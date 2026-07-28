package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.Builder;
import lombok.Value;
import java.util.HashMap;
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

    /**
     * H4 新增：可变属性容器，供规则间传递上下文（如脱敏 key 列表）。
     * 不同于不可变的 toolInput，此 Map 可由规则写入。
     */
    @Builder.Default
    Map<String, Object> attributes = new HashMap<>();
}
