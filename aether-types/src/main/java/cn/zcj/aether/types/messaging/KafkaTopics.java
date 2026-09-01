package cn.zcj.aether.types.messaging;

/**
 * P1(1.3): Kafka 主题契约 —— 生产端（aether-app）与消费端（aether-trigger）共享。
 *
 * <p>分区语义：{@code aether.agent.events} 以 sessionId 为 key，保证会话内事件有序；
 * {@code aether.audit.events} 以 userId 为 key，保证单用户审计有序。</p>
 */
public final class KafkaTopics {

    /** 审计事件（AuditEventMessage JSON；消费端批量写 t_audit_log） */
    public static final String AUDIT_EVENTS = "aether.audit.events";
    public static final String AUDIT_EVENTS_DLT = AUDIT_EVENTS + ".DLT";

    /** Agent 事件（AgentEventMessage JSON；消费端聚合 dashboard_stats） */
    public static final String AGENT_EVENTS = "aether.agent.events";
    public static final String AGENT_EVENTS_DLT = AGENT_EVENTS + ".DLT";

    private KafkaTopics() {
    }
}
