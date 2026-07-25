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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话 ID */
    private String sessionId;

    /** Agent ID */
    private String agentId;

    /** 当前 turn 编号 */
    private int turnNumber;

    /** 检查点创建时间 */
    private Instant timestamp;

    /** Agent 状态（saveState() 的 JSON 序列化结果） */
    private Map<String, Object> agentState;

    /** 消息数量 */
    private int messageCount;

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
