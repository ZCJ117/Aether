package cn.zcj.aether.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话消息 DTO — 用于 GET /api/v1/session_messages 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessageDTO {
    private String role;
    private String content;
}
