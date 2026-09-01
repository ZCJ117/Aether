package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.IChatService;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.executor.GraphExecutor;
import cn.zcj.aether.domain.agent.service.memory.MemoryInjectionService;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryContextScrubber;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.session.SessionService;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import cn.zcj.aether.types.exception.StateRestoreException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent对话服务 — 对话编排（O2 职责收敛）
 *
 * <p>O2 拆分后仅保留"对话编排"单一职责：handleMessage / handleMessageStream / handleConfirm。
 * 会话生命周期与恢复委托 {@link SessionService}；记忆注入委托
 * {@link MemoryInjectionService}；模型目录委托
 * {@link cn.zcj.aether.domain.agent.service.model.ModelCatalogService}；
 * 仪表盘统计委托 {@link DashboardService}。
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private AgentRegistry agentRegistry;

    @Resource
    private GraphExecutor graphExecutor;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * P0-1 新增：Agent 工厂，替代直接调用 AgentRuntime
     */
    @Resource
    private DefaultAgentFactory agentFactory;

    /**
     * O2: 会话生命周期（创建/删除/恢复/消息提取）。
     */
    @Resource
    private SessionService sessionService;

    /**
     * O2: 记忆注入。
     */
    @Resource
    private MemoryInjectionService memoryInjectionService;

    /**
     * 记忆生命周期门面（turn 后 syncTurn 持久化）。
     * required=false：未配置 EmbeddingModel/向量库或 aether.memory.enabled=false 时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryLifecycleHooks memoryLifecycleHooks;

    /** 流式路径捕获助手文本的上限（防超长回复写入记忆） */
    private static final int MAX_SYNC_CAPTURE_CHARS = 8000;

    @Override
    public List<AiAgentConfigTableVO> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO> list = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    list.add(vo);
                }
            }
        }
        return list;
    }

    @Override
    public String createSession(String agentId, String userId) {
        return sessionService.createSession(agentId, userId);
    }

    @Override
    public void deleteSession(String sessionId) {
        sessionService.deleteSession(sessionId);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = sessionService.createSession(agentId, userId);
        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 多Agent工作流 → GraphExecutor（P0-1 改造：不再传 chatModel）
        if (graph.getEdges() != null && !graph.getEdges().isEmpty()) {
            log.info("路由到 GraphExecutor: edges={}", graph.getEdges().size());
            List<String> outputs = new ArrayList<>();
            graphExecutor.execute(graph, userId, sessionId, message)
                    .blockingForEach(event -> {
                        if (event.getType() == RuntimeEvent.EventType.textDelta
                                && event.getText() != null) {
                            outputs.add(event.getText());
                        }
                    });
            return outputs;
        }

        // 单Agent → 通过 AgentFactory 创建 Agent 实例（P0-1 改造）
        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        if (entry == null) {
            throw new AppException(ResponseCode.E0001.getCode(), "入口Agent未配置: " + graph.getEntryPoint());
        }

        // P1-4: 记忆注入（优先 MemoryLifecycleHooks prefetch，回退文件存储）
        String instruction = memoryInjectionService.injectMemory(
                entry.getInstruction(), message, entry.getName(), sessionId);
        log.info("Agent entry resolved: name={}, instructionLen={}, modelRef={}",
                entry.getName(),
                instruction != null ? instruction.length() : 0,
                entry.getModelRef());

        // P0-1: 通过 AgentFactory 创建 Agent 实例
        AgentConfig agentConfig = AgentConfig.builder()
                .name(entry.getName())
                .instruction(instruction)
                .description(entry.getDescription())
                .outputKey(entry.getOutputKey())
                .toolNames(entry.getToolNames())
                .modelRef(entry.getModelRef())
                .agentType(entry.getAgentType() != null ? entry.getAgentType() : "react")
                .cancelToken(new CancelToken())
                .build();

        Agent agent = agentFactory.create(agentConfig);

        // H5-步骤6: 会话恢复 —— 加载已保存的状态（O2: 委托 SessionService）
        // O4/O6: 同步路径与流式路径语义统一——恢复失败响亮报错，不带病恢复
        restoreSessionOrThrow(agent, sessionId);

        // H4: 检查 Agent 是否处于 PAUSED 状态
        if (agent.getState().getStatus() == AgentState.AgentStatus.PAUSED
                && agent.getState().hasPendingAsking()) {
            throw new AppException(ResponseCode.E0001.getCode(),
                    "Agent 已暂停，等待用户确认。请先通过 /api/v1/confirm 提交确认结果。");
        }

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, null, null,
                entry.getName());

        List<String> outputs = new ArrayList<>();
        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        outputs.add(event.getText());
                    }
                });

        // 记忆生命周期：turn 后持久化（异步，经净化防记忆回显递归污染）
        if (memoryLifecycleHooks != null && !outputs.isEmpty()) {
            String assistantText = MemoryContextScrubber.sanitize(String.join("", outputs));
            memoryLifecycleHooks.syncTurn(message, assistantText, sessionId, null);
        }

        return outputs;
    }

    @Override
    public Flowable<RuntimeEvent> handleMessageStream(
            String agentId, String userId, String sessionId, String message) {

        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode()));
        }

        // 多Agent工作流 → GraphExecutor（P0-1 改造：不再传 chatModel）
        if (graph.getEdges() != null && !graph.getEdges().isEmpty()) {
            log.info("流式路由到 GraphExecutor: edges={}", graph.getEdges().size());
            return graphExecutor.execute(graph, userId, sessionId, message);
        }

        // 单Agent → 通过 AgentFactory 创建 Agent 实例（P0-1 改造）
        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        if (entry == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "入口Agent未配置: " + graph.getEntryPoint()));
        }

        // P1-4: 记忆注入（优先 MemoryLifecycleHooks prefetch，回退文件存储）
        String instruction = memoryInjectionService.injectMemory(
                entry.getInstruction(), message, entry.getName(), sessionId);
        log.info("Agent entry resolved (stream): name={}, instructionLen={}, modelRef={}",
                entry.getName(),
                instruction != null ? instruction.length() : 0,
                entry.getModelRef());

        // P0-1: 通过 AgentFactory 创建 Agent 实例
        AgentConfig agentConfig = AgentConfig.builder()
                .name(entry.getName())
                .instruction(instruction)
                .description(entry.getDescription())
                .outputKey(entry.getOutputKey())
                .toolNames(entry.getToolNames())
                .modelRef(entry.getModelRef())
                .agentType(entry.getAgentType() != null ? entry.getAgentType() : "react")
                .cancelToken(new CancelToken())
                .build();

        Agent agent = agentFactory.create(agentConfig);

        // H5-步骤6: 会话恢复 —— O2: 委托 SessionService
        try {
            sessionService.restoreSession(agent, sessionId);
        } catch (StateRestoreException e) {
            // 状态恢复失败 → 响亮报错，不带病恢复（对齐 autogen 语义）
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "会话状态恢复失败: " + e.getMessage()));
        }

        // H4: 检查 Agent 是否处于 PAUSED 状态，若是则需注入 confirmResults
        Map<String, Object> metadata = new HashMap<>();
        if (agent.getState().getStatus() == AgentState.AgentStatus.PAUSED
                && agent.getState().hasPendingAsking()) {
            // PAUSED 状态但没有确认回执 → 返回错误
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "Agent 已暂停，等待用户确认。请先通过 /api/v1/confirm 提交确认结果。"));
        }

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, metadata, null,
                entry.getName());

        // 记忆生命周期：捕获助手文本（经流式净化防记忆回显递归污染）+ turn 后持久化
        // 注意：Flowable 为冷流，闭包捕获的 scrubber/captured 仅支持单次订阅；
        //       onError 终止时（模型失败/超时/取消）不触发 syncTurn，避免持久化残缺轮次。
        MemoryContextScrubber.StreamingScrubber scrubber = new MemoryContextScrubber.StreamingScrubber();
        StringBuilder captured = new StringBuilder();
        return agent.execute(ctx)
                .doOnNext(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        String clean = scrubber.feed(event.getText());
                        if (captured.length() < MAX_SYNC_CAPTURE_CHARS) {
                            captured.append(clean);
                        }
                    }
                })
                .doOnComplete(() -> {
                    captured.append(scrubber.flush());
                    if (memoryLifecycleHooks != null && captured.length() > 0) {
                        memoryLifecycleHooks.syncTurn(message, captured.toString(), sessionId, null);
                    }
                });
    }

    /**
     * H4: 处理用户确认 —— 恢复挂起的 Agent 执行。
     *
     * @param agentId   Agent ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param confirmResults 用户确认结果列表
     * @return 恢复后的执行事件流
     */
    public Flowable<RuntimeEvent> handleConfirm(
            String agentId, String userId, String sessionId,
            List<ConfirmResult> confirmResults) {

        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode()));
        }

        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        if (entry == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "入口Agent未配置: " + graph.getEntryPoint()));
        }

        // 恢复之前的 Agent 状态
        String instruction = memoryInjectionService.injectMemory(
                entry.getInstruction(), "", entry.getName(), sessionId);

        AgentConfig agentConfig = AgentConfig.builder()
                .name(entry.getName())
                .instruction(instruction)
                .description(entry.getDescription())
                .outputKey(entry.getOutputKey())
                .toolNames(entry.getToolNames())
                .modelRef(entry.getModelRef())
                .agentType(entry.getAgentType() != null ? entry.getAgentType() : "react")
                .cancelToken(new CancelToken())
                .build();

        Agent agent = agentFactory.create(agentConfig);

        // H5-步骤6: 加载已保存的会话状态（O2: 委托 SessionService）
        try {
            sessionService.restoreSession(agent, sessionId);
        } catch (StateRestoreException e) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "会话状态恢复失败: " + e.getMessage()));
        }

        // 注入确认回执到 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("confirmResults", confirmResults);

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null,
                "[用户已提交工具调用确认]", metadata, null, entry.getName());

        return agent.execute(ctx);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        return handleMessage(
                chatCommandEntity.getAgentId(),
                chatCommandEntity.getUserId(),
                chatCommandEntity.getSessionId(),
                chatCommandEntity.getTexts() != null && !chatCommandEntity.getTexts().isEmpty()
                        ? chatCommandEntity.getTexts().get(0).getMessage()
                        : "");
    }

    /**
     * 同步路径的会话恢复：恢复失败（StateRestoreException）响亮抛出，
     * 与流式路径语义一致（O4/O6）。
     */
    private void restoreSessionOrThrow(Agent agent, String sessionId) {
        try {
            sessionService.restoreSession(agent, sessionId);
        } catch (StateRestoreException e) {
            log.error("会话状态恢复失败（同步路径）: sessionId={}, error={}", sessionId, e.getMessage());
            throw new AppException(ResponseCode.E0001.getCode(),
                    "会话状态恢复失败: " + e.getMessage());
        }
    }
}
