package cn.zcj.aether.domain.agent.service.curation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CurationPipeline {
    private final ResultSummarizer summarizer;

    public CurationResult curate(String rawContent, String toolName, int budgetTokens) {
        if (rawContent == null || rawContent.isEmpty()) {
            return new CurationResult("[空结果]", 0, 0);
        }
        int rawChars = rawContent.length();
        try {
            String summary = summarizer.summarize(rawContent, toolName, budgetTokens);
            int curatedChars = summary.length();
            log.info("CurationPipeline: tool={} rawChars={} curatedChars={} ratio={:.0%}",
                    toolName, rawChars, curatedChars,
                    rawChars > 0 ? (double) curatedChars / rawChars : 1.0);
            return new CurationResult(summary, rawChars, curatedChars);
        } catch (Exception e) {
            log.warn("CurationPipeline 策展异常，降级: toolName={}", toolName, e);
            String fallback = rawContent.length() > 500 ? rawContent.substring(0, 500) + "..." : rawContent;
            return new CurationResult(fallback, rawChars, fallback.length());
        }
    }

    public record CurationResult(String summary, int rawChars, int curatedChars) {
        public double compressionRatio() {
            return rawChars > 0 ? (double) curatedChars / rawChars : 1.0;
        }
    }
}
