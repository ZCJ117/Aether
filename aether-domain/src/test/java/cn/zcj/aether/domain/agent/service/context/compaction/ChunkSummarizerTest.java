package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.ModelPricing;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.SummaryChatModelResolver;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkSummarizerTest {

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void summarizeRoutesThroughModelInvokerAndAccumulatesCost() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), eq("m")))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("sum").inputTokens(10).outputTokens(5)
                        .events(List.of()).toolCalls(List.of()).build());
        ModelPricingRegistry pricing = mock(ModelPricingRegistry.class);
        when(pricing.lookup("m")).thenReturn(new ModelPricing("m", 0.001, 0.002));

        ChunkSummarizer summarizer = new ChunkSummarizer();
        SummaryChatModelResolver resolver = mock(SummaryChatModelResolver.class);
        when(resolver.resolve()).thenReturn(mock(ChatModel.class));
        injectField(summarizer, "summaryChatModelResolver", resolver);
        injectField(summarizer, "modelInvoker", invoker);
        injectField(summarizer, "pricingRegistry", pricing);

        TokenBudget budget = mock(TokenBudget.class);
        String out = summarizer.summarize(
                List.of(TurnMessage.user("hello"), TurnMessage.assistant("world")), "m", budget);

        assertEquals("sum", out);
        verify(invoker).callWithStream(any(), anyList(), anyString(), eq("m"));
        verify(budget).accumulateCost(eq(10), eq(5), any(ModelPricing.class));
    }
}
