package cn.zcj.aether.trigger.http;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3/VUL-05: {@code delegation} 事件必须带载荷序列化到 SSE 帧。
 *
 * <p><b>为什么需要本用例</b>：领域层确实发出了完成事件
 * （{@code ReActAgent:259} 调 {@code RuntimeEvent.delegation(delegationId, 0, status)}），
 * 但 {@code serializeEvent} 的 switch 里没有 {@code delegation} 分支时，事件会落进
 * {@code default -> {}}，前端只收到 {@code {"type":"delegation"}}——事件"发了"却"没有内容"，
 * 委派状态在前端无从刷新。这是"链路存在但载荷丢失"的典型缺口，只有断言帧内容才抓得到。</p>
 *
 * <p>不需要 Spring 上下文：{@code serializeEvent} 是纯函数，{@code objectMapper} 为静态常量，
 * 直接 new 出控制器反射调用即可。</p>
 */
class AgentServiceControllerSseTest {

    @Test
    @DisplayName("D3 delegation 帧携带 delegationId / status / toolCount")
    void delegationFrameCarriesPayload() throws Exception {
        String frame = serialize(RuntimeEvent.delegation("ad-123", 3, "COMPLETED"));

        assertTrue(frame.contains("\"delegationId\":\"ad-123\""),
                "缺 delegationId，前端无法定位是哪次委派完成；实际帧: " + frame);
        assertTrue(frame.contains("\"status\":\"COMPLETED\""),
                "缺 status，前端无法区分完成/失败；实际帧: " + frame);
        assertTrue(frame.contains("\"toolCount\":3"),
                "缺 toolCount；实际帧: " + frame);
    }

    @Test
    @DisplayName("D3 delegation 帧仍是合法 SSE data 帧且 type 正确")
    void delegationFrameKeepsSseShape() throws Exception {
        String frame = serialize(RuntimeEvent.delegation("ad-9", 0, "FAILED"));

        assertTrue(frame.startsWith("data: ") && frame.endsWith("\n\n"),
                "必须保持 SSE data 帧形状；实际帧: " + frame);
        assertTrue(frame.contains("\"type\":\"delegation\""),
                "type 字段须为 delegation；实际帧: " + frame);
        assertEquals(1, frame.lines().count() - 1,
                "载荷须压成单行 JSON（多行会破坏 SSE 帧边界）；实际帧: " + frame);
    }

    private static String serialize(RuntimeEvent event) throws Exception {
        Method method = AgentServiceController.class
                .getDeclaredMethod("serializeEvent", RuntimeEvent.class);
        method.setAccessible(true);
        return (String) method.invoke(new AgentServiceController(), event);
    }
}
