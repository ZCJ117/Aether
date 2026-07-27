package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 安全切点查找器。
 * 在消息列表中找到安全切点，确保不切断 (assistant含tool_use, tool_result) 配对。
 * 借鉴 AgentScope-Java findSafeCutoffPoint() 算法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafeCutoffFinder {

    private final TokenEstimator tokenEstimator;

    /**
     * 找到安全切点索引。
     * messages[0..cutoffIndex) 将被压缩，messages[cutoffIndex..) 将被保留。
     *
     * @param messages   完整消息列表
     * @param keepTokens 保留尾部的 token 上限
     * @return 切点索引（0 表示压缩全部，messages.size() 表示不压缩）
     */
    public int findCutoff(List<TurnMessage> messages, int keepTokens) {
        int n = messages.size();
        if (n == 0) return 0;

        // Step 1: 从尾向头累加 token
        int accumulated = 0;
        int cutoffIndex = n;
        for (int i = n - 1; i >= 0; i--) {
            TurnMessage msg = messages.get(i);
            int tokens = tokenEstimator.estimate(msg.content());
            if (accumulated + tokens > keepTokens) {
                cutoffIndex = i + 1;
                break;
            }
            accumulated += tokens;
            cutoffIndex = i;
        }

        // Step 2: 确保不切断 tool 配对
        cutoffIndex = adjustForPairIntegrity(messages, cutoffIndex);

        log.debug("SafeCutoffFinder: cutoffIndex={} totalMessages={} keptTokens={}",
                cutoffIndex, n, accumulated);
        return cutoffIndex;
    }

    /**
     * 调整切点，确保不位于 (assistant含tool_use, tool_result) 配对的中间。
     */
    private int adjustForPairIntegrity(List<TurnMessage> messages, int cutoffIndex) {
        int n = messages.size();

        // 切点处的消息是 tool_result → 向前找到对应的 assistant
        if (cutoffIndex < n && messages.get(cutoffIndex).isToolResult()) {
            String callId = messages.get(cutoffIndex).toolCallId();
            int pairedAssistant = findPairedAssistant(messages, cutoffIndex, callId);
            if (pairedAssistant >= 0) {
                cutoffIndex = pairedAssistant;
            }
        }

        // 切点前一条是 tool_result（它属于被压缩部分）
        // → 确保它的配对 assistant 也在被压缩部分
        if (cutoffIndex > 0 && messages.get(cutoffIndex - 1).isToolResult()) {
            String callId = messages.get(cutoffIndex - 1).toolCallId();
            int pairedAssistant = findPairedAssistant(messages, cutoffIndex - 1, callId);
            if (pairedAssistant >= 0 && pairedAssistant >= cutoffIndex) {
                // assistant 在保留部分 → 把 cut 前移以包含它
                cutoffIndex = pairedAssistant;
            }
        }

        return Math.max(0, cutoffIndex);
    }

    /**
     * 从 tool_result 位置向前扫描，找到包含对应 tool_call_id 的 assistant 消息。
     */
    private int findPairedAssistant(List<TurnMessage> messages, int toolResultIndex, String toolCallId) {
        if (toolCallId == null) return -1;
        for (int i = toolResultIndex - 1; i >= 0; i--) {
            TurnMessage msg = messages.get(i);
            if (!"assistant".equals(msg.role()) || !msg.hasToolCalls()) continue;
            for (Map<String, Object> tc : msg.toolCalls()) {
                if (toolCallId.equals(tc.get("id"))) {
                    return i;
                }
            }
        }
        return -1;
    }
}
