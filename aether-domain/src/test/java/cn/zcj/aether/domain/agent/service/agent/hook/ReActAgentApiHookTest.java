package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.curation.CurationPipeline;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3：验证 ReActAgent 在模型调用段触发 PRE_API_REQUEST / POST_API_REQUEST / API_REQUEST_ERROR 生命周期钩子，
 * 对齐 hermes pre_api_request / post_api_request / api_request_error。
 *
 * <p>驱动 agent.execute(ctx) 走真实 queryLoop：成功路径断言 PRE 与 POST 各触发；错误路径断言 ERROR 触发。</p>
 */
@SuppressWarnings("unchecked")
class ReActAgentApiHookTest {

    private ReActAgent buildAgent(HookRegistry registry,
            ModelInvoker.ModelCallResult callResult) {
        // ReActAgent 运行时的 chatModel 实际是 ChatModel 直接实例，不走 Mockito mock 的链式返回，
        // 但 queryLoop 通过 modelInvoker 调用 chatModel，因此对 chatModel 无额外 stub 需求。
        ChatModel chatModel = mock(ChatModel.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ContextManager contextManager = mock(ContextManager.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        CheckpointCollector checkpointCollector = mock(CheckpointCollector.class);
        TokenBudget tokenBudget = mock(TokenBudget.class);
        ModelPricingRegistry pricingRegistry = mock(ModelPricingRegistry.class);
        CurationPipeline curationPipeline = mock(CurationPipeline.class);
        ExternalNotes externalNotes = mock(ExternalNotes.class);

        AgentConfig config = mock(AgentConfig.class);
        when(config.getCancelToken()).thenReturn(new cn.zcj.aether.domain.agent.service.agent.core.CancelToken());
        when(config.getInstruction()).thenReturn("你是一个助手");
        when(config.getModelRef()).thenReturn("qwen");
        when(config.isCacheEnabled()).thenReturn(false);

        // 让 queryLoop 越过 context 管理阶段（压缩管道不触发）
        when(contextManager.applyToolResultBudget(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.microCompact(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(contextManager.autoCompactIfNeeded(anyList(), anyString(), anyString(), any(TokenBudget.class)))
                .thenReturn(cn.zcj.aether.domain.agent.service.context.AutoCompactResult.notNeeded());
        when(contextManager.runCompactionPipeline(anyList(), anyString(), anyString(), anyInt(), any(TokenBudget.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.List<cn.zcj.aether.domain.agent.service.runtime.TurnMessage> msgs =
                            (java.util.List<cn.zcj.aether.domain.agent.service.runtime.TurnMessage>) inv.getArgument(0);
                    return new cn.zcj.aether.domain.agent.service.context.compaction.CompactionPipeline
                            .CompactionResult(false, null, msgs, 0, 0);
                });

        ReActAgent agent = new ReActAgent(config, chatModel, modelInvoker, toolExecutor,
                contextManager, publisher, checkpointCollector, tokenBudget,
                pricingRegistry, curationPipeline, externalNotes);
        agent.setHookRegistry(registry);

        // 驱动 queryLoop 的模型调用返回值
        when(modelInvoker.callWithStreamCachedAsync(any(ChatModel.class), anyList(), anyString(),
                anyString(), anyBoolean(), anyInt()))
                .thenReturn(Mono.just(callResult));

        return agent;
    }

    private void drain(ReActAgent agent) {
        RuntimeContext ctx = new RuntimeContext("u1", "session-1", "corr-1", null, "hello", null, null);
        agent.execute(ctx)
                .blockingSubscribe();
    }

    @Test
    void successPathFiresPreAndPostApiRequest() {
        AtomicInteger pre = new AtomicInteger(0);
        AtomicInteger post = new AtomicInteger(0);
        AtomicInteger err = new AtomicInteger(0);
        HookRegistry registry = new HookRegistry();
        registry.registerLifecycle(new LifecycleHook() {
            @Override
            public Set<HookPoint> points() {
                return Set.of(HookPoint.PRE_API_REQUEST, HookPoint.POST_API_REQUEST, HookPoint.API_REQUEST_ERROR);
            }

            @Override
            public void onHook(HookPoint point, HookContext ctx) {
                if (point == HookPoint.PRE_API_REQUEST) pre.incrementAndGet();
                else if (point == HookPoint.POST_API_REQUEST) post.incrementAndGet();
                else err.incrementAndGet();
            }
        });

        ModelInvoker.ModelCallResult ok = ModelInvoker.ModelCallResult.builder()
                .fullText("done")
                .toolCalls(List.of())
                .build();
        drain(buildAgent(registry, ok));

        assertEquals(1, pre.get(), "PRE_API_REQUEST 应触发 1 次");
        assertEquals(1, post.get(), "POST_API_REQUEST 应触发 1 次");
        assertEquals(0, err.get(), "成功路径不应触发 API_REQUEST_ERROR");
    }

    @Test
    void errorPathFiresApiRequestError() {
        AtomicInteger pre = new AtomicInteger(0);
        AtomicInteger post = new AtomicInteger(0);
        AtomicInteger err = new AtomicInteger(0);
        HookRegistry registry = new HookRegistry();
        registry.registerLifecycle(new LifecycleHook() {
            @Override
            public Set<HookPoint> points() {
                return Set.of(HookPoint.PRE_API_REQUEST, HookPoint.POST_API_REQUEST, HookPoint.API_REQUEST_ERROR);
            }

            @Override
            public void onHook(HookPoint point, HookContext ctx) {
                if (point == HookPoint.PRE_API_REQUEST) pre.incrementAndGet();
                else if (point == HookPoint.POST_API_REQUEST) post.incrementAndGet();
                else err.incrementAndGet();
            }
        });

        ModelInvoker.ModelCallResult failed = ModelInvoker.ModelCallResult.error("boom");
        drain(buildAgent(registry, failed));

        assertEquals(1, pre.get(), "PRE_API_REQUEST 应触发 1 次");
        assertEquals(0, post.get(), "错误路径不应触发 POST_API_REQUEST");
        assertEquals(1, err.get(), "API_REQUEST_ERROR 应触发 1 次");
    }

    @Test
    void setterInjectsRegistryWithoutThrowing() {
        AtomicInteger pre = new AtomicInteger(0);
        HookRegistry registry = new HookRegistry();
        registry.registerLifecycle(new LifecycleHook() {
            @Override
            public Set<HookPoint> points() { return Set.of(HookPoint.PRE_API_REQUEST); }
            @Override
            public void onHook(HookPoint point, HookContext ctx) { pre.incrementAndGet(); }
        });

        drain(buildAgent(registry, ModelInvoker.ModelCallResult.error("boom")));

        assertEquals(1, pre.get(), "setter 注入后 PRE_API_REQUEST 应可达并触发");
    }
}
