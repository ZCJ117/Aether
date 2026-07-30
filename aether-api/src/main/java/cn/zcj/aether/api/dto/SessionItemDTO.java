package cn.zcj.aether.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * 会话列表项 DTO — 用于 GET /api/v1/list_sessions 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionItemDTO {
    private String sessionId;
    private String agentId;
    private String userId;
    private String title;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
