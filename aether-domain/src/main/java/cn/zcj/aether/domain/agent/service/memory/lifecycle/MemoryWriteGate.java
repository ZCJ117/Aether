package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.EncodingFlow;

/**
 * P1(4.3): 记忆写入质量门槛 —— 降低噪音记忆污染召回（路线图 4.3 第 2 步）。
 *
 * <p>仅在 <b>LLM 成功打分</b>时生效：importance &lt; minImportance（默认 0.6 ≈ 3/5）的
 * 候选记忆不入库。无 LLM（默认重要性 0.5）或编码失败时<b>放行</b>——
 * 门槛针对的是"LLM 明确判定为琐碎"的记忆，而非降低系统在无 LLM 环境下的可用性。</p>
 */
public final class MemoryWriteGate {

    private MemoryWriteGate() {
    }

    /** @return true = 允许入库；false = 拒绝（调用方不落库并打点） */
    public static boolean shouldAccept(EncodingFlow.EncodeResult encoded, float minImportance, boolean gateEnabled) {
        if (!gateEnabled) {
            return true;
        }
        // 编码未走 LLM（未配置/关闭/失败）：默认重要性不可信，放行
        if (encoded == null || !encoded.llmCalled() || !encoded.llmSuccess()) {
            return true;
        }
        return encoded.importance() >= minImportance;
    }
}
