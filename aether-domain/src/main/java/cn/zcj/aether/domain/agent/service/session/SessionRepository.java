package cn.zcj.aether.domain.agent.service.session;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 会话持久化仓储接口 — P0-4 新增
 * 灵感来源：AgentScope AgentStateStore。
 */
public interface SessionRepository {

    /** 保存或更新会话状态 */
    CompletableFuture<Void> save(SessionEntity entity);

    /** 按 sessionId 查找 */
    Optional<SessionEntity> findBySessionId(String sessionId);

    /** 删除会话 */
    CompletableFuture<Void> deleteBySessionId(String sessionId);

    /** 按 userId 列出活跃会话 */
    java.util.List<SessionEntity> listByUserId(String userId);

    /** 按 userId + agentId 列出活跃会话 */
    java.util.List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId);
}
