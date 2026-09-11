package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * P0-#8: Agent 检查点数据模型。
 * 借鉴 CrewAI 的多粒度检查点 + cc-haha 的 WAL 日志模式。
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：检查点是 saveState() 快照的不可变容器——agentState 持有整份 Agent 状态 JSON（L41-42），
 * 以 VERSION=1（L48）标注格式版本；create/toJson/fromJson（L53-85）提供快照序列化闭环，
 * 与 CrewAI from_checkpoint 对齐，使会话可从最近检查点无损恢复。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointData {

    // Instant 需 JavaTimeModule，否则 toJson() 每次序列化都失败（检查点持久化不可用）
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 会话 ID */
    private String sessionId;

    /** Agent ID */
    private String agentId;

    /** 当前 turn 编号 */
    private int turnNumber;

    /** 检查点创建时间 */
    private Instant timestamp;

    // 【持久化】检查点快照内容：即 saveState() 的 JSON 序列化结果，恢复时原样回灌 loadState()
    /** Agent 状态（saveState() 的 JSON 序列化结果） */
    private Map<String, Object> agentState;

    /** 消息数量 */
    private int messageCount;

    // 【持久化】检查点格式版本：变更时须配套迁移逻辑，避免旧快照反序列化错乱
    /** 检查点格式版本 */
    private static final int VERSION = 1;

    /**
     * 从 Agent 状态创建检查点
     */
    public static CheckpointData create(String sessionId, String agentId,
            int turnNumber, Map<String, Object> agentState, int messageCount) {
        return CheckpointData.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .turnNumber(turnNumber)
                .timestamp(Instant.now())
                .agentState(agentState)
                .messageCount(messageCount)
                .build();
    }

    /**
     * 序列化为 JSON 字符串
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("检查点序列化失败", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化
     */
    public static CheckpointData fromJson(String json) {
        try {
            return MAPPER.readValue(json, CheckpointData.class);
        } catch (Exception e) {
            throw new RuntimeException("检查点反序列化失败", e);
        }
    }
}
