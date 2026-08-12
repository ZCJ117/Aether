package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 委派并发租约 — 对齐 hermes async_delegation.py 容量检查（L678-694，max_async_children 默认 3）
 * + credential_pool.py acquire_lease（L1926，软租约/ref-count）。
 * <p>每 session 并发上限：同 session 活跃委派数 ≥ maxAsyncChildren 时新委派直接 rejected
 * （不排队，对齐 hermes L680-683）。Aether 无 hermes 的 ChildLease 类（软租约），
 * 简化为每 session AtomicInteger + CAS。acquireLease 成功者须在完成时 releaseLease。</p>
 */
@Slf4j
@Component
public class LeaseManager {

    public static final int DEFAULT_MAX_ASYNC_CHILDREN = 3;

    private final int maxAsyncChildren;
    private final ConcurrentMap<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();

    public LeaseManager() {
        this(DEFAULT_MAX_ASYNC_CHILDREN);
    }

    public LeaseManager(int maxAsyncChildren) {
        this.maxAsyncChildren = maxAsyncChildren > 0 ? maxAsyncChildren : DEFAULT_MAX_ASYNC_CHILDREN;
    }

    /** 尝试为 session 获取一个并发槽位；已达上限返回 false（拒绝，不排队）。 */
    public boolean acquireLease(String sessionId) {
        AtomicInteger counter = sessionCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur >= maxAsyncChildren) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /** 释放 session 的一个槽位（幂等）。 */
    public void releaseLease(String sessionId) {
        AtomicInteger counter = sessionCounters.get(sessionId);
        if (counter == null) {
            return;
        }
        counter.updateAndGet(c -> Math.max(0, c - 1));
    }

    public int activeLeases(String sessionId) {
        AtomicInteger counter = sessionCounters.get(sessionId);
        return counter == null ? 0 : counter.get();
    }
}
