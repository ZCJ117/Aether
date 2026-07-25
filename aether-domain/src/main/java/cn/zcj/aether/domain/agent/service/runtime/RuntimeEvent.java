package cn.zcj.aether.domain.agent.service.runtime;

import lombok.Builder;
import lombok.Getter;

/**
 * 运行时事件类型
 */
@Getter
@Builder
public class RuntimeEvent {

    private EventType type;
    private String text;                  // textDelta
    private String toolCallId;            // toolCall / toolResult
    private String toolName;              // toolCall / toolResult
    private String toolInput;             // toolCall
    private String toolOutput;            // toolResult
    private boolean toolError;            // toolResult
    private String compactSummary;        // compactBoundary
    private int turnCount;                // turnComplete
    private String errorMessage;          // error
    private String internalLlmSource;     // C2: "context-compaction" | "memory-encoding"
    private String internalLlmModel;      // C2: 使用的模型名
    private long internalLlmDurationMs;   // C2: 耗时（毫秒）
    private boolean internalLlmSuccess;   // C2: 是否成功

    public enum EventType {
        textDelta,
        toolCall,
        toolResult,
        compactBoundary,
        turnComplete,
        done,
        maxTurnsReached,
        error,
        internalLlmCall   // C2: 非主循环 LLM 调用
    }

    public static RuntimeEvent text(String delta) {
        return RuntimeEvent.builder().type(EventType.textDelta).text(delta).build();
    }

    public static RuntimeEvent done() {
        return RuntimeEvent.builder().type(EventType.done).build();
    }

    public static RuntimeEvent error(String msg) {
        return RuntimeEvent.builder().type(EventType.error).errorMessage(msg).build();
    }

    public static RuntimeEvent internalLlmCall(String source, String model,
                                                long durationMs, boolean success) {
        return RuntimeEvent.builder()
                .type(EventType.internalLlmCall)
                .internalLlmSource(source)
                .internalLlmModel(model)
                .internalLlmDurationMs(durationMs)
                .internalLlmSuccess(success)
                .build();
    }
}
