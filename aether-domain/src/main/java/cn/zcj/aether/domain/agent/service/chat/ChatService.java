package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.IChatService;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.executor.GraphExecutor;
import cn.zcj.aether.domain.agent.service.memory.MemoryStore;
import cn.zcj.aether.domain.agent.service.runtime.AgentRuntime;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private AgentRuntime agentRuntime;

    @Resource
    private GraphExecutor graphExecutor;

    @Resource
    private MemoryStore memoryStore;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * ChatModel — 从策略树装配阶段（ChatModelNode.doApply）动态注册
     * @Lazy 延迟注入避免装配未完成时的初始化错误
     */
    @Resource
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

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

        return userSessions.computeIfAbsent(userId, uid -> {
            String sessionId = UUID.randomUUID().toString();
            log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);
            return sessionId;
        });
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

        // 加载记忆
        String memoryPrompt = memoryStore.loadMemoryPrompt(message);

        // 多Agent工作流 → GraphExecutor
        if (graph.getEdges() != null && !graph.getEdges().isEmpty()) {
            log.info("路由到 GraphExecutor: edges={}", graph.getEdges().size());
            List<String> outputs = new ArrayList<>();
            graphExecutor.execute(graph, chatModel, userId, sessionId, message)
                    .blockingForEach(event -> {
                        if (event.getType() == RuntimeEvent.EventType.textDelta
                                && event.getText() != null) {
                            outputs.add(event.getText());
                        }
                    });
            return outputs;
        }

        // 单Agent → AgentRuntime
        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        if (entry == null) {
            throw new AppException(ResponseCode.E0001.getCode(), "入口Agent未配置: " + graph.getEntryPoint());
        }

        String instruction = injectMemory(entry.getInstruction(), memoryPrompt);
        log.info("Agent entry resolved: name={}, instructionLen={}, modelRef={}",
                entry.getName(),
                instruction != null ? instruction.length() : 0,
                entry.getModelRef());
        AgentNodeDef resolved = AgentNodeDef.builder()
                .name(entry.getName())
                .instruction(instruction)
                .description(entry.getDescription())
                .outputKey(entry.getOutputKey())
                .toolNames(entry.getToolNames())
                .modelRef(entry.getModelRef())
                .build();

        List<String> outputs = new ArrayList<>();
        agentRuntime.execute(resolved, chatModel, userId, sessionId, message)
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

        String memoryPrompt = memoryStore.loadMemoryPrompt(message);

        // 多Agent工作流 → GraphExecutor
        if (graph.getEdges() != null && !graph.getEdges().isEmpty()) {
            log.info("流式路由到 GraphExecutor: edges={}", graph.getEdges().size());
            return graphExecutor.execute(graph, chatModel, userId, sessionId, message);
        }

        // 单Agent → AgentRuntime
        AgentNodeDef entry = graph.getAgentDefs().get(graph.getEntryPoint());
        if (entry == null) {
            return Flowable.error(new AppException(ResponseCode.E0001.getCode(),
                    "入口Agent未配置: " + graph.getEntryPoint()));
        }

        String instruction = injectMemory(entry.getInstruction(), memoryPrompt);
        log.info("Agent entry resolved (stream): name={}, instructionLen={}, modelRef={}",
                entry.getName(),
                instruction != null ? instruction.length() : 0,
                entry.getModelRef());
        AgentNodeDef resolved = AgentNodeDef.builder()
                .name(entry.getName())
                .instruction(instruction)
                .description(entry.getDescription())
                .outputKey(entry.getOutputKey())
                .toolNames(entry.getToolNames())
                .modelRef(entry.getModelRef())
                .build();

        return agentRuntime.execute(resolved, chatModel, userId, sessionId, message);
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

    private String injectMemory(String instruction, String memoryPrompt) {
        if (memoryPrompt == null || memoryPrompt.isEmpty()) return instruction;
        if (instruction == null) return memoryPrompt;
        return instruction.replace("{memory}", memoryPrompt);
    }
}
