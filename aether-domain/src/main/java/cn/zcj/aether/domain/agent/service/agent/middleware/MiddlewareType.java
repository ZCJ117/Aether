package cn.zcj.aether.domain.agent.service.agent.middleware;

/**
 * 中间件类型枚举。
 * 灵感来源：AgentScope MiddlewareType — 对中间件按功能域分类。
 *
 * 用于：
 * - 按类型批量启用/禁用中间件
 * - 日志和监控中识别中间件类别
 * - 按类型注入不同的中间件配置
 */
public enum MiddlewareType {

    /** 安全类：权限检查、速率限制 */
    SECURITY,

    /** 可观测类：日志、Metrics、Tracing */
    OBSERVABILITY,

    /** 生命周期类：优雅关闭、健康检查 */
    LIFECYCLE,

    /** 业务增强类：任务提醒、上下文补充 */
    ENHANCEMENT,

    /** 自定义（用户注册的中间件） */
    CUSTOM
}
