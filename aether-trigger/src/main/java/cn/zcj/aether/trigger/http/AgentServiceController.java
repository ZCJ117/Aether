package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.IAgentService;
import cn.zcj.aether.api.dto.*;
import cn.zcj.aether.api.dto.SessionMessageDTO;
import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.security.annotation.Auditable;
import cn.zcj.aether.types.enums.AuditAction;
import cn.zcj.aether.domain.agent.service.IChatService;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.chat.ChatService;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.*;

/**
 * REST控制器 — AI Agent服务HTTP入口
 *
 * DDD分层中的触发层:
 *   1. 接收HTTP请求
 *   2. 参数转换 (DTO → 领域对象)
 *   3. 调用domain层服务
 *   4. 结果转换 (领域对象 → DTO)
 *   5. 统一异常包装
 *
 * H4 更新：
 * - CORS 改为按 profile 配置白名单（不再 * 全开）
 * - 新增 POST /api/v1/confirm 权限确认回执端点
 * - SSE 序列化新增 permissionAsking / agentPaused 事件类型
 *
 * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
 * 面试举证点：{@code chatStream()}（行365）是 Flowable→SSE 的边缘承接点——以 10 分钟超时的
 * ResponseBodyEmitter（行374）订阅领域层 {@code Flowable<RuntimeEvent>}，将逐事件经
 * {@code serializeEvent()}（行485）序列化为 SSE data 帧（textDelta/toolCall/permissionAsking/
 * agentPaused/checkpoint/tokenBudget 等）；correlationId 入 MDC（行369）串联全链路；
 * {@code confirm()}（行429）以同款 SSE 承接权限确认恢复流。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    /** H4: 直接注入 ChatService 实现以访问 handleConfirm 方法 */
    @Resource
    private ChatService chatServiceImpl;

    /** O2: 会话生命周期（列表/删除/消息提取）由 SessionService 承接 */
    @Resource
    private cn.zcj.aether.domain.agent.service.session.SessionService sessionService;

    /** O2: 模型目录由 ModelCatalogService 承接 */
    @Resource
    private cn.zcj.aether.domain.agent.service.model.ModelCatalogService modelCatalogService;

    /** O2: 仪表盘统计由 DashboardService 承接 */
    @Resource
    private cn.zcj.aether.domain.agent.service.chat.DashboardService dashboardService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** H4: CORS 允许的来源白名单（逗号分隔，默认 * 保持向后兼容） */
    @Value("${aether.cors.allowed-origins:*}")
    private String allowedOrigins;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO> configs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = configs.stream().map(config -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(config.getAgent().getAgentId());
                responseDTO.setAgentName(config.getAgent().getAgentName());
                responseDTO.setAgentDesc(config.getAgent().getAgentDesc());
                // 从 YAML module.chatModel.model 提取模型名
                if (config.getModule() != null && config.getModule().getChatModel() != null) {
                    responseDTO.setModelRef(config.getModule().getChatModel().getModel());
                }
                return responseDTO;
            }).collect(java.util.stream.Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(
            @RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @Auditable(value = AuditAction.AGENT_CHAT, resource = "agent")
    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            List<String> messages = chatService.handleMessage(
                    requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "list_sessions", method = RequestMethod.GET)
    @Override
    public Response<List<SessionItemDTO>> listSessions(
            @RequestParam("agentId") String agentId,
            @RequestParam("userId") String userId) {
        try {
            log.info("查询会话列表 agentId={} userId={}", agentId, userId);
            List<SessionEntity> entities = sessionService.listSessions(agentId, userId);
            List<SessionItemDTO> dtos = entities.stream()
                    .map(e -> SessionItemDTO.builder()
                            .sessionId(e.getSessionId())
                            .agentId(e.getAgentId())
                            .userId(e.getUserId())
                            .title(extractTitle(e))
                            .status(e.getStatus())
                            .createdAt(e.getCreatedAt())
                            .updatedAt(e.getUpdatedAt())
                            .build())
                    .toList();
            return Response.<List<SessionItemDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("查询会话列表失败 agentId={} userId={}", agentId, userId, e);
            return Response.<List<SessionItemDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "delete_session", method = RequestMethod.DELETE)
    @Override
    public Response<Void> deleteSession(@RequestParam("sessionId") String sessionId) {
        try {
            log.info("删除会话 sessionId={}", sessionId);
            chatServiceImpl.deleteSession(sessionId);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("删除会话失败 sessionId={}", sessionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "session_messages", method = RequestMethod.GET)
    public Response<List<SessionMessageDTO>> getSessionMessages(@RequestParam("sessionId") String sessionId) {
        try {
            log.info("查询会话消息 sessionId={}", sessionId);
            var rawMessages = sessionService.getSessionMessages(sessionId);
            var dtos = rawMessages.stream()
                    .map(m -> SessionMessageDTO.builder()
                            .role(m.get("role"))
                            .content(m.get("content"))
                            .build())
                    .toList();
            return Response.<List<SessionMessageDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("查询会话消息失败 sessionId={}", sessionId, e);
            return Response.<List<SessionMessageDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "models", method = RequestMethod.GET)
    public Response<List<ModelConfigDTO>> getModels() {
        try {
            log.info("查询模型列表");
            var raw = modelCatalogService.getConfiguredModels();
            var dtos = raw.stream()
                    .map(m -> ModelConfigDTO.builder()
                            .id(m.get("id"))
                            .providerId(m.get("providerId"))
                            .modelId(m.get("modelId"))
                            .status(m.get("status"))
                            .build())
                    .toList();
            return Response.<List<ModelConfigDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("查询模型列表失败", e);
            return Response.<List<ModelConfigDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "dashboard_stats", method = RequestMethod.GET)
    @Override
    @SuppressWarnings("unchecked")
    public Response<DashboardStatsDTO> dashboardStats() {
        try {
            log.info("查询仪表盘统计");
            java.util.Map<String, Object> raw = dashboardService.getDashboardRawStats();
            int totalAgents = (int) raw.get("totalAgents");
            int activeSessions = (int) raw.get("activeSessions");
            var rawAgentStats = (java.util.List<java.util.Map<String, Object>>) raw.get("agentStats");

            var agentStats = rawAgentStats.stream()
                    .map(m -> DashboardStatsDTO.AgentSessionStatDTO.builder()
                            .agentId((String) m.get("agentId"))
                            .agentName((String) m.get("agentName"))
                            .sessionCount((int) m.get("sessionCount"))
                            .build())
                    .toList();

            DashboardStatsDTO stats = DashboardStatsDTO.builder()
                    .totalAgents(totalAgents)
                    .activeSessions(activeSessions)
                    .totalTokens(0)
                    .totalCost(0)
                    .agentStats(agentStats)
                    .build();

            return Response.<DashboardStatsDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(stats)
                    .build();
        } catch (Exception e) {
            log.error("查询仪表盘统计失败", e);
            return Response.<DashboardStatsDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 从会话的 AgentState JSON 中提取首条用户消息作为标题。
     */
    private String extractTitle(SessionEntity entity) {
        if (entity.getStateJson() == null || entity.getStateJson().isEmpty()) {
            return "新对话";
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> state = objectMapper.readValue(entity.getStateJson(), java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> messages =
                    (java.util.List<java.util.Map<String, Object>>) state.get("messages");
            if (messages != null) {
                for (java.util.Map<String, Object> msg : messages) {
                    if ("user".equals(msg.get("role"))) {
                        Object content = msg.get("content");
                        if (content instanceof String text && !text.isBlank()) {
                            return text.length() > 30 ? text.substring(0, 30) + "…" : text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取会话标题失败: sessionId={}", entity.getSessionId());
        }
        return "新对话";
    }

    @Auditable(value = AuditAction.AGENT_CHAT, resource = "agent")
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        // P0-6: 注入 correlationId 到 MDC
        // 【流式】correlationId 入 MDC，串联 SSE 全链路便于跨进程追踪
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);

        // 超时对齐最长工具等待预算：baidu-search MCP requestTimeout=500s + 多轮调用，
        // 3min 会在 Agent 仍在执行时切断 SSE（前端"生成中"卡住 + 重发重复执行）。
        // 【流式】SSE 边缘承接：10 分钟超时对齐最长工具等待预算，避免 Agent 执行中被切断
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);
        try {
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getMessage());

            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            chatService.handleMessageStream(
                            requestDTO.getAgentId(), requestDTO.getUserId(),
                            sessionId, requestDTO.getMessage())
                    // 【流式】订阅统一 Flowable，将逐事件经 SSE data 帧下发前端
                    .subscribe(
                            event -> {
                                try {
                                    emitter.send(serializeEvent(event));
                                } catch (Exception e) {
                                    log.error("流式对话发送失败", e);
                                    emitter.completeWithError(e);
                                }
                            },
                            emitter::completeWithError,
                            emitter::complete
                    );
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ========== H4: 权限确认端点 ==========

    /**
     * H4-步骤5: 用户提交工具调用确认回执。
     *
     * <p>当 Agent 处于 PAUSED 状态等待用户确认时，前端通过此端点提交
     * 批准/拒绝结果，Agent 恢复执行。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "agentId": "my-agent",
     *   "userId": "user-123",
     *   "sessionId": "abc123",
     *   "confirmResults": [
     *     {"toolCallId": "call_001", "approved": true},
     *     {"toolCallId": "call_002", "approved": false}
     *   ]
     * }
     * }</pre>
     */
    @RequestMapping(value = "confirm", method = RequestMethod.POST)
    public ResponseBodyEmitter confirm(@RequestBody Map<String, Object> requestBody) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);
        try {
            String agentId = (String) requestBody.get("agentId");
            String userId = (String) requestBody.get("userId");
            String sessionId = (String) requestBody.get("sessionId");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawResults = (List<Map<String, Object>>) requestBody.get("confirmResults");

            if (agentId == null || sessionId == null || rawResults == null || rawResults.isEmpty()) {
                emitter.send("data: {\"type\":\"error\",\"errorMessage\":\"缺少必要参数: agentId, sessionId, confirmResults\"}\n\n");
                emitter.complete();
                return emitter;
            }

            // 将原始 Map 转换为 ConfirmResult 列表
            List<ConfirmResult> confirmResults = rawResults.stream()
                    .map(m -> {
                        String toolCallId = (String) m.get("toolCallId");
                        boolean approved = Boolean.TRUE.equals(m.get("approved"));
                        return approved
                                ? ConfirmResult.approve(toolCallId)
                                : ConfirmResult.deny(toolCallId);
                    })
                    .toList();

            log.info("收到确认回执: agentId={}, userId={}, sessionId={}, count={}",
                    agentId, userId, sessionId, confirmResults.size());

            chatServiceImpl.handleConfirm(agentId, userId, sessionId, confirmResults)
                    .subscribe(
                            event -> {
                                try {
                                    emitter.send(serializeEvent(event));
                                } catch (Exception e) {
                                    log.error("确认恢复流式发送失败", e);
                                    emitter.completeWithError(e);
                                }
                            },
                            emitter::completeWithError,
                            emitter::complete
                    );

        } catch (Exception e) {
            log.error("确认回执处理失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ========== SSE 序列化 ==========

    // 【流式】RuntimeEvent → SSE data 帧：按 type 序列化 textDelta/toolCall/permissionAsking 等
    private String serializeEvent(RuntimeEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.getType().name());
            switch (event.getType()) {
                case textDelta -> payload.put("text", event.getText() != null ? event.getText() : "");
                case toolCall -> {
                    payload.put("toolCallId", event.getToolCallId());
                    payload.put("toolName", event.getToolName());
                    payload.put("toolInput", event.getToolInput());
                }
                case toolResult -> {
                    payload.put("toolCallId", event.getToolCallId());
                    payload.put("toolName", event.getToolName());
                    payload.put("toolOutput", event.getToolOutput());
                    payload.put("toolError", event.isToolError());
                }
                case compactBoundary ->
                    payload.put("summary", event.getCompactSummary());
                case error ->
                    payload.put("errorMessage", event.getErrorMessage());
                case turnComplete ->
                    payload.put("turnCount", event.getTurnCount());
                case permissionAsking -> {
                    // H4: 权限挂起事件 → 前端展示确认 UI
                    payload.put("replyId", event.getConfirmReplyId());
                    payload.put("pendingToolCalls", event.getPendingToolCallsJson());
                }
                case agentPaused ->
                    payload.put("reason", event.getErrorMessage());
                case tokenBudget -> {
                    payload.put("budgetUsed", event.getBudgetUsed());
                    payload.put("budgetTotal", event.getBudgetTotal());
                    payload.put("budgetPercent", event.getBudgetPercent());
                }
                case checkpoint -> {
                    payload.put("sessionId", event.getCheckpointSessionId());
                    payload.put("turnNumber", event.getCheckpointTurnNumber());
                }
                case internalLlmCall -> {
                    payload.put("source", event.getInternalLlmSource());
                    payload.put("model", event.getInternalLlmModel());
                    payload.put("durationMs", event.getInternalLlmDurationMs());
                    payload.put("success", event.isInternalLlmSuccess());
                }
                case done -> {} // stream close is signaled by emitter.complete(), no payload needed
                default -> {}   // maxTurnsReached and any future types — no extra payload
            }
            return "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"error\",\"errorMessage\":\"serialize failed\"}\n\n";
        }
    }
}
