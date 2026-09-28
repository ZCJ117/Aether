package cn.zcj.aether;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.subagent.AsyncDelegationService;
import cn.zcj.aether.domain.agent.service.subagent.CompletionBus;
import cn.zcj.aether.domain.agent.service.subagent.DelegationCompletion;
import cn.zcj.aether.domain.agent.service.subagent.DelegationCompletionSink;
import cn.zcj.aether.domain.agent.service.subagent.PendingDelegationInbox;
import cn.zcj.aether.domain.agent.service.subagent.SubagentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D3/VUL-05 装配验证（真 Spring Boot 上下文）。
 *
 * <p>对应验收用例 T5-2（{@code CompletionBus} 有真实生产订阅者）与 F5-6
 * （{@code @DependsOn("delegationCompletionSink")} 的 Bean 名必须存在且无循环依赖）。</p>
 *
 * <p>与 {@code AgentIntegrationTest} 保持完全一致的上下文配置，以便 Spring 复用同一个
 * 缓存上下文（不会二次启动）。</p>
 */
@SpringBootTest(
        properties = {
                "spring.profiles.active=test",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
        }
)
class CompletionBusWiringTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.UserRepository userRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.RefreshTokenRepository refreshTokenRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.AuditLogRepository auditLogRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.DashboardStatsRepository dashboardStatsRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.ProcessedEventRepository processedEventRepository;

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private CompletionBus completionBus;

    @Autowired
    private PendingDelegationInbox inbox;

    @Autowired
    private DefaultAgentFactory agentFactory;

    /**
     * T5-2：{@code CompletionBus.subscribe} 必须有生产调用点。
     *
     * <p>{@code publish} 返回"成功接收的订阅者数"，因此断言 {@code >= 1} 直接证明
     * 生产上下文里订阅者非空 —— 否则 {@code publish} 只是遍历空集合的<em>空循环</em>。</p>
     */
    @Test
    @DisplayName("T5-2: 生产上下文里 CompletionBus 至少有 1 个订阅者")
    void completionBusHasProductionSubscriber() {
        DelegationCompletion completion = new DelegationCompletion(
                "ad-wiring", "wiring-session", "wiring-agent", "task",
                SubagentState.COMPLETED, "ok", Instant.now());

        int delivered = completionBus.publish(completion);

        assertTrue(delivered >= 1,
                "subscribe 必须有生产调用点（实际投递数=" + delivered + "）—— "
                        + "否则异步委派仍是「发射后不管」");

        var drained = inbox.drain("wiring-session");
        assertEquals(1, drained.size(), "订阅者应把完成事件落到父会话收件箱");
        assertEquals("ad-wiring", drained.get(0).delegationId());
    }

    /**
     * F5-6：{@code @DependsOn("delegationCompletionSink")} 的目标 Bean 必须存在。
     *
     * <p>上下文能启动本身即证明 Bean 名正确且无循环依赖；此处再显式断言两个 Bean 都在，
     * 使失败信息更直白（规格 §8.2：写错 Bean 名会导致启动直接失败）。</p>
     */
    @Test
    @DisplayName("F5-6: delegationCompletionSink 与 AsyncDelegationService 均已装配")
    void dependsOnTargetBeanExists() {
        assertTrue(ctx.containsBean("delegationCompletionSink"),
                "@DependsOn(\"delegationCompletionSink\") 的目标 Bean 名必须与实际 Bean 名一致");
        assertNotNull(ctx.getBean(DelegationCompletionSink.class));
        assertNotNull(ctx.getBean(AsyncDelegationService.class),
                "@DependsOn 目标存在 → AsyncDelegationService 才能正常装配");
    }

    /**
     * D3 验收门槛 3 的**真装配**部分：经 {@code DefaultAgentFactory} 造出的 Agent，
     * 其委派收件箱必须就是 {@code CompletionBus} 订阅者写入的那一个实例。
     *
     * <p>此前的端到端用例全程手工 new 对象，证明的是"这些类拼起来能工作"；而真实装配路径是
     * {@code DefaultAgentFactory.java:101} 的 {@code resolveBean(PendingDelegationInbox.class)}
     * ——若该行缺失或解析不到 Bean，Agent 侧 {@code delegationInbox} 为 null，
     * 父会话下一轮静默看不到任何子任务完成通知，且不报任何错。本用例用
     * "总线发布 → Agent 自己的收件箱收到"把该装配点钉死。</p>
     */
    @Test
    @DisplayName("D3 门槛3: 真工厂装配的 Agent 与总线订阅者共用同一收件箱")
    void factoryWiredAgentSharesInboxWithBus() throws Exception {
        Agent agent = agentFactory.create(AgentConfig.builder()
                .name("d3-wiring-agent")
                .instruction("测试指令")
                .description("D3 装配验证")
                .outputKey("result")
                .modelRef("gpt-4o")
                .agentType("react")
                .build());

        Field field = ReActAgent.class.getDeclaredField("delegationInbox");
        field.setAccessible(true);
        Object agentInbox = field.get(agent);

        assertNotNull(agentInbox,
                "DefaultAgentFactory:101 必须把 PendingDelegationInbox 注入 Agent——"
                        + "否则父会话下一轮永远看不到子任务完成通知（静默失效）");

        String sessionId = "d3-factory-wiring-session";
        int delivered = completionBus.publish(new DelegationCompletion(
                "ad-factory", sessionId, "d3-wiring-agent", "task",
                SubagentState.COMPLETED, "ok", Instant.now()));
        assertTrue(delivered >= 1, "总线应至少投递给一个订阅者（实际=" + delivered + "）");

        List<DelegationCompletion> drained = ((PendingDelegationInbox) agentInbox).drain(sessionId);

        assertEquals(1, drained.size(),
                "Agent 持有的收件箱必须与总线订阅者写入的是同一个实例；"
                        + "若此处为空，说明工厂注入的与订阅者写入的不是同一个 Bean");
        assertEquals("ad-factory", drained.get(0).delegationId());
    }
}
