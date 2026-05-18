package cn.zcj.aether.domain.agent.service.tool;

import java.util.Map;

/**
 * 统一工具接口
 * 吸取项目B src/Tool.ts:362-695 的设计理念
 */
public interface Tool {

    /** 工具名称 */
    String name();

    /** 工具描述 (给LLM看) */
    String description();

    /** JSON Schema 输入定义 */
    Map<String, Object> inputSchema();

    /** 执行工具 */
    ToolResult call(Map<String, Object> input, ToolContext context);

    /** 是否并发安全 — 默认false (吸取项目B TOOL_DEFAULTS, Tool.ts:759) */
    default boolean isConcurrencySafe() {
        return false;
    }

    /** 是否只读操作 */
    default boolean isReadOnly() {
        return false;
    }

    /** 输入校验 */
    default boolean validateInput(Map<String, Object> input) {
        return true;
    }

    /** 权限检查 */
    default boolean checkPermissions(Map<String, Object> input) {
        return true;
    }
}

/** 工具执行上下文 */
record ToolContext(String userId, String sessionId, String toolCallId) {}
