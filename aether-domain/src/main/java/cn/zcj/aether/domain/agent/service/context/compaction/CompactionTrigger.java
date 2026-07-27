package cn.zcj.aether.domain.agent.service.context.compaction;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 双阈值压缩触发器。
 * 借鉴 AgentScope-Java ConversationCompactor 的 triggerMessages + triggerTokens 模式。
 * 任一条件超过阈值即触发压缩。
 */
@Getter
@Component
public class CompactionTrigger {

    @Value("${aether.context.compaction.trigger-messages:150}")
    private int triggerMessages;

    @Value("${aether.context.compaction.trigger-tokens:80000}")
    private int triggerTokens;

    @Value("${aether.context.compaction.keep-messages:20}")
    private int keepMessages;

    @Value("${aether.context.compaction.keep-tokens:10000}")
    private int keepTokens;

    /**
     * 判断是否应触发压缩。
     *
     * @param messageCount 当前消息总数
     * @param tokenCount   当前估算 token 总数
     * @return true 如果任一超过阈值
     */
    public boolean shouldCompact(int messageCount, int tokenCount) {
        return messageCount > triggerMessages || tokenCount > triggerTokens;
    }
}
