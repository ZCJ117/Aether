package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.context.AutoCompactResult;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier;
import cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VUL-04 / T4-1: 生产装配点必须真的注入 CompressCallback。
 *
 * <p>回归目标：修复前 {@code setCompressCallback} 全仓唯一调用点在测试里，
 * 生产环境 CONTEXT_COMPRESSION 恢复分支永远空转。本用例锁死
 * {@code ReActAgent.wireResilientExecutor} 这条装配链。</p>
 */
class ReActAgentCompressWiringTest {

    @Test
    void executeShouldInjectCompressCallback() throws Exception {
        ResilientChatModelExecutor executor = new ResilientChatModelExecutor(
                mock(ChatModel.class),
                ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build(),
                mock(ModelProvider.class), mock(ModelProviderRegistry.class),
                mock(ModelErrorClassifier.class), List.of());

        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.isTrueStreaming()).thenReturn(false);
        when(modelInvoker.callWithStreamCachedAsync(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(finalText()));

        ContextManager contextManager = mock(ContextManager.class);
        when(contextManager.applyToolResultBudget(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.microCompact(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.autoCompactIfNeeded(any(), any(), any(), any()))
                .thenReturn(AutoCompactResult.notNeeded());

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder().name("wiring-agent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                executor, modelInvoker, mock(ToolExecutor.class), contextManager,
                null, null, null, null, null, null);

        List<RuntimeEvent> events = agent
                .execute(new RuntimeContext("user1", "session1", null, null, "你好", null, null))
                .toList().blockingGet();

        assertTrue(events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.done),
                "execute 应正常跑完，装配不应阻断主循环");

        Field callbackField = ResilientChatModelExecutor.class.getDeclaredField("compressCallback");
        callbackField.setAccessible(true);
        assertNotNull(callbackField.get(executor),
                "生产装配点未注入 CompressCallback —— CONTEXT_COMPRESSION 分支将空转");

        // 只注入回调还不够：压缩改的是 AgentState，而重试复用的是入参 Prompt。
        // 不注入重建器，重试发出的仍是压缩前那份超长请求，必然再次溢出。
        Field rebuilderField = ResilientChatModelExecutor.class.getDeclaredField("promptRebuilder");
        rebuilderField.setAccessible(true);
        assertNotNull(rebuilderField.get(executor),
                "生产装配点未注入 PromptRebuilder —— 压缩后的重试请求不会缩短");
    }

    private static ModelInvoker.ModelCallResult finalText() {
        return ModelInvoker.ModelCallResult.builder()
                .events(List.of())
                .fullText("完成")
                .toolCalls(List.of())
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }
}
