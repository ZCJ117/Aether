package cn.zcj.aether.domain.agent.service.runtime;

import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * Agent 运行时 — 自研主循环引擎的运行时服务聚合。
 *
 * P0-1 重构后，执行逻辑已迁移到 {@link cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent}，
 * 会话持久化由 P0-4 {@link cn.zcj.aether.domain.agent.service.session.SessionRepository} 接管。
 * 此类现在作为轻量服务聚合，对外暴露 ModelInvoker、ContextManager、ToolExecutor 的引用。
 *
 * @deprecated 新代码请使用 {@link cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory} 创建 Agent 实例。
 */
@Deprecated
@Slf4j
@Service
public class AgentRuntime {

    @Resource
    private ContextManager contextManager;

    @Resource
    private ToolExecutor toolExecutor;

    @Resource
    private ModelInvoker modelInvoker;

}
