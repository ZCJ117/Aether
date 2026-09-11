package cn.zcj.aether.domain.agent.service.session;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import cn.zcj.aether.types.exception.StateRestoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话服务 — O2 从 ChatService 拆出。
 *
 * <p>单一职责：会话的创建/列表/删除/恢复（SessionRepository 状态恢复 + 检查点恢复）与历史消息提取。
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：恢复路径坚持"带病不恢复"——restoreSession（L164-198）将字段缺失类
 * StateRestoreException 响亮抛出（L190-193），仅对 JSON 解析失败等非结构错误兼容回退新会话；
 * resumeFromCheckpoint（L207-220）从最新检查点快照恢复 agentState，无检查点则显式抛错，
 * 与 CrewAI from_checkpoint 风格对齐，保证恢复的可追溯与可失败。</p>
 */
@Slf4j
@Service
public class SessionService {

    @Resource
    private AgentRegistry agentRegistry;

    @Resource
    private HookRegistry hookRegistry;

    /**
     * 会话持久化仓储（用于会话恢复）。required=false：未配置数据源时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SessionRepository sessionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryLifecycleHooks memoryLifecycleHooks;

    /**
     * P0-#8：检查点收集器（用于从文件检查点恢复 Agent 状态）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CheckpointCollector checkpointCollector;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** P0-3 启动检测的持久化意图（与 PgSessionRepository 激活条件同源：store 缺省/postgres 且 persistence 缺省/true）。 */
    @org.springframework.beans.factory.annotation.Value("${aether.session.store:postgres}")
    private String sessionStore = "postgres";

    @org.springframework.beans.factory.annotation.Value("${aether.session.persistence:true}")
    private String sessionPersistence = "true";

    /**
     * P0-3 启动检测：预期启用持久化（store 缺省/postgres 且 persistence 缺省/true）却无仓储 Bean 时
     * 显式 WARN——不再无声降级；显式关闭（store=none / persistence=false）时静默。
     */
    @jakarta.annotation.PostConstruct
    void warnIfSessionPersistenceMissing() {
        boolean persistenceIntended =
                "postgres".equals(sessionStore) && "true".equals(sessionPersistence);
        if (persistenceIntended && sessionRepository == null) {
            log.warn("会话持久化未激活（预期启用但无 SessionRepository Bean）：会话重启后不可恢复。"
                    + "请检查 PostgreSQL 数据源配置（需 aether.session.persistence=true 且 classpath 含 pg 驱动）；"
                    + "如确不需持久化，请显式设置 aether.session.store=none。");
        }
    }

    /**
     * 创建会话并立即持久化空会话（使其出现在列表 API 中），触发 ON_SESSION_START 钩子。
     */
    public String createSession(String agentId, String userId) {
        AgentGraph graph = agentRegistry.get(agentId);
        if (graph == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);

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
                        // O6: 持久化失败升级为 ERROR（含 sessionId），不再静默降级
                        log.error("会话持久化失败（会话列表可能缺失该记录）: sessionId={}, agentId={}, userId={}",
                                sessionId, agentId, userId, ex);
                        return null;
                    });
        }

        // D3: ON_SESSION_START（对齐 hermes on_session_start）
        hookRegistry.invokeAll(HookPoint.ON_SESSION_START, HookContext.builder()
                .agentId(agentId).sessionId(sessionId).build());

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
     * 软删除会话（状态改为 ARCHIVED），删除前触发记忆 flush 与 ON_SESSION_END 钩子。
     */
    public void deleteSession(String sessionId) {
        if (sessionRepository == null) {
            log.warn("SessionRepository 未配置，无法删除会话: sessionId={}", sessionId);
            return;
        }

        // 记忆生命周期：会话结束 flush（轮次 ≥ flush-min-turns 才真正触发）
        int turnCount = readSessionTurnCount(sessionId);
        if (memoryLifecycleHooks != null) {
            memoryLifecycleHooks.onSessionEnd(sessionId, turnCount);
        }

        // D3: ON_SESSION_END（对齐 hermes on_session_end）
        hookRegistry.invokeAll(HookPoint.ON_SESSION_END, HookContext.builder()
                .sessionId(sessionId).build());

        sessionRepository.deleteBySessionId(sessionId);
        log.info("会话已删除: sessionId={}", sessionId);
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
    // 【持久化】强校验恢复：StateRestoreException（字段缺失）响亮传播，拒绝带病恢复；仅 JSON 解析失败兼容回退
    public void restoreSession(Agent agent, String sessionId) {
        if (sessionRepository == null || sessionId == null) return;
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
     * P0-#8: 从最新检查点恢复会话。
     * 如果存在检查点，加载 agentState → 返回给调用方用于 loadState()。
     * 无检查点则抛出 AppException。
     *
     * 借鉴 CrewAI 的 from_checkpoint（快照恢复）+ MetaGPT 的 recovered 标志。
     */
    // 【持久化】检查点恢复：从最新检查点快照（CrewAI from_checkpoint 风格）载入 agentState，无检查点显式抛错
    public Map<String, Object> resumeFromCheckpoint(String agentId, String sessionId) {
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
    public List<CheckpointData> listCheckpoints(String sessionId) {
        if (checkpointCollector == null) return List.of();
        return checkpointCollector.listCheckpoints(sessionId);
    }

    /**
     * 从会话的 stateJson 中提取消息列表，供前端恢复历史对话。
     */
    public List<Map<String, String>> getSessionMessages(String sessionId) {
        if (sessionRepository == null) return List.of();
        var opt = sessionRepository.findBySessionId(sessionId);
        if (opt.isEmpty() || opt.get().getStateJson() == null) return List.of();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = objectMapper.readValue(opt.get().getStateJson(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages =
                    (List<Map<String, Object>>) state.get("messages");
            if (messages == null) return List.of();

            return messages.stream()
                    .filter(m -> "user".equals(m.get("role")) || "assistant".equals(m.get("role")))
                    .map(m -> Map.of(
                            "role", String.valueOf(m.getOrDefault("role", "")),
                            "content", String.valueOf(m.getOrDefault("content", ""))))
                    .toList();
        } catch (Exception e) {
            log.warn("提取会话消息失败: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 从会话 stateJson 读取累计轮次（解析失败视为 0）。 */
    private int readSessionTurnCount(String sessionId) {
        try {
            var opt = sessionRepository.findBySessionId(sessionId);
            if (opt.isEmpty() || opt.get().getStateJson() == null) return 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> state = objectMapper.readValue(opt.get().getStateJson(), Map.class);
            return ((Number) state.getOrDefault("currentTurn", 0)).intValue();
        } catch (Exception e) {
            log.warn("读取会话轮次失败: sessionId={}, error={}", sessionId, e.getMessage());
            return 0;
        }
    }
}
