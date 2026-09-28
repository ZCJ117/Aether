package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D3/VUL-05 F5-2：<b>{@code CompletionBus.subscribe} 的第一个生产调用点</b>。
 * 对应验收用例 T5-2 —— "有发布无订阅"（故障 (a)）的回归锁。
 */
class DelegationCompletionSinkWiringTest {

    private static DelegationCompletion completion() {
        return new DelegationCompletion("ad-1", "s1", "a1", "task",
                SubagentState.COMPLETED, "ok", Instant.now());
    }

    /**
     * T5-2（域内可跑的一半）：三个 Bean 在最小 Spring 上下文中可装配，且装配后
     * {@code CompletionBus} 有订阅者、收件箱收到事件。
     *
     * <p><b>注意</b>：裸 {@code AnnotationConfigApplicationContext} 在本模块测试作用域下
     * 不会执行 {@code @PostConstruct}（Spring 6.2 的 {@code InitDestroyAnnotationBeanPostProcessor}
     * 已移除 {@code getInitAnnotationType} 等 API，生命周期注解处理路径与 Boot 上下文不同）。
     * 因此这里显式声明"生命周期回调必须存在"，真正的 Boot 上下文装配验证见
     * {@code aether-app} 的 {@code CompletionBusWiringTest}（T5-2 完整版）。</p>
     */
    @Test
    void subscribeIsDeclaredAsLifecycleCallback() throws Exception {
        Method subscribe = DelegationCompletionSink.class.getDeclaredMethod("subscribe");
        assertNotNull(subscribe.getAnnotation(PostConstruct.class),
                "subscribe() 必须带 @PostConstruct —— 这是 Boot 上下文里自动注册订阅者的唯一途径");

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                CompletionBus.class, PendingDelegationInbox.class, DelegationCompletionSink.class)) {
            CompletionBus bus = ctx.getBean(CompletionBus.class);
            PendingDelegationInbox inbox = ctx.getBean(PendingDelegationInbox.class);

            assertEquals(0, bus.publish(completion()),
                    "裸上下文不跑 @PostConstruct → 此时尚无订阅者（说明该断言确实依赖回调被执行）");

            ctx.getBean(DelegationCompletionSink.class).subscribe();

            assertEquals(1, bus.publish(completion()),
                    "subscribe() 执行后必须有订阅者 —— 否则 publish 只是遍历空集合的空循环");
            assertEquals(1, inbox.size(), "订阅者应把完成事件落到父会话收件箱");
        }
    }

    /** 上下文无需 AgentEventPublisher / MeterRegistry 也能装配（两者均可选依赖）。 */
    @Test
    void springContextBootsWithoutOptionalCollaborators() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                CompletionBus.class, PendingDelegationInbox.class, DelegationCompletionSink.class)) {
            assertNotNull(ctx.getBean(DelegationCompletionSink.class),
                    "无事件通道与 MeterRegistry 时仍应装配成功");
            assertFalse(ctx.containsBean("agentEventPublisher"),
                    "本上下文不含事件通道，sink 应按可空依赖降级");
        }
    }

    /** 投递顺序：入收件箱 + 转发事件通道（SSE/Kafka 桥由后者消费）。 */
    @Test
    void onCompletionOffersToInboxAndForwardsEvent() {
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        new DelegationCompletionSink(bus, inbox, publisher, null).subscribe();

        bus.publish(completion());

        assertEquals(1, inbox.size(), "完成事件必须落到父会话收件箱");
        verify(publisher).publishDelegation("a1", "s1", null, "ad-1", 0, "COMPLETED");
    }

    /** AgentEventPublisher 缺失时只做收件箱投递，不影响正确性。 */
    @Test
    void onCompletionToleratesMissingEventPublisher() {
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        new DelegationCompletionSink(bus, inbox, null, null).subscribe();

        assertEquals(1, bus.publish(completion()), "无事件通道也应投递成功");
        assertEquals(1, inbox.size());
    }

    /**
     * <b>热路径安全 + 投递判定</b>：入桶失败时既不向上抛异常，也<b>不</b>计入投递数。
     *
     * <p>这是 VUL-05「静默数据丢失」的回归锁。规格 §7.1.9 要求"<em>返回值不含该订阅者</em>"，
     * 但若只是简单吞掉异常，{@code publish} 仍会把它算作投递成功 → 调用方置位
     * {@code completion_delivered} → 该事件既不在收件箱也不可回灌，永久丢失。
     * 因此投递判定必须以"是否真正入桶"为准（见 {@code CompletionBus.DeliveryAwareSubscriber}）。</p>
     */
    @Test
    void onCompletionReportsFailureWithoutThrowingWhenInboxRejects() {
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = mock(PendingDelegationInbox.class);
        doThrow(new RuntimeException("inbox down")).when(inbox).offer(any(), any());
        new DelegationCompletionSink(bus, inbox, null, null).subscribe();

        int delivered = 0;
        try {
            delivered = bus.publish(completion());
        } catch (Exception e) {
            fail("发布热路径不得因订阅者内部异常而中断: " + e);
        }

        assertEquals(0, delivered,
                "入桶失败必须报告未投递，否则该事件永久丢失（无法回灌）");
    }

    /** 事件通道转发失败<b>不影响</b>投递判定：已入桶即算投递，避免回灌重复投递。 */
    @Test
    void eventForwardingFailureDoesNotAffectDelivery() {
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        doThrow(new RuntimeException("bridge down")).when(publisher)
                .publishDelegation(any(), any(), any(), any(), anyInt(), any());
        new DelegationCompletionSink(bus, inbox, publisher, null).subscribe();

        assertEquals(1, bus.publish(completion()),
                "事件通道失败不应把已入桶的事件判为未投递");
        assertEquals(1, inbox.size());
    }

    /** null 完成事件被忽略，不抛 NPE。 */
    @Test
    void onCompletionIgnoresNullCompletion() {
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        new DelegationCompletionSink(bus, inbox, null, null).subscribe();

        assertDoesNotThrow(() -> bus.publish(null));
        assertEquals(0, inbox.size());
    }
}
