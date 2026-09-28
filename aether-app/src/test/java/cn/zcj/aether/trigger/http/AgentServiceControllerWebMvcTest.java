package cn.zcj.aether.trigger.http;

import cn.zcj.aether.domain.agent.service.chat.ChatService;
import cn.zcj.aether.domain.agent.service.chat.DashboardService;
import cn.zcj.aether.domain.agent.service.model.ModelCatalogService;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.session.SessionService;
import cn.zcj.aether.trigger.http.filter.JwtAuthFilter;
import cn.zcj.aether.trigger.http.filter.MdcFilter;
import cn.zcj.aether.trigger.http.filter.RateLimitFilter;
import cn.zcj.aether.trigger.http.filter.SecurityHeadersFilter;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentServiceController 的 @WebMvcTest 切片测试 — P0(2.1-步骤3-③)。
 *
 * <p>补 SSE 端点回归防线：{@code chat_stream}（textDelta/done 帧序列化 + sessionId 自动建会话）
 * 与 {@code confirm}（参数缺失校验）。过滤器（JWT/限流/MDC/安全头）不在本切片关注点内，
 * 显式排除并关闭 MockMvc 过滤链。</p>
 */
@WebMvcTest(controllers = AgentServiceController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = {
        JwtAuthFilter.class, RateLimitFilter.class, SecurityHeadersFilter.class, MdcFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class AgentServiceControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    // ── 控制器依赖 ──
    // 注意：控制器的 IChatService 与 ChatService 两个 @Resource 字段在生产中指向同一个
    // ChatService Bean（chatService 字段按类型兜底注入），故这里只注册一个 mock。
    @MockitoBean
    private ChatService chatServiceImpl;
    @MockitoBean
    private SessionService sessionService;
    @MockitoBean
    private ModelCatalogService modelCatalogService;
    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void chatStreamEmitsTextDeltaAndDoneFrames() throws Exception {
        when(chatServiceImpl.handleMessageStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Flowable.just(RuntimeEvent.text("hello"), RuntimeEvent.done()));

        MvcResult result = mockMvc.perform(post("/api/v1/chat_stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"1\",\"userId\":\"u1\",\"sessionId\":\"s1\",\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"textDelta\"")))
                .andExpect(content().string(containsString("\"type\":\"done\"")));
    }

    @Test
    void chatStreamCreatesSessionWhenSessionIdMissing() throws Exception {
        when(chatServiceImpl.createSession(anyString(), anyString())).thenReturn("s-auto");
        when(chatServiceImpl.handleMessageStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Flowable.just(RuntimeEvent.done()));

        MvcResult result = mockMvc.perform(post("/api/v1/chat_stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"1\",\"userId\":\"u1\",\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"done\"")));

        verify(chatServiceImpl).createSession("1", "u1");
        verify(chatServiceImpl).handleMessageStream("1", "u1", "s-auto", "hi", null);
    }

    @Test
    void confirmWithoutRequiredParamsEmitsErrorFrame() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"error\"")));
    }
}
