package cn.zcj.aether.trigger.listener;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * P1(1.3): Kafka 批量消费容器工厂（aether.kafka.enabled=true 时装配）。
 *
 * <p>投递语义三件套：<b>手动 ack</b>（MANUAL_IMMEDIATE，处理完才提交，不丢）、
 * <b>幂等</b>（消费端 event_id 去重，见各 Consumer）、<b>重试 + 死信</b>
 * （指数退避 1s 起共 3 次后路由 {@code *.DLT}，毒消息不阻塞分区）。
 * key/value 均为 String，消息契约见 {@code cn.zcj.aether.types.messaging}。</p>
 */
@Configuration
@ConditionalOnProperty(name = "aether.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<Object, Object> aetherConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>(props);
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            // 消费端指标（kafka.consumer.fetch.manager.records.lag 等）→ lag 告警数据源
            factory.addListener(new MicrometerConsumerListener<>(registry));
        }
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> batchListenerContainerFactory(
            ConsumerFactory<Object, Object> aetherConsumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(aetherConsumerFactory);
        factory.setConcurrency(2);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 重试 3 次（1s 指数退避）→ 原消息原样路由 <topic>.DLT，offset 随之提交
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),
                new ExponentialBackOffWithMaxRetries(3));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
