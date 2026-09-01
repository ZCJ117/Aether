package cn.zcj.aether.domain.agent.service.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * O7 增强: 摘要 ChatModel 显式选取器测试。
 */
class SummaryChatModelResolverTest {

    private SummaryChatModelResolver resolver(ListableBeanFactory bf, String summaryModel)
            throws Exception {
        SummaryChatModelResolver r = new SummaryChatModelResolver(bf);
        var f = SummaryChatModelResolver.class.getDeclaredField("summaryModel");
        f.setAccessible(true);
        f.set(r, summaryModel);
        return r;
    }

    @Test
    void fallsBackToDefaultChatModelBean() throws Exception {
        ListableBeanFactory bf = mock(ListableBeanFactory.class);
        when(bf.containsBean("chatModel")).thenReturn(true);
        ChatModel defaultModel = mock(ChatModel.class);
        when(bf.getBean("chatModel", ChatModel.class)).thenReturn(defaultModel);

        assertSame(defaultModel, resolver(bf, "").resolve());
        assertEquals("chatModel", resolver(bf, "").resolvedBeanName());
    }

    @Test
    void explicitSummaryModelPrefersChatModelNameBean() throws Exception {
        ListableBeanFactory bf = mock(ListableBeanFactory.class);
        when(bf.containsBean("chatModel-qwen")).thenReturn(true);
        ChatModel qwen = mock(ChatModel.class);
        when(bf.getBean("chatModel-qwen", ChatModel.class)).thenReturn(qwen);
        // 默认 bean 也存在 → 显式配置优先
        when(bf.containsBean("chatModel")).thenReturn(true);

        assertSame(qwen, resolver(bf, "qwen").resolve());
        assertEquals("chatModel-qwen", resolver(bf, "qwen").resolvedBeanName());
    }

    @Test
    void explicitFullBeanNameIsAcceptedVerbatim() throws Exception {
        ListableBeanFactory bf = mock(ListableBeanFactory.class);
        when(bf.containsBean("chatModel")).thenReturn(true);
        ChatModel m = mock(ChatModel.class);
        when(bf.getBean("chatModel", ChatModel.class)).thenReturn(m);

        assertSame(m, resolver(bf, "chatModel").resolve());
    }

    @Test
    void explicitModelMissFallsBackToDefault() throws Exception {
        ListableBeanFactory bf = mock(ListableBeanFactory.class);
        when(bf.containsBean("chatModel-nonexistent")).thenReturn(true).thenReturn(false);
        when(bf.containsBean("chatModel-nonexistent")).thenReturn(false);
        when(bf.containsBean("chatModel")).thenReturn(true);
        ChatModel fallback = mock(ChatModel.class);
        when(bf.getBean("chatModel", ChatModel.class)).thenReturn(fallback);

        assertSame(fallback, resolver(bf, "nonexistent").resolve());
    }

    @Test
    void noChatModelBeanFailsFast() throws Exception {
        ListableBeanFactory bf = mock(ListableBeanFactory.class);
        when(bf.containsBean(anyString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> resolver(bf, "").resolve());
    }
}
