package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.Builder;
import lombok.Value;
import java.util.Map;

/**
 * 权限检查上下文。
 */
@Value
@Builder
public class PermissionContext {
    String toolName;
    String toolCallId;
    Map<String, Object> toolInput;
    String agentId;
    String userId;
    String sessionId;
    boolean isReadOnly;
    PermissionMode mode;
}
