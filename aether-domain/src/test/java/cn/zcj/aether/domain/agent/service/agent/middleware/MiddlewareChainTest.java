package cn.zcj.aether.domain.agent.service.agent.middleware;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MiddlewareChain.applyActing 异常处理策略测试。
 *
 * <p>核心回归：权限校验类中间件抛异常时必须 fail-closed——中断链路并返回空列表
 * （拒绝本批全部工具调用），而不是吞掉异常后把请求原样放行（fail-open）。
 */
class MiddlewareChainTest {

    private final Agent agent = mock(Agent.class);
    private final RuntimeContext ctx =
            new RuntimeContext("user1", "session1", null, null, "开始任务", null, null);

    private static List<ToolExecutor.ToolCallRequest> requests(String... toolNames) {
        List<ToolExecutor.ToolCallRequest> reqs = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            reqs.add(new ToolExecutor.ToolCallRequest("call-" + i, toolNames[i], java.util.Map.of()));
        }
        return reqs;
    }

    @Test
    void noMiddlewarePassesRequestsThrough() {
        MiddlewareChain chain = new MiddlewareChain(agent, ctx);
        List<ToolExecutor.ToolCallRequest> reqs = requests("search");

        assertSame(reqs, chain.applyActing(reqs));
    }

    @Test
    void exceptionDeniesAllAndShortCircuitsRemainingMiddlewares() {
        AgentMiddleware throwing = new AgentMiddleware() {
            @Override public String name() { return "permission"; }
            @Override public int priority() { return 10; }
            @Override
            public List<ToolExecutor.ToolCallRequest> onActing(
                    List<ToolExecutor.ToolCallRequest> requests, Agent agent, RuntimeContext ctx) {
                throw new IllegalStateException("permission engine unavailable");
            }
        };
        // 后续中间件不应被触及（短路）
        AgentMiddleware downstream = mock(AgentMiddleware.class);

        MiddlewareChain chain = new MiddlewareChain(agent, ctx);
        chain.use(throwing);
        chain.use(downstream);

        List<ToolExecutor.ToolCallRequest> denied = chain.applyActing(requests("search", "write"));

        assertTrue(denied.isEmpty(), "权限链异常时应拒绝全部工具调用（fail-closed）");
        verify(downstream, never()).onActing(anyList(), any(), any());
    }

    @Test
    void middlewareChainTransformsInPriorityOrder() {
        AgentMiddleware first = new AgentMiddleware() {
            @Override public String name() { return "first"; }
            @Override public int priority() { return 10; }
            @Override
            public List<ToolExecutor.ToolCallRequest> onActing(
                    List<ToolExecutor.ToolCallRequest> requests, Agent agent, RuntimeContext ctx) {
                return requests.stream()
                        .map(r -> new ToolExecutor.ToolCallRequest(r.toolCallId(), r.toolName() + "-1", r.input()))
                        .toList();
            }
        };
        AgentMiddleware second = new AgentMiddleware() {
            @Override public String name() { return "second"; }
            @Override public int priority() { return 20; }
            @Override
            public List<ToolExecutor.ToolCallRequest> onActing(
                    List<ToolExecutor.ToolCallRequest> requests, Agent agent, RuntimeContext ctx) {
                return requests.stream()
                        .map(r -> new ToolExecutor.ToolCallRequest(r.toolCallId(), r.toolName() + "-2", r.input()))
                        .toList();
            }
        };

        MiddlewareChain chain = new MiddlewareChain(agent, ctx);
        chain.use(second);
        chain.use(first);

        List<ToolExecutor.ToolCallRequest> result = chain.applyActing(requests("search"));
        assertEquals(1, result.size());
        assertEquals("search-1-2", result.get(0).toolName());
    }
}
