package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.EncodingFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.3): 写入门槛测试 —— 仅拦"LLM 明确判定为琐碎"，无 LLM/编码失败一律放行。
 */
class MemoryWriteGateTest {

    private static EncodingFlow.EncodeResult llmScored(float importance) {
        return new EncodingFlow.EncodeResult(List.of("tag"), importance, false, "", true, true, 100);
    }

    private static EncodingFlow.EncodeResult defaults() {
        return EncodingFlow.EncodeResult.defaults();
    }

    @Test
    void rejectsLowImportanceWhenLlmScored() {
        assertFalse(MemoryWriteGate.shouldAccept(llmScored(0.3f), 0.6f, true),
                "LLM 打分 0.3 < 0.6 应拒绝");
        assertFalse(MemoryWriteGate.shouldAccept(llmScored(0.59f), 0.6f, true));
    }

    @Test
    void acceptsHighImportance() {
        assertTrue(MemoryWriteGate.shouldAccept(llmScored(0.6f), 0.6f, true), "恰好等于阈值应放行");
        assertTrue(MemoryWriteGate.shouldAccept(llmScored(0.95f), 0.6f, true));
    }

    @Test
    void acceptsWhenNoLlmScoring() {
        // 无 LLM（默认 0.5）或编码失败：门槛不生效，保证无 LLM 环境记忆系统可用
        assertTrue(MemoryWriteGate.shouldAccept(defaults(), 0.6f, true));
        assertTrue(MemoryWriteGate.shouldAccept(llmScored(0.1f), 0.6f, false), "门槛关闭全放行");
    }
}
