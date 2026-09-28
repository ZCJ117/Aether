package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D3/VUL-05 端到端：异步委派 → 终态回传 → 父会话下一轮自动注入 → SSE 事件。
 *
 * <p>全程真对象，只有"被委派的子Agent执行器"与"父 Agent 的模型"是替身：
 * 真 {@link CompletionBus} → 真 {@link PendingDelegationInbox} → 真 {@link DelegationCompletionSink}
 * → 真 {@link AsyncDelegationService} → 真 {@link ReActAgent}（真 queryLoop、真收件箱消费、
 * 真消息装配、真 {@link ModelInvoker} 真流式路径）。</p>
 *
 * <p>对应验收用例 T5-7 / T5-8 / T5-10。</p>
 */
class AsyncDelegationCompletionE2ETest {

    private static final String SESSION = "e2e-delegation-session";

    /**
     * T5-7 + T5-10：一次异步委派完成后，父会话下一轮消息中确实包含
     * {@code [子任务完成通知]} 的系统消息，且 SSE 流上出现 {@code delegation} 事件。
     */
    @Test
    void delegatedResultReachesParentSessionNextTurn() throws Exception {
        // ── 真回传链路：bus → sink → inbox ──
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        new DelegationCompletionSink(bus, inbox, null, null).subscribe();

        // ── 真 AsyncDelegationService；替身只有子Agent执行器与租约闸 ──
        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        LeaseManager leaseManager = mock(LeaseManager.class);
        SpawnGate spawnGate = mock(SpawnGate.class);
        when(leaseManager.acquireLease(any())).thenReturn(true);

        ResultRefiner.SubAgentResult result =
                new ResultRefiner.SubAgentResult("成功", "[结论] 子任务已完成", Map.of());
        when(lifecycle.launch(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(lifecycle.status(any())).thenReturn(Optional.of(SubagentState.COMPLETED));
        when(lifecycle.result(any())).thenReturn(Optional.of(result));

        InMemoryDelegationStore store = new InMemoryDelegationStore();
        AsyncDelegationService service =
                new AsyncDelegationService(lifecycle, bus, leaseManager, spawnGate, store);

        // ── 异步委派一条任务（已完成的 future → whenComplete 同步收尾） ──
        String delegationId = service.dispatch(new DelegationTask(
                "分析代码", List.of("code"), null, "u1", SESSION, "parent-agent"));
        assertNotNull(delegationId);
        assertTrue(delegationId.startsWith("ad-"));

        // ── 回传已落到父会话收件箱（推模式核心） ──
        assertEquals(1, inbox.size(),
                "委派终态必须回传到父会话收件箱，而不是「发射后不管」");
        assertTrue(store.isDelivered(delegationId),
                "有订阅者且投递成功 → 应置位 completion_delivered");

        // ── 父会话下一轮：真 ReActAgent 消费收件箱 ──
        List<Prompt> requests = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return Flux.just(ok());
        });

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder().name("e2e-parent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                model, new ModelInvoker(), mock(ToolExecutor.class), realContextManager(),
                null, null, null, null, null, null);
        agent.setDelegationInbox(inbox);

        List<RuntimeEvent> events = agent
                .execute(new RuntimeContext("u1", SESSION, null, null, "继续", null, null))
                .toList().blockingGet();

        // ── T5-7：注入的消息列表包含含 [子任务完成通知] 的系统消息 ──
        assertFalse(requests.isEmpty(), "父 Agent 应至少调用模型一次");
        String injected = requests.get(0).getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> m.getText() == null ? "" : m.getText())
                .filter(t -> t.contains("[子任务完成通知]"))
                .findFirst().orElse(null);
        assertNotNull(injected, "父会话下一轮必须收到「子任务完成通知」系统消息；实际消息="
                + requests.get(0).getInstructions().stream()
                        .map(m -> m.getMessageType() + ":" + m.getText()).toList());
        assertTrue(injected.contains(delegationId), "通知须带上真实 delegationId");
        assertTrue(injected.contains("COMPLETED"), "通知须带上终态");
        assertTrue(injected.contains("[结论] 子任务已完成"), "通知须带上子任务摘要");

        // ── T5-10：SSE 流上出现委派完成事件 ──
        assertTrue(events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.delegation),
                "SSE 流上必须出现 RuntimeEvent.EventType.delegation 事件");

        // ── 已消费：不重复注入 ──
        assertEquals(0, inbox.size(), "drain 为原子取出并清空，不得重复注入");
    }

    /**
     * T5-8：启动回灌在重启后生效 —— 手工插入 {@code completion_delivered=false} 的终态记录，
     * 以全新内存态（模拟重启）启动 → 收件箱出现该条，且该记录最终置位 {@code true}。
     */
    @Test
    void startupReplayRestoresUndeliveredCompletionAfterRestart() {
        InMemoryDelegationStore store = new InMemoryDelegationStore();
        store.seed(DelegationRecord.builder()
                .id("ad-replay").parentSessionId(SESSION).parentAgentId("parent-agent")
                .taskPayload("上次容器崩溃前完成的子任务")
                .state(SubagentState.COMPLETED).resultSummary("[结论] 崩溃前的成果")
                .attemptCount(1).createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
        assertFalse(store.isDelivered("ad-replay"));

        // ── 重启：全新的 bus / inbox / sink（内存收件箱已丢失），复用同一持久化 store ──
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox inbox = new PendingDelegationInbox(null);
        new DelegationCompletionSink(bus, inbox, null, null).subscribe();

        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        LeaseManager leaseManager = mock(LeaseManager.class);
        AsyncDelegationService service = new AsyncDelegationService(
                lifecycle, bus, leaseManager, mock(SpawnGate.class), store);

        service.start();

        assertEquals(1, inbox.size(), "回灌应把未投递的终态记录重新投进父会话收件箱");
        DelegationCompletion replayed = inbox.drain(SESSION).get(0);
        assertEquals("ad-replay", replayed.delegationId());
        assertEquals(SubagentState.COMPLETED, replayed.status());
        assertEquals("[结论] 崩溃前的成果", replayed.summary());
        assertTrue(store.isDelivered("ad-replay"),
                "投递成功后记录应置位 completion_delivered=true（去重，避免下次重复回灌）");
    }

    /**
     * T5-8 反向：回灌时<b>无订阅者</b> → 不置位 → 下次启动继续重放（幂等，不丢）。
     */
    @Test
    void startupReplayKeepsRecordUndeliveredWhenNoSubscriber() {
        InMemoryDelegationStore store = new InMemoryDelegationStore();
        store.seed(DelegationRecord.builder()
                .id("ad-orphan").parentSessionId(SESSION).taskPayload("task")
                .state(SubagentState.FAILED).resultSummary("[失败]")
                .attemptCount(1).updatedAt(Instant.now())
                .build());

        // 无 sink 订阅者
        CompletionBus bus = new CompletionBus();
        AsyncDelegationService service = new AsyncDelegationService(
                mock(SubagentLifecycleService.class), bus, mock(LeaseManager.class),
                mock(SpawnGate.class), store);

        service.start();

        assertFalse(store.isDelivered("ad-orphan"),
                "无订阅者时不得置位 —— 否则回灌永久失效（静默数据丢失）");
    }

    /**
     * VUL-05 收口：<b>投递失败 → 不置位 → 下次启动回灌补投</b>的完整闭环。
     *
     * <p>这是「静默数据丢失」的端到端回归锁。若 sink 把入桶失败也算作投递成功，
     * 记录会被置位 {@code completion_delivered=true}，该事件既不在收件箱也不可回灌 —— 永久丢失。</p>
     */
    @Test
    void failedDeliveryIsRecoveredByStartupReplay() {
        InMemoryDelegationStore store = new InMemoryDelegationStore();

        // ── 第一次运行：收件箱故障，sink 无法投递 ──
        CompletionBus bus = new CompletionBus();
        PendingDelegationInbox brokenInbox = mock(PendingDelegationInbox.class);
        doThrow(new RuntimeException("inbox down")).when(brokenInbox).offer(any(), any());
        new DelegationCompletionSink(bus, brokenInbox, null, null).subscribe();

        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireLease(any())).thenReturn(true);
        ResultRefiner.SubAgentResult result =
                new ResultRefiner.SubAgentResult("成功", "[结论] 子任务已完成", Map.of());
        when(lifecycle.launch(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(lifecycle.status(any())).thenReturn(Optional.of(SubagentState.COMPLETED));
        when(lifecycle.result(any())).thenReturn(Optional.of(result));

        AsyncDelegationService service = new AsyncDelegationService(
                lifecycle, bus, leaseManager, mock(SpawnGate.class), store);

        String id = service.dispatch(new DelegationTask(
                "分析代码", List.of("code"), null, "u1", SESSION, "parent-agent"));

        assertNotNull(id);
        assertFalse(store.isDelivered(id),
                "投递失败必须保留 completion_delivered=false，否则该事件永久丢失");

        // ── 第二次运行（模拟重启）：全新的 bus / 收件箱（这次正常）→ 回灌补投 ──
        CompletionBus bus2 = new CompletionBus();
        PendingDelegationInbox goodInbox = new PendingDelegationInbox(null);
        new DelegationCompletionSink(bus2, goodInbox, null, null).subscribe();
        new AsyncDelegationService(mock(SubagentLifecycleService.class), bus2,
                mock(LeaseManager.class), mock(SpawnGate.class), store).start();

        assertEquals(1, goodInbox.size(), "回灌应把投递失败的事件补投到父会话收件箱");
        assertEquals(id, goodInbox.drain(SESSION).get(0).delegationId());
        assertTrue(store.isDelivered(id), "补投成功后应置位，避免反复回灌");
    }

    // ── helpers ──

    private static ChatResponse ok() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
    }

    /** 真 ContextManager：真 TokenEstimator（替身掉窗口与系数），消息数下限收紧以便压缩路径可用。 */
    private static ContextManager realContextManager() throws Exception {
        TokenEstimator estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text == null ? 0 : (int) Math.ceil(text.length() / 3.5);
        });
        when(estimator.getContextWindow(any())).thenReturn(30_000);

        CompactionTrigger trigger = new CompactionTrigger();
        setField(trigger, "minMessagesToCompact", 10);

        ContextManager cm = new ContextManager(null, null);
        setField(cm, "tokenEstimator", estimator);
        setField(cm, "compactionTrigger", trigger);
        return cm;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 内存 delegation store：模拟 PgAsyncDelegationStore 的"终态未投递"语义。 */
    private static final class InMemoryDelegationStore implements AsyncDelegationStore {

        private final Map<String, DelegationRecord> rows = new HashMap<>();
        private final Set<String> delivered = new HashSet<>();

        synchronized void seed(DelegationRecord rec) {
            rows.put(rec.getId(), rec);
        }

        synchronized boolean isDelivered(String id) {
            return delivered.contains(id);
        }

        private static boolean isTerminal(SubagentState s) {
            return s != null && s != SubagentState.QUEUED
                    && s != SubagentState.PENDING && s != SubagentState.RUNNING;
        }

        @Override
        public synchronized void save(DelegationRecord record) {
            rows.put(record.getId(), record);
        }

        @Override
        public synchronized List<DelegationRecord> listBySession(String sessionId) {
            return rows.values().stream()
                    .filter(r -> sessionId.equals(r.getParentSessionId())).toList();
        }

        @Override
        public synchronized List<DelegationRecord> findPendingStale(Instant staleBefore, int limit) {
            return List.of();
        }

        @Override
        public synchronized List<DelegationRecord> findUndeliveredTerminal(int limit) {
            return rows.values().stream()
                    .filter(r -> isTerminal(r.getState()) && !delivered.contains(r.getId()))
                    .limit(limit).toList();
        }

        @Override
        public synchronized void markTerminal(String id, SubagentState terminal, String resultSummary) {
            DelegationRecord r = rows.get(id);
            if (r != null) {
                r.setState(terminal);
                r.setResultSummary(resultSummary);
                r.setUpdatedAt(Instant.now());
            }
        }

        @Override
        public synchronized void markQueuedForRetry(String id, int newAttemptCount) {
            DelegationRecord r = rows.get(id);
            if (r != null) {
                r.setState(SubagentState.QUEUED);
                r.setAttemptCount(newAttemptCount);
            }
        }

        @Override
        public synchronized void updateHeartbeat(String id, Instant at) {
            DelegationRecord r = rows.get(id);
            if (r != null) {
                r.setLastHeartbeatAt(at);
            }
        }

        @Override
        public synchronized void markCompletionDelivered(String id) {
            delivered.add(id);
            DelegationRecord r = rows.get(id);
            if (r != null) {
                r.setCompletionDelivered(true);
            }
        }
    }
}
