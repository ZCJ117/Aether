package cn.zcj.aether.domain.agent.service.agent.core;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可取消执行令牌。
 * 借鉴 AutoGen CancellationToken 设计。
 *
 * 使用方式：
 *   CancelToken token = new CancelToken(Instant.now().plusSeconds(60));
 *   while (!token.isCancelled()) { ... }
 *   token.cancel(); // 外部取消
 */
public class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Instant deadline;

    /** 无超时限制 */
    public CancelToken() {
        this.deadline = null;
    }

    /** 带超时限制 */
    public CancelToken(Instant deadline) {
        this.deadline = deadline;
    }

    /** 已取消或已超时 */
    public boolean isCancelled() {
        return cancelled.get() || (deadline != null && Instant.now().isAfter(deadline));
    }

    /** 主动取消 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 仅超时检查，不含主动取消 */
    public boolean isExpired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }
}
