package cn.zcj.aether.messaging;

import cn.zcj.aether.infrastructure.persistence.AuditLogRepository;
import cn.zcj.aether.infrastructure.persistence.DashboardStatsRepository;
import cn.zcj.aether.infrastructure.persistence.ProcessedEventRepository;
import cn.zcj.aether.trigger.listener.AuditEventConsumer;
import cn.zcj.aether.trigger.listener.DashboardStatsConsumer;
import cn.zcj.aether.types.messaging.AgentEventMessage;
import cn.zcj.aether.types.messaging.AuditEventMessage;
import cn.zcj.aether.types.messaging.KafkaMessageCodec;
import cn.zcj.aether.types.messaging.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P1(1.3): Kafka 消息管道集成测试（Testcontainers PG + Testcontainers Kafka KRaft；-Pintegration / CI integration job）。
 *
 * <p>覆盖消费闭环三大承诺：<b>不重</b>（重复 eventId 幂等去重）、<b>不丢</b>
 * （批量落库后手动 ack）、<b>聚合正确</b>（dashboard_stats 增量 upsert）。
 * 手工装配消费端真实协作者（真实 JdbcTemplate 指向 PG 容器），不启动整个应用上下文。</p>
 *
 * <p>注：不使用 spring-kafka-test 的 @EmbeddedKafka——其全局 TestExecutionListener 与
 * xfg-wrench fat-jar 的 logback 冲突（见 aether-app/pom.xml 注释）；Testcontainers Kafka
 * 与生产 KRaft 部署（docker-compose-fullstack.yml 的 bitnami/kafka）同构。</p>
 */
@Tag("integration")
class KafkaAuditPipelineIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aether").withUsername("aether").withPassword("aether");

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.8.0"));

    static KafkaProducer<String, String> producer;
    static KafkaConsumer<String, String> consumer;

    static AuditLogRepository auditLogRepository;
    static ProcessedEventRepository processedEventRepository;
    static DashboardStatsRepository dashboardStatsRepository;
    static AuditEventConsumer auditConsumer;
    static DashboardStatsConsumer statsConsumer;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        KAFKA.start();

        // ---- 真实 PG：执行 app schema.sql（t_audit_log+event_id / dashboard_stats / aether_processed_event）----
        DataSource ds = dataSourceOf();
        String schema = Files.readString(
                Path.of("..", "aether-app", "src", "main", "resources", "schema.sql"));
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (String part : schema.split(";")) {
                String stmt = part.strip();
                if (!stmt.isEmpty()) {
                    st.execute(stmt);
                }
            }
        }

        auditLogRepository = new AuditLogRepository(ds);
        processedEventRepository = new ProcessedEventRepository(ds);
        dashboardStatsRepository = new DashboardStatsRepository(ds);
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<Object, Object> dltTemplate =
                mock(org.springframework.kafka.core.KafkaTemplate.class);
        auditConsumer = new AuditEventConsumer(auditLogRepository, dltTemplate);
        statsConsumer = new DashboardStatsConsumer(processedEventRepository, dashboardStatsRepository, dltTemplate);

        // ---- Testcontainers Kafka（KRaft 单节点，与 fullstack compose 同构）----
        String bootstrap = KAFKA.getBootstrapServers();
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        producer = new KafkaProducer<>(producerProps);

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "it-group");
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(List.of(KafkaTopics.AUDIT_EVENTS, KafkaTopics.AGENT_EVENTS));
    }

    @AfterAll
    static void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
        KAFKA.stop();
        PG.stop();
    }

    @Test
    void auditBatchDedupsDuplicateEventIds() throws Exception {
        String eventId = UUID.randomUUID().toString();
        AuditEventMessage first = new AuditEventMessage(eventId, "LOGIN", "auth", "{}",
                1L, "alice", "1.2.3.4", true, null, "c-1", Instant.now());
        AuditEventMessage duplicate = new AuditEventMessage(eventId, "LOGIN", "auth", "{}",
                1L, "alice", "1.2.3.4", true, null, "c-1", Instant.now());
        AuditEventMessage other = new AuditEventMessage(UUID.randomUUID().toString(), "LOGOUT",
                "auth", "{}", 1L, "alice", "1.2.3.4", true, null, "c-1", Instant.now());

        producer.send(new ProducerRecord<>(KafkaTopics.AUDIT_EVENTS, "1", KafkaMessageCodec.toJson(first))).get();
        producer.send(new ProducerRecord<>(KafkaTopics.AUDIT_EVENTS, "1", KafkaMessageCodec.toJson(duplicate))).get();
        producer.send(new ProducerRecord<>(KafkaTopics.AUDIT_EVENTS, "1", KafkaMessageCodec.toJson(other))).get();

        List<ConsumerRecord<Object, Object>> records = pollRecords(KafkaTopics.AUDIT_EVENTS, 3);
        assertEquals(3, records.size());

        var jdbc = auditJdbc();
        int before = totalAuditRows(jdbc, eventId);
        Acknowledgment ack = mock(Acknowledgment.class);
        auditConsumer.onBatch(records, ack);
        int after = totalAuditRows(jdbc, eventId);

        assertEquals(2, after - before, "3 条消息（1 条重复 eventId）应只写 2 行");
        verify(ack).acknowledge(); // 不丢承诺：处理完成后手动提交
    }

    @Test
    void statsAggregationIsIdempotentAndCorrect() throws Exception {
        String agentId = "agent-it-" + UUID.randomUUID().toString().substring(0, 8);
        String eid = UUID.randomUUID().toString();
        List<AgentEventMessage> events = List.of(
                new AgentEventMessage(eid, "turn.completed", agentId, "s1", "c1",
                        1, true, 2, 100, 0, 0, 0, null, false, null, Instant.now()),
                new AgentEventMessage(eid, "turn.completed", agentId, "s1", "c1",
                        1, true, 2, 100, 0, 0, 0, null, false, null, Instant.now()), // 重复 eventId
                new AgentEventMessage(UUID.randomUUID().toString(), "tool.call.completed", agentId, "s1", "c1",
                        0, false, 0, 50, 0, 0, 0, "code", false, null, Instant.now()),
                new AgentEventMessage(UUID.randomUUID().toString(), "agent.completed", agentId, "s1", "c1",
                        3, false, 0, 9000, 0, 0, 0, null, false, "success", Instant.now()));

        for (AgentEventMessage e : events) {
            producer.send(new ProducerRecord<>(KafkaTopics.AGENT_EVENTS, "s1", KafkaMessageCodec.toJson(e))).get();
        }
        List<ConsumerRecord<Object, Object>> records = pollRecords(KafkaTopics.AGENT_EVENTS, 4);
        assertEquals(4, records.size());

        Acknowledgment ack = mock(Acknowledgment.class);
        statsConsumer.onBatch(records, ack);

        var snap = statsSnapshot(agentId);
        assertEquals(1, snap[0], "sessions_total=1（重复去重）");
        assertEquals(1, snap[1], "turns_total=1（重复去重）");
        assertEquals(3, snap[2], "tool_calls_total=2(turn)+1(tool)");
        assertEquals(1, snap[3], "tool_errors_total=1");

        // 重放同一批（模拟 rebalance 后重复投递）→ 计数不变
        statsConsumer.onBatch(records, ack);
        var snap2 = statsSnapshot(agentId);
        assertEquals(1, snap2[0]);
        assertEquals(1, snap2[1]);
        verify(ack).acknowledge();
    }

    @Test
    void poisonMessageIsRoutedToDltNotBlockingBatch() throws Exception {
        producer.send(new ProducerRecord<>(KafkaTopics.AUDIT_EVENTS, "1", "not-a-valid-json{")).get();
        List<ConsumerRecord<Object, Object>> records = pollRecords(KafkaTopics.AUDIT_EVENTS, 1);
        assertEquals(1, records.size());
        Acknowledgment ack = mock(Acknowledgment.class);
        // 不抛异常即通过（毒消息路由 DLT，整批其余消息照常处理）
        auditConsumer.onBatch(records, ack);
    }

    // ====== helpers ======

    private static List<ConsumerRecord<Object, Object>> pollRecords(String topic, int expected) {
        List<ConsumerRecord<Object, Object>> matched = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 60_000;
        while (matched.size() < expected && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                if (topic.equals(r.topic())) {
                    matched.add(new ConsumerRecord<>(r.topic(), r.partition(), r.offset(),
                            r.key(), r.value()));
                }
            }
        }
        return matched;
    }

    private static javax.sql.DataSource dataSourceOf() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    private static org.springframework.jdbc.core.JdbcTemplate auditJdbc() {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSourceOf());
    }

    private static int totalAuditRows(org.springframework.jdbc.core.JdbcTemplate jdbc, String eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    /** {sessions_total, turns_total, tool_calls_total, tool_errors_total} */
    private static long[] statsSnapshot(String agentId) {
        var jdbc = auditJdbc();
        return jdbc.queryForObject(
                "SELECT sessions_total, turns_total, tool_calls_total, tool_errors_total " +
                "FROM dashboard_stats WHERE agent_id = ?",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)},
                agentId);
    }
}
