package cn.zcj.aether.domain.agent.service.runtime;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Serializable LLM cache representation.
 *
 * <p>The runtime value types are domain classes without Jackson constructors.
 * Keeping this boundary separate allows Redis L2 storage without changing the
 * in-memory model API.</p>
 */
@Data
@Builder
public class ModelCacheSnapshot {
    private String fullText;
    private String error;
    private int inputTokens;
    private int outputTokens;
    private int cacheTokens;
    private double costUsd;
    private List<EventSnapshot> events;
    private List<ToolCallSnapshot> toolCalls;

    public static ModelCacheSnapshot from(ModelInvoker.ModelCallResult result) {
        return ModelCacheSnapshot.builder()
                .fullText(result.getFullText())
                .error(result.getError())
                .inputTokens(result.getInputTokens())
                .outputTokens(result.getOutputTokens())
                .cacheTokens(result.getCacheTokens())
                .costUsd(result.getCostUsd())
                .events(result.getEvents() == null ? List.of() : result.getEvents().stream()
                        .map(EventSnapshot::from)
                        .toList())
                .toolCalls(result.getToolCalls() == null ? List.of() : result.getToolCalls().stream()
                        .map(ToolCallSnapshot::from)
                        .toList())
                .build();
    }

    public ModelInvoker.ModelCallResult toResult() {
        return ModelInvoker.ModelCallResult.builder()
                .events(events == null ? List.of() : events.stream().map(EventSnapshot::toEvent).toList())
                .fullText(fullText)
                .toolCalls(toolCalls == null ? List.of() : toolCalls.stream().map(ToolCallSnapshot::toToolCall).toList())
                .error(error)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheTokens(cacheTokens)
                .costUsd(costUsd)
                .build();
    }

    @Data
    @Builder
    public static class EventSnapshot {
        private String type;
        private String text;
        private String toolCallId;
        private String toolName;
        private String toolInput;
        private String toolOutput;
        private boolean toolError;
        private String errorMessage;

        static EventSnapshot from(RuntimeEvent event) {
            return EventSnapshot.builder()
                    .type(event.getType() == null ? null : event.getType().name())
                    .text(event.getText())
                    .toolCallId(event.getToolCallId())
                    .toolName(event.getToolName())
                    .toolInput(event.getToolInput())
                    .toolOutput(event.getToolOutput())
                    .toolError(event.isToolError())
                    .errorMessage(event.getErrorMessage())
                    .build();
        }

        RuntimeEvent toEvent() {
            RuntimeEvent.RuntimeEventBuilder builder = RuntimeEvent.builder();
            if (type != null) {
                try {
                    builder.type(RuntimeEvent.EventType.valueOf(type));
                } catch (IllegalArgumentException ignored) {
                    return RuntimeEvent.error(errorMessage == null ? "invalid cached event type" : errorMessage);
                }
            }
            return builder.text(text)
                    .toolCallId(toolCallId)
                    .toolName(toolName)
                    .toolInput(toolInput)
                    .toolOutput(toolOutput)
                    .toolError(toolError)
                    .errorMessage(errorMessage)
                    .build();
        }
    }

    @Data
    @Builder
    public static class ToolCallSnapshot {
        private String id;
        private String name;
        private java.util.Map<String, Object> input;

        static ToolCallSnapshot from(ModelInvoker.ToolCallDef toolCall) {
            return ToolCallSnapshot.builder()
                    .id(toolCall.getId())
                    .name(toolCall.getName())
                    .input(toolCall.getInput())
                    .build();
        }

        ModelInvoker.ToolCallDef toToolCall() {
            return ModelInvoker.ToolCallDef.builder()
                    .id(id)
                    .name(name)
                    .input(input == null ? java.util.Map.of() : input)
                    .build();
        }
    }
}
