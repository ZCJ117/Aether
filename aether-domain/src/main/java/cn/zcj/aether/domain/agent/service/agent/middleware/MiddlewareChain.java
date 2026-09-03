package cn.zcj.aether.domain.agent.service.agent.middleware;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 中间件洋葱链构建器。
 * 灵感来源：AgentScope MiddlewareChain + Koa compose() 模式。
 *
 * 使用方式：
 * <pre>
 * MiddlewareChain chain = new MiddlewareChain(agent, ctx)
 *     .use(new LoggingHook())
 *     .use(new RateLimitMiddleware(10, Duration.ofMinutes(1)))
 *     .use(new PermissionMiddleware(permissionEngine));
 * </pre>
 */
@Slf4j
public class MiddlewareChain {

    private final Agent agent;
    private final RuntimeContext ctx;
    private final List<AgentMiddleware> middlewares = new CopyOnWriteArrayList<>();

    public MiddlewareChain(Agent agent, RuntimeContext ctx) {
        this.agent = agent;
        this.ctx = ctx;
    }

    /** 添加中间件（按优先级自动排序） */
    public MiddlewareChain use(AgentMiddleware middleware) {
        if (middleware.isEnabled()) {
            middlewares.add(middleware);
            middlewares.sort(Comparator.comparingInt(AgentMiddleware::priority));
        }
        return this;
    }

    // =========================================================
    // 五个拦截点的洋葱包装方法
    // =========================================================

    /** 执行系统提示词变换链 */
    public String applySystemPrompt(String originalPrompt) {
        String result = originalPrompt;
        for (AgentMiddleware mw : middlewares) {
            try {
                result = mw.onSystemPrompt(result, agent, ctx);
            } catch (Exception e) {
                log.warn("中间件 [{}] onSystemPrompt 抛出异常: {}", mw.name(), e.getMessage());
            }
        }
        return result;
    }

    /** 执行 Agent 包装链（洋葱最外层） */
    public Flowable<RuntimeEvent> wrapAgent(Supplier<Flowable<RuntimeEvent>> core) {
        // 从内到外构建洋葱
        Supplier<Flowable<RuntimeEvent>> wrapped = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            AgentMiddleware mw = middlewares.get(i);
            Supplier<Flowable<RuntimeEvent>> current = wrapped;
            wrapped = () -> mw.onAgent(agent, ctx, current);
        }
        return wrapped.get();
    }

    /** 执行推理链 */
    public List<Message> applyReasoning(List<Message> messages) {
        List<Message> result = messages;
        for (AgentMiddleware mw : middlewares) {
            try {
                result = mw.onReasoning(result, agent, ctx);
            } catch (Exception e) {
                log.warn("中间件 [{}] onReasoning 异常: {}", mw.name(), e.getMessage());
            }
        }
        return result;
    }

    /** 执行模型调用链 */
    public ModelInvoker.ModelCallResult applyModelCall(
            Supplier<ModelInvoker.ModelCallResult> core, String modelName) {
        Supplier<ModelInvoker.ModelCallResult> wrapped = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            AgentMiddleware mw = middlewares.get(i);
            Supplier<ModelInvoker.ModelCallResult> current = wrapped;
            String mn = modelName;
            wrapped = () -> mw.onModelCall(current, agent, ctx, mn);
        }
        return wrapped.get();
    }

    /**
     * 执行工具调用链。
     *
     * <p>fail-closed：任一中间件（尤其权限校验）抛出异常时，立即中断链路并返回空列表
     * （= 拒绝本批全部工具调用）。异常被吞掉后继续放行会把权限校验击穿成 fail-open，
     * 因此这里宁可全部拒绝。</p>
     */
    public List<ToolExecutor.ToolCallRequest> applyActing(
            List<ToolExecutor.ToolCallRequest> requests) {
        List<ToolExecutor.ToolCallRequest> result = requests;
        for (AgentMiddleware mw : middlewares) {
            try {
                result = mw.onActing(result, agent, ctx);
            } catch (Exception e) {
                log.error("中间件 [{}] onActing 异常，fail-closed 拒绝本批 {} 个工具调用: {}",
                        mw.name(), requests.size(), e.getMessage(), e);
                return List.of();
            }
        }
        return result;
    }
}
