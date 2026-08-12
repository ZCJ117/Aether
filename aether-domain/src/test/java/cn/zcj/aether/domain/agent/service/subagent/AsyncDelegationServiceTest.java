package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AsyncDelegationServiceTest {

    private SubagentLifecycleService lifecycle;
    private CompletionBus completionBus;
    private LeaseManager leaseManager;
    private SpawnGate spawnGate;
    private AsyncDelegationStore store;
    private AsyncDelegationService service;

    @BeforeEach
    void setUp() {
        lifecycle = mock(SubagentLifecycleService.class);
        completionBus = mock(CompletionBus.class);
        leaseManager = mock(LeaseManager.class);
        spawnGate = mock(SpawnGate.class);
        store = mock(AsyncDelegationStore.class);
        // AsyncDelegationService 不自建线程池：执行由 SubagentLifecycleService 内部池承担（code-review M1）
        service = new AsyncDelegationService(lifecycle, completionBus, leaseManager, spawnGate, store);
        when(leaseManager.acquireLease(any())).thenReturn(true);
    }

    private static DelegationTask task() {
        return new DelegationTask("分析代码", List.of("code"), null, "u1", "s1", "parent");
    }

    @Test
    void dispatchPersistsQueuedAndLaunches() {
        when(lifecycle.launch(any(), any())).thenReturn(new CompletableFuture<>());
        String id = service.dispatch(task());
        assertNotNull(id);
        assertTrue(id.startsWith("ad-"));
        verify(store).save(argThat(rec ->
                rec.getState() == SubagentState.QUEUED && rec.getAttemptCount() == 1));
        verify(lifecycle).launch(eq(id), eq(task()));
        verify(leaseManager).acquireLease("s1");
    }

    @Test
    void dispatchRejectsWhenLeaseFull() {
        when(leaseManager.acquireLease("s1")).thenReturn(false);
        assertNull(service.dispatch(task()));
        verify(store, never()).save(any());
        verify(lifecycle, never()).launch(any(), any());
    }

    @Test
    void completionPersistedAndPublishedOnSuccess() {
        ResultRefiner.SubAgentResult result =
                new ResultRefiner.SubAgentResult("成功", "[结论]", java.util.Map.of());
        CompletableFuture<ResultRefiner.SubAgentResult> future = CompletableFuture.completedFuture(result);
        when(lifecycle.launch(any(), any())).thenReturn(future);
        when(lifecycle.status(any())).thenReturn(java.util.Optional.of(SubagentState.COMPLETED));
        when(lifecycle.result(any())).thenReturn(java.util.Optional.of(result));

        service.dispatch(task());

        verify(store).markTerminal(argThat(id -> id.startsWith("ad-")),
                eq(SubagentState.COMPLETED), eq("[结论]"));
        verify(completionBus).publish(argThat(c -> c.status() == SubagentState.COMPLETED));
        verify(store).markCompletionDelivered(argThat(id -> id.startsWith("ad-")));
        verify(leaseManager).releaseLease("s1");
    }

    @Test
    void recoverAbandonedRequeuesWithIncrementedAttempt() {
        DelegationRecord stale = DelegationRecord.builder()
                .id("ad-5").parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of("code")).state(SubagentState.RUNNING).attemptCount(1).build();
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of(stale));
        when(leaseManager.acquireLease("s1")).thenReturn(true);
        when(lifecycle.launch(any(), any())).thenReturn(new CompletableFuture<>());

        int recovered = service.recoverAbandoned();

        assertEquals(1, recovered);
        verify(store).markQueuedForRetry("ad-5", 2);
        verify(lifecycle).launch(eq("ad-5"), any());
    }

    @Test
    void recoverAbandonedCapsAtMaxAttempts() {
        DelegationRecord stale = DelegationRecord.builder()
                .id("ad-6").parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of()).state(SubagentState.RUNNING).attemptCount(8).build();
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of(stale));

        int recovered = service.recoverAbandoned();

        assertEquals(0, recovered);
        verify(store).markTerminal("ad-6", SubagentState.FAILED, "[超出最大尝试次数 8]");
        verify(lifecycle, never()).launch(any(), any());
    }

    @Test
    void recoverAbandonedIsIdempotent() {
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of());
        service.recoverAbandoned();
        service.recoverAbandoned();
        verify(store, times(1)).findPendingStale(any(), eq(100));
    }

    @Test
    void restoreUndeliveredReplaysCompletions() {
        when(store.findUndeliveredTerminal(100)).thenReturn(List.of());
        assertEquals(0, service.restoreUndelivered());
        verify(completionBus).subscribeFromPersistence(store);
    }

    @Test
    void interruptForSessionCancelsMatchingActive() {
        when(lifecycle.activeIds()).thenReturn(List.of("ad-1", "ad-2"));
        when(lifecycle.taskOf("ad-1")).thenReturn(java.util.Optional.of(
                new DelegationTask("a", List.of(), null, "u", "s1", "p")));
        when(lifecycle.taskOf("ad-2")).thenReturn(java.util.Optional.of(
                new DelegationTask("b", List.of(), null, "u", "s2", "p")));
        when(lifecycle.cancel("ad-1")).thenReturn(true);

        int count = service.interruptForSession("s1", "test");

        assertEquals(1, count);
        verify(lifecycle).cancel("ad-1");
        verify(lifecycle, never()).cancel("ad-2");
    }

    @Test
    void listBySessionDelegatesToStore() {
        when(store.listBySession("s1")).thenReturn(List.of(
                DelegationRecord.builder().id("ad-1").parentSessionId("s1").taskPayload("t").build()));
        assertEquals(1, service.listBySession("s1").size());
        verify(store).listBySession("s1");
    }

    @Test
    void recoverAbandonedReleasesLeaseWhenStoreThrows() {
        DelegationRecord stale = DelegationRecord.builder()
                .id("ad-7").parentSessionId("s1").parentAgentId("a1").taskPayload("task")
                .toolNames(List.of()).state(SubagentState.RUNNING).attemptCount(1).build();
        when(store.findPendingStale(any(), eq(100))).thenReturn(List.of(stale));
        when(leaseManager.acquireLease("s1")).thenReturn(true);
        doThrow(new RuntimeException("db down")).when(store).markQueuedForRetry("ad-7", 2);

        int recovered = service.recoverAbandoned();

        assertEquals(0, recovered);
        verify(leaseManager).releaseLease("s1");
        verify(store).markTerminal("ad-7", SubagentState.FAILED, "[恢复重入队失败: db down]");
    }

    @Test
    void dispatchReleasesLeaseAndSkipsMarkTerminalWhenSaveThrows() {
        doThrow(new RuntimeException("db down")).when(store).save(any());
        assertNull(service.dispatch(task()));
        verify(leaseManager).releaseLease("s1");
        verify(store, never()).markTerminal(any(), any(), any());
    }

    @Test
    void memoryDegradationWhenStoreAbsent() {
        AsyncDelegationService memoryService = new AsyncDelegationService(
                lifecycle, completionBus, leaseManager, spawnGate, (AsyncDelegationStore) null);
        when(lifecycle.launch(any(), any())).thenReturn(new CompletableFuture<>());

        String id = memoryService.dispatch(task());

        assertNotNull(id);
        verify(lifecycle).launch(any(), any());
        verify(store, never()).save(any());           // 无 store 不落库
        assertEquals(0, memoryService.recoverAbandoned());  // 无 store 恢复为 0
        assertEquals(0, memoryService.restoreUndelivered()); // 无 store 回灌为 0
        assertTrue(memoryService.listBySession("s1").isEmpty());
    }
}
