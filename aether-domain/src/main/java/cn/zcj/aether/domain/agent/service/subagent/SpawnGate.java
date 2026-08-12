package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 委派 Spawn 闸门 — 对齐 hermes delegate_tool.py set_spawn_paused（L153）/ 深度计数（L2795）。
 * <p>setSpawnPaused 全局暂停新委派（只挡新 spawn，已运行子Agent不受影响，见 L2775）；
 * 深度用 ThreadLocal 追踪当前执行线程的委派嵌套深度（子=父+1，见 _build_child_agent L1234）。
 * 同步路径子Agent在调用线程 blocking 执行，深度正确累计；异步路径各池线程从 0 起（已知简化）。</p>
 */
@Slf4j
@Component
public class SpawnGate {

    public static final int DEFAULT_MAX_DEPTH = 3;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final int maxDepth;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public SpawnGate() {
        this(DEFAULT_MAX_DEPTH);
    }

    public SpawnGate(int maxDepth) {
        this.maxDepth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
    }

    /** 全局暂停开关：true 时新委派一律拒绝。 */
    public void setSpawnPaused(boolean value) {
        paused.set(value);
    }

    public boolean isSpawnPaused() {
        return paused.get();
    }

    public int maxDepth() {
        return maxDepth;
    }

    /**
     * 尝试进入一层委派：暂停或已达深度上限则拒绝（返回 false），否则深度+1 返回 true。
     * 调用方须在 finally 中配对调用 {@link #exit()}。
     */
    public boolean enter() {
        if (paused.get()) {
            log.warn("SpawnGate: 委派已暂停，拒绝新 spawn");
            return false;
        }
        int d = depth.get();
        if (d >= maxDepth) {
            log.warn("SpawnGate: 委派深度已达上限 maxDepth={}", maxDepth);
            return false;
        }
        depth.set(d + 1);
        return true;
    }

    /** 退出当前委派深度（与 enter 成对，finally 中调用）。归零后清除 ThreadLocal，防止池线程滞留值。 */
    public void exit() {
        int d = depth.get();
        if (d <= 1) {
            depth.remove(); // 0 或 1 → 归零并清除，防止 ThreadLocal 值滞留池线程
        } else {
            depth.set(d - 1);
        }
    }

    public int currentDepth() {
        return depth.get();
    }
}
