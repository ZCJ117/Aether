package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 委派完成事件的唯一生产订阅者 —— 把 {@link AsyncDelegationService} 推来的 completion
 * 落到父会话收件箱并转发到事件通道，补齐"发布无订阅"的断链（VUL-05 (a)）。
 *
 * <p>这是 {@link CompletionBus#subscribe} 的第一个生产调用点。缺失时 {@code publish} 只是
 * 遍历空集合的空循环，异步委派退化为"发射后不管"。</p>
 *
 * <p>本类位于发布热路径，{@link #onCompletion} <b>必须不抛异常</b>：整体 try/catch 吞掉并计数，
 * 与 {@link CompletionBus} 的订阅者异常隔离构成双重兜底。</p>
 */
@Slf4j
@Component
public class DelegationCompletionSink {

    private final CompletionBus completionBus;
    private final PendingDelegationInbox inbox;
    /** 事件通道（SSE/Kafka 桥）可选：未装配时只做收件箱投递，不影响正确性。 */
    private final AgentEventPublisher eventPublisher;
    /** 指标注册表可选（actuator 未装配时为 null → 跳过打点）。 */
    private final MeterRegistry meterRegistry;

    public DelegationCompletionSink(CompletionBus completionBus,
                                    PendingDelegationInbox inbox,
                                    @Autowired(required = false) AgentEventPublisher eventPublisher,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.completionBus = completionBus;
        this.inbox = inbox;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
    }

    /** 注册为 CompletionBus 的订阅者（Bean 初始化时执行一次）。 */
    @PostConstruct
    public void subscribe() {
        completionBus.subscribeReporting(this::onCompletion);
    }

    /**
     * 处理单条完成事件：入父会话收件箱（<b>投递判定点</b>）→ 转发事件通道 → 日志与计数。
     *
     * <p><b>投递判定以"是否落入父会话收件箱"为准</b>：只有 {@code inbox.offer} 成功才返回 true，
     * 即计入 {@link CompletionBus#publish} 的返回值 → 置位 {@code completion_delivered}。
     * 若它失败则返回 false，使记录保留未投递状态、由下次启动回灌重放 —— 否则该事件
     * 既不在收件箱也不可回灌（静默数据丢失，正是 VUL-05 要消灭的那一类）。</p>
     *
     * <p>事件通道转发与打点是"尽力而为"：置于入桶之后，其失败<b>不影响</b>投递判定，
     * 避免把已入桶的事件再判为未投递而导致回灌重复投递。</p>
     *
     * <p>热路径安全：本方法不抛异常。</p>
     *
     * @return true = 已落入父会话收件箱
     */
    boolean onCompletion(DelegationCompletion completion) {
        if (completion == null) {
            return false;
        }
        try {
            inbox.offer(completion.parentSessionId(), completion);
        } catch (Exception e) {
            log.warn("DelegationCompletionSink: 入收件箱失败（未投递，待回灌重放）id={}, err={}",
                    completion.delegationId(), e.getMessage());
            countError();
            return false;
        }
        String status = completion.statusName();
        try {
            if (eventPublisher != null) {
                eventPublisher.publishDelegation(completion.parentAgentId(), completion.parentSessionId(),
                        null, completion.delegationId(), 0, status);
            }
            log.info("委派完成已投递: id={}, session={}, status={}",
                    completion.delegationId(), completion.parentSessionId(), status);
            count(status);
        } catch (Exception e) {
            // 已入桶 → 仍算投递成功；事件通道/打点失败只降级为告警
            log.warn("DelegationCompletionSink: 事件转发/打点失败（不影响投递判定）id={}, err={}",
                    completion.delegationId(), e.getMessage());
        }
        return true;
    }

    private void count(String status) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("aether.delegation.completion.delivered", "status", status).increment();
    }

    private void countError() {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("aether.delegation.completion.delivered", "result", "error").increment();
    }
}
