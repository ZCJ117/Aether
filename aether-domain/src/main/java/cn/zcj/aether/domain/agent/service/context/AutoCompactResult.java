package cn.zcj.aether.domain.agent.service.context;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 自动压缩结果
 */
@Getter
@Builder
public class AutoCompactResult {

    private boolean compacted;
    private String summary;
    private List compressedMessages;
    private int preCompactTokens;
    private int postCompactTokens;

    public static AutoCompactResult notNeeded() {
        return AutoCompactResult.builder().compacted(false).build();
    }

    public static AutoCompactResult compacted(String summary, List compactMessages,
                                               int preTokens, int postTokens) {
        return AutoCompactResult.builder()
                .compacted(true)
                .summary(summary)
                .compressedMessages(compactMessages)
                .preCompactTokens(preTokens)
                .postCompactTokens(postTokens)
                .build();
    }
}
