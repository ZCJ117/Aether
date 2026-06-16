package cn.zcj.aether.domain.agent.service.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * 会话实体 — P0-4 新增
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionEntity {
    private String sessionId;
    private String userId;
    private String agentId;
    private String status;         // ACTIVE / ARCHIVED / ERROR
    private String stateJson;      // AgentState 的 JSON 序列化结果
    private Instant createdAt;
    private Instant updatedAt;
}
