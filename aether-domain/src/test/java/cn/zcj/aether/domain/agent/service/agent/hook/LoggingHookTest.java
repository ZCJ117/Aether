package cn.zcj.aether.domain.agent.service.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LoggingHookTest {

    @Test
    void mapperSerializesInstantAsIsoString() {
        ObjectMapper mapper = (ObjectMapper) ReflectionTestUtils.getField(LoggingHook.class, "MAPPER");
        assertNotNull(mapper, "MAPPER 静态字段应存在");

        // 裸 ObjectMapper（无 JavaTimeModule）对 Instant 序列化会抛 InvalidDefinitionException
        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(Instant.now()));
        assertTrue(json.startsWith("\""), "Instant 应序列化为带引号的 ISO 字符串，实际: " + json);
    }
}
