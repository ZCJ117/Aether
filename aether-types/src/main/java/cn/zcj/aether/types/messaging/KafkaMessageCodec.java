package cn.zcj.aether.types.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * P1(1.3): 消息 JSON 编解码 —— 生产/消费两端共用的唯一序列化契约。
 *
 * <p>StringSerializer/Deserializer 走纯文本，规避 JsonSerializer 的类型映射与
 * trusted-packages 陷阱；新增字段向前兼容（未知字段忽略）。</p>
 */
public final class KafkaMessageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private KafkaMessageCodec() {
    }

    /** 序列化失败极少见（纯 record）；异常原样抛出由调用方按发送失败处理。 */
    public static String toJson(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败: " + e.getMessage(), e);
        }
    }

    /** 解析失败返回 null（毒消息由消费端路由 DLT，不阻断批量处理）。 */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
