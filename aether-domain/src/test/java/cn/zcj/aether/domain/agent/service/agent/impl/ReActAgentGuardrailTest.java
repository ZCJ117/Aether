package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * O10: ReActAgent per-tool 失败护栏行为单元测试。
 *
 * <p>验证：同一工具连续失败 3 次后，后续对它的调用被护栏熔断跳过
 * （不再触达 {@link ToolExecutor}，结果以 {@code ErrorType.GUARDRAIL} 回注保持 tool_call 配对），
 * 且 Agent 主循环继续运行直至正常完成。
 */
class ReActAgentGuardrailTest {

    @Test
    void guardrailShouldTripAfterThreeConsecutiveFailuresOfSameTool() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.isTrueStreaming()).thenReturn(false);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ContextManager contextManager = mock(ContextManager.class);

        when(contextManager.applyToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.microCompact(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        // 轮次脚本：t1 broken；t2 ok+broken（成功重置"连续 3 轮全失败"退出计数）；
        // t3 broken（failCount 达 3）；t4 broken（应被护栏拦截，不触达 ToolExecutor）；t5 纯文本结束
        ModelInvoker.ModelCallResult brokenOnly = toolCallResult(
                toolCall("call-1", "broken_tool"));
        ModelInvoker.ModelCallResult mixed = toolCallResult(
                toolCall("call-2", "ok_tool"),
                toolCall("call-3", "broken_tool"));
        ModelInvoker.ModelCallResult finalText = ModelInvoker.ModelCallResult.builder()
                .events(List.of())
                .fullText("改用其他方式完成任务")
                .toolCalls(List.of())
                .inputTokens(10)
                .outputTokens(5)
                .build();
        when(modelInvoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(brokenOnly), Mono.just(mixed), Mono.just(brokenOnly),
                        Mono.just(brokenOnly), Mono.just(finalText));

        when(toolExecutor.executeBatch(anyList(), anyString(), anyString())).thenAnswer(inv -> {
            List<ToolExecutor.ToolCallRequest> reqs = inv.getArgument(0);
            return reqs.stream()
                    .map(r -> r.toolName().equals("ok_tool")
                            ? ToolResult.success(r.toolCallId(), r.toolName(), "ok")
                            : ToolResult.error(r.toolCallId(), r.toolName(), "boom"))
                    .toList();
        });

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder().name("guard-agent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                chatModel, modelInvoker, toolExecutor, contextManager,
                null, null, null, null, null, null);

        RuntimeContext ctx = new RuntimeContext("user1", "session1", null, null, "开始任务", null, null);
        List<RuntimeEvent> events = agent.execute(ctx).toList().blockingGet();

        // 真实执行仅发生在 failCount < 3 的前 3 轮；第 4 次 broken_tool 调用被护栏熔断
        verify(toolExecutor, times(3)).executeBatch(anyList(), anyString(), anyString());

        boolean guardrailEvent = events.stream()
                .filter(e -> e.getType() == RuntimeEvent.EventType.toolResult)
                .anyMatch(e -> "broken_tool".equals(e.getToolName())
                        && e.getToolOutput() != null
                        && e.getToolOutput().contains("[系统护栏]"));
        assertTrue(guardrailEvent, "连续失败超限后应产生 GUARDRAIL 拦截事件（含放弃建议提示）");

        assertTrue(events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.done),
                "护栏拦截后主循环应继续运行至正常完成而非报错退出");
    }

    private static ModelInvoker.ToolCallDef toolCall(String id, String name) {
        return ModelInvoker.ToolCallDef.builder().id(id).name(name).input(Map.of()).build();
    }

    private static ModelInvoker.ModelCallResult toolCallResult(ModelInvoker.ToolCallDef... calls) {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of())
                .fullText("调用工具")
                .toolCalls(List.of(calls))
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }
}
