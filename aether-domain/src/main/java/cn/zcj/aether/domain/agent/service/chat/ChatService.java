package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.IChatService;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import cn.zcj.aether.types.exception.StateRestoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.zcj.aether.domain.agent.service.executor.GraphExecutor;
import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import cn.zcj.aether.domain.agent.service.memory.MemoryStore;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * AI Agent对话服务 — 基于自研AgentRuntime (替代Google ADK)
 *
 * 主要职责:
 *   1. 管理用户会话
 *   2. 加载记忆到上下文
 *   3. 调用AgentRuntime执行对话
 *   4. 支持同步/流式消息处理
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private AgentRegistry agentRegistry;

    @Resource
    private GraphExecutor graphExecutor;

    @Resource
    private MemoryStore memoryStore;

    /**
     * P1-4 新增：多层记忆门面（优先使用语义搜索，回退文件存储）。
     * required=false：未配置 EmbeddingModel/向量数据库时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryFacade memoryFacade;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * P0-1 新增：Agent 工厂，替代直接调用 AgentRuntime
     */
    @Resource
    private DefaultAgentFactory agentFactory;

    /**
     * P0-4 新增：会话持久化仓储（用于会话恢复）。
     * required=false：未配置数据源时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SessionRepository sessionRepository;

    /**
     * P0-#8 新增：检查点收集器（用于从文件检查点恢复 Agent 状态）。
     * required=false：未配置检查点收集器时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CheckpointCollector checkpointCollector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.zcj.aether.domain.agent.service.retrieval.IdentifierRegistry identifierRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }
        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);

        // 立即持久化空会话，使其出现在列表 API 中
        if (sessionRepository != null) {
            SessionEntity entity = SessionEntity.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .agentId(agentId)
                    .status("ACTIVE")
                    .stateJson(null)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            sessionRepository.save(entity)
                    .exceptionally(ex -> {
                        log.warn("会话持久化失败: sessionId={}", sessionId, ex);
                        return null;
                    });
        }

        return sessionId;
    }

    /**
     * 查询用户在某 Agent 下的所有活跃会话。
     */
    public List<SessionEntity> listSessions(String agentId, String userId) {
        if (sessionRepository == null) {
            return List.of();
        }
        return sessionRepository.listByUserIdAndAgentId(userId, agentId);
    }

    /**
     * 仪表盘统计：活跃智能体数 + 活跃会话数 + 各智能体会话分布。
     * 返回原始数据，由 Controller 层组装 DTO。
     */
    public java.util.Map<String, Object> getDashboardRawStats() {
        var agentList = queryAiAgentConfigList();
        int totalAgents = agentList.size();
        int activeSessions = sessionRepository != null ? sessionRepository.countActiveSessions() : 0;
        var sessionsByAgent = sessionRepository != null
                ? sessionRepository.countSessionsByAgent() : java.util.Map.<String, Integer>of();

        // 以配置中的智能体为准：有会话则显示计数，无会话则显示 0
        java.util.List<java.util.Map<String, Object>> agentStats = new java.util.ArrayList<>();
        for (var agent : agentList) {
            int count = sessionsByAgent.getOrDefault(agent.getAgentId(), 0);
            agentStats.add(java.util.Map.of(
                    "agentId", agent.getAgentId(),
                    "agentName", agent.getAgentName(),
                    "sessionCount", count));
        }

        return java.util.Map.of(
                "totalAgents", totalAgents,
                "activeSessions", activeSessions,
                "agentStats", agentStats);
    }

    /**
     * 软删除会话（状态改为 ARCHIVED），将 deleteBySessionId 改为软删除策略。
     */
    @Override
    public void deleteSession(String sessionId) {
        if (sessionRepository == null) {
            log.warn("SessionRepository 未配置，无法删除会话: sessionId={}", sessionId);
            return;
        }
        sessionRepository.deleteBySessionId(sessionId);
        log.info("会话已删除: sessionId={}", sessionId);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);
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

        // P1-4: 记忆注入（优先 MemoryFacade 语义搜索，回退文件存储）
        String instruction = injectMemory(entry.getInstruction(), message, entry.getName());
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

        // H5-步骤6: 会话恢复 —— 加载已保存的状态（仅在 sessionRepository 可用时）
        if (sessionRepository != null && sessionId != null) {
            restoreSession(agent, sessionId);
        }

        // H4: 检查 Agent 是否处于 PAUSED 状态
        if (agent.getState().getStatus() == AgentState.AgentStatus.PAUSED
                && agent.getState().hasPendingAsking()) {
            throw new AppException(ResponseCode.E0001.getCode(),
                    "Agent 已暂停，等待用户确认。请先通过 /api/v1/confirm 提交确认结果。");
        }

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, null, null);

        List<String> outputs = new ArrayList<>();
        agent.execute(ctx)
                .blockingForEach(event -> {
                    if (event.getType() == RuntimeEvent.EventType.textDelta
                            && event.getText() != null) {
                        outputs.add(event.getText());
                    }
                });

        return outputs;
    }

    @Override
    public Flowable<RuntimeEvent> handleMessageStream(
            String agentId, String userId, String sessionId, String message) {

        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode()));
        }

        // P1-4: 记忆注入（优先 MemoryFacade 语义搜索，回退文件存储）

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

        String instruction = injectMemory(entry.getInstruction(), message, entry.getName());
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

        // H5-步骤6: 会话恢复 —— 加载已保存的状态（仅在 sessionRepository 可用时）
        if (sessionRepository != null && sessionId != null) {
            try {
                restoreSession(agent, sessionId);
            } catch (StateRestoreException e) {
                // 状态恢复失败 → 响亮报错，不带病恢复（对齐 autogen 语义）
                return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                        "会话状态恢复失败: " + e.getMessage()));
            }
        }

        // H4: 检查 Agent 是否处于 PAUSED 状态，若是则需注入 confirmResults
        Map<String, Object> metadata = new HashMap<>();
        if (agent.getState().getStatus() == AgentState.AgentStatus.PAUSED
                && agent.getState().hasPendingAsking()) {
            // PAUSED 状态但没有确认回执 → 返回错误
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "Agent 已暂停，等待用户确认。请先通过 /api/v1/confirm 提交确认结果。"));
        }

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null, message, metadata, null);

        return agent.execute(ctx);
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
        String instruction = injectMemory(entry.getInstruction(), "", entry.getName());

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

        // H5-步骤6: 加载已保存的会话状态
        if (sessionRepository != null && sessionId != null) {
            try {
                restoreSession(agent, sessionId);
            } catch (StateRestoreException e) {
                return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                        "会话状态恢复失败: " + e.getMessage()));
            }
        }

        // 注入确认回执到 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("confirmResults", confirmResults);

        RuntimeContext ctx = new RuntimeContext(userId, sessionId, null, null,
                "[用户已提交工具调用确认]", metadata, null);

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
     * P0-#8: 从最新检查点恢复会话。
     * 如果存在检查点，加载 agentState → 返回给调用方用于 loadState()。
     * 无检查点则抛出 AppException。
     *
     * 借鉴 CrewAI 的 from_checkpoint（快照恢复）+ MetaGPT 的 recovered 标志。
     */
    public java.util.Map<String, Object> resumeFromCheckpoint(String agentId, String sessionId) {
        if (checkpointCollector == null) {
            throw new AppException(ResponseCode.E0001.getCode(), "检查点收集器未配置");
        }

        var ckpt = checkpointCollector.loadLatest(sessionId)
                .orElseThrow(() -> new AppException(ResponseCode.E0001.getCode(),
                        "会话 " + sessionId + " 无可用检查点"));

        log.info("从检查点恢复: agentId={}, sessionId={}, turnNumber={}, messageCount={}",
                agentId, sessionId, ckpt.getTurnNumber(), ckpt.getMessageCount());

        return ckpt.getAgentState();
    }

    /**
     * P0-#8: 列出会话的所有检查点（按时间倒序）
     */
    public java.util.List<CheckpointData> listCheckpoints(String sessionId) {
        if (checkpointCollector == null) return List.of();
        return checkpointCollector.listCheckpoints(sessionId);
    }

    /**
     * H5-步骤6: 从 SessionRepository 恢复 Agent 状态。
     *
     * <p>强校验恢复路径：
     * <ul>
     *   <li>JSON 解析失败 → 警告后回退新会话（兼容旧格式）</li>
     *   <li>{@link StateRestoreException}（字段缺失） → 响亮抛出不带病恢复</li>
     *   <li>恢复后用槽位重建运行时组件（对齐 AgentScope ReActAgent L437-477）</li>
     * </ul>
     *
     * @param agent     目标 Agent 实例
     * @param sessionId 会话 ID
     * @throws StateRestoreException 如果必需字段缺失
     */
    private void restoreSession(Agent agent, String sessionId) {
        try {
            var opt = sessionRepository.findBySessionId(sessionId);
            if (opt.isEmpty()) return;

            String stateJson = opt.get().getStateJson();
            if (stateJson == null || stateJson.isEmpty()) {
                log.info("会话无历史状态，将作为新会话处理: sessionId={}", sessionId);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> savedState = objectMapper.readValue(stateJson, Map.class);

            // H5-步骤6: loadState 内部会校验必需字段，缺失时抛 StateRestoreException
            agent.loadState(savedState);

            log.info("恢复会话: sessionId={}, turnCount={}, status={}",
                    sessionId,
                    savedState.getOrDefault("currentTurn", 0),
                    savedState.getOrDefault("status", "unknown"));

            // H5-步骤6: 用恢复的槽位重建运行时组件
            // （PermissionEngine 重建在 H4 方案落地时完成，
            //   此处预留重建钩子——toolContext 槽位数据已随 loadState 恢复）
        } catch (StateRestoreException e) {
            // 字段缺失类错误 → 响亮传播，拒绝带病恢复
            log.error("会话状态恢复失败（字段缺失）: sessionId={}, error={}", sessionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            // JSON 解析失败等非字段缺失错误 → 兼容旧格式，回退新会话
            log.warn("会话状态 JSON 解析失败，将作为新会话处理: sessionId={}", sessionId, e);
        }
    }

    /**
     * P1-4: 记忆注入（优先使用 MemoryFacade 语义搜索，回退文件存储关键词匹配）。
     */
    private String injectMemory(String instruction, String userMessage, String agentId) {
        // Phase 9: 注入标识符上下文（项目文件结构+文档索引）
        if (identifierRegistry != null) {
            try {
                String identifierCtx = identifierRegistry.buildIdentifierContext();
                if (instruction != null && !identifierCtx.isEmpty()) {
                    instruction = instruction + "\n\n" + identifierCtx;
                }
            } catch (Exception e) {
                log.debug("标识符上下文生成失败: {}", e.getMessage());
            }
        }

        // P1-4: 优先使用 MemoryFacade 语义搜索
        if (memoryFacade != null) {
            MemoryScope scope = MemoryScope.global().subscope("agent").subscope(agentId);
            List<MemorySearchResult> results = memoryFacade.search(userMessage, scope, 5);

            if (!results.isEmpty()) {
                StringBuilder memoryBlock = new StringBuilder("\n\n<auto-memory>\n");
                memoryBlock.append("以下是与当前对话相关的历史记忆，请参考但不强制使用：\n\n");
                for (int i = 0; i < results.size(); i++) {
                    MemorySearchResult r = results.get(i);
                    memoryBlock.append("记忆").append(i + 1).append(": ")
                        .append(r.getRecord().getContent()).append("\n\n");
                }
                memoryBlock.append("</auto-memory>");

                if (instruction == null) return memoryBlock.toString();
                String enriched = instruction.replace("{memory}", memoryBlock.toString());
                if (enriched.equals(instruction)) {
                    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                             "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryFacade 路径）。", agentId);
                }
                return enriched;
            }
        }

        // 回退：文件存储关键词匹配
        String memoryPrompt = memoryStore.loadMemoryPrompt(userMessage);
        if (memoryPrompt == null || memoryPrompt.isEmpty()) return instruction;
        if (instruction == null) return memoryPrompt;
        String enriched = instruction.replace("{memory}", memoryPrompt);
        if (enriched.equals(instruction)) {
            log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                     "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryStore 回退路径）。", agentId);
        }
        return enriched;
    }
}
