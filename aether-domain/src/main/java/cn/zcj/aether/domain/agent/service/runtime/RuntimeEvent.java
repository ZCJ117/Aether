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

    public enum EventType {
        textDelta,
        toolCall,
        toolResult,
        compactBoundary,
        turnComplete,
        done,
        maxTurnsReached,
        error
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
}
