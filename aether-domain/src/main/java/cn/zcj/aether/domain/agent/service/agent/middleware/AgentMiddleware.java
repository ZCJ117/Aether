package cn.zcj.aether.domain.agent.service.agent.middleware;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 中间件接口 —— 洋葱模型。
 * 灵感来源：AgentScope MiddlewareBase（五层洋葱）+ Koa/Express 中间件模式。
 *
 * 每个拦截点都可以返回修改后的事件流，实现横切关注点的非侵入注入。
 */
public interface AgentMiddleware {

    /** 中间件名称 */
    String name();

    /** 优先级：数值越小越先执行（外层） */
    default int priority() { return 100; }

    /** 是否启用 */
    default boolean isEnabled() { return true; }

    // =========================================================
    // 五个拦截点
    // =========================================================

    /**
     * 系统提示词变换 —— 在发送给模型之前修改 system prompt。
     * 示例：注入当前时间、注入用户偏好、注入临时规则。
     */
    default String onSystemPrompt(String systemPrompt, Agent agent, RuntimeContext ctx) {
        return systemPrompt;
    }

    /**
     * 完整 Agent 调用包装 —— 最外层洋葱。
     * @param next 实际的 Agent 执行逻辑
     */
    default Flowable<RuntimeEvent> onAgent(
            Agent agent, RuntimeContext ctx,
            Supplier<Flowable<RuntimeEvent>> next) {
        return next.get();
    }

    /**
     * 推理阶段拦截 —— 在调用模型之前。
     */
    default List<Message> onReasoning(
            List<Message> messages, Agent agent, RuntimeContext ctx) {
        return messages;
    }

    /**
     * 模型调用拦截 —— 包装模型调用。
     * 示例：记录 token 使用、注入缓存、切换模型。
     */
    default ModelInvoker.ModelCallResult onModelCall(
            Supplier<ModelInvoker.ModelCallResult> next,
            Agent agent, RuntimeContext ctx, String modelName) {
        return next.get();
    }

    /**
     * 工具执行拦截 —— 在工具调用之前。
     * 示例：权限检查、参数改写、工具调用审计。
     */
    default List<ToolExecutor.ToolCallRequest> onActing(
            List<ToolExecutor.ToolCallRequest> requests,
            Agent agent, RuntimeContext ctx) {
        return requests;
    }
}
