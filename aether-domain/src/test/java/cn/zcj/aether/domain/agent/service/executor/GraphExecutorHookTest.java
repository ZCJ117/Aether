package cn.zcj.aether.domain.agent.service.executor;

import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.hook.LifecycleHook;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphExecutor 图生命周期钩子注入测试 — D3 Task 5。
 *
 * <p>GraphExecutor 是 @Service，字段经 @Resource 注入，依赖
 * agentFactory/conditionEvaluator/subAgentOrchestrator/interventionHandler；
 * 完整驱动 execute() 需 Spring 上下文。本测试采用注册表级验证：
 * <ol>
 *   <li>HookRegistry 对图节点级 + 图完成级挂点的分发正确性</li>
 *   <li>反射确认 GraphExecutor 已接线 hookRegistry 字段（@Resource）</li>
 * </ol>
 */
class GraphExecutorHookTest {

    @Test
    void registrySupportsGraphPoints() {
        HookRegistry registry = new HookRegistry();
        AtomicInteger nodeStart = new AtomicInteger(0);
        AtomicInteger nodeEnd = new AtomicInteger(0);
        AtomicInteger finalize = new AtomicInteger(0);
        registry.registerLifecycle(new LifecycleHook() {
            @Override public Set<HookPoint> points() {
                return Set.of(HookPoint.ON_GRAPH_NODE_START, HookPoint.ON_GRAPH_NODE_END, HookPoint.ON_GRAPH_FINALIZE);
            }
            @Override public void onHook(HookPoint point, HookContext ctx) {
                if (point == HookPoint.ON_GRAPH_NODE_START) nodeStart.incrementAndGet();
                else if (point == HookPoint.ON_GRAPH_NODE_END) nodeEnd.incrementAndGet();
                else finalize.incrementAndGet();
            }
        });

        registry.invokeAll(HookPoint.ON_GRAPH_NODE_START, HookContext.builder().agentId("n1").sessionId("s1").build());
        registry.invokeAll(HookPoint.ON_GRAPH_NODE_END, HookContext.builder().agentId("n1").sessionId("s1").build());
        registry.invokeAll(HookPoint.ON_GRAPH_FINALIZE, HookContext.builder().sessionId("s1").build());

        assertEquals(1, nodeStart.get());
        assertEquals(1, nodeEnd.get());
        assertEquals(1, finalize.get());
    }

    @Test
    void graphExecutorHasHookRegistryFieldForGraphPoints() throws Exception {
        Field field = GraphExecutor.class.getDeclaredField("hookRegistry");
        assertEquals(HookRegistry.class, field.getType());
        // 存在 @Resource 注解 → Spring 注入接线
        assertTrue(field.isAnnotationPresent(javax.annotation.Resource.class),
                "hookRegistry 必须经 @Resource 注入");
    }
}
