package cn.zcj.aether.domain.agent.service.agent.hook;

/**
 * 全局生命周期钩子点枚举 — 对齐 hermes plugins.py VALID_HOOKS（L135）。
 * 覆盖全生命周期：API request / subagent / session / graph 节点级。
 * Aether 特有：ON_GRAPH_* 系列（图节点生命周期）。
 */
public enum HookPoint {
    // 工具调用（agent 内部 AgentHook 已有，此处为编排级复用）
    PRE_TOOL_CALL, POST_TOOL_CALL,

    // LLM 调用
    PRE_LLM_CALL, POST_LLM_CALL,

    // API 请求（对齐 hermes pre_api_request / post_api_request / api_request_error）
    PRE_API_REQUEST, POST_API_REQUEST, API_REQUEST_ERROR,

    // 子代理（对齐 hermes subagent_start / subagent_stop）
    SUBAGENT_START, SUBAGENT_STOP,

    // 会话生命周期（对齐 hermes on_session_start / on_session_end）
    ON_SESSION_START, ON_SESSION_END,

    // Aether 特有：图执行生命周期
    ON_GRAPH_FINALIZE, ON_GRAPH_NODE_START, ON_GRAPH_NODE_END
}
