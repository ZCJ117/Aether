package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.support.LlmJson;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P1(4.3): 记忆冲突合并 —— 同 scope 同主题（相似度 ≥ consolidationThreshold）新旧记忆冲突时：
 *
 * <ul>
 *   <li>{@code concat}（默认，兼容原行为）：内容拼接 + 重要性均值；</li>
 *   <li>{@code new-wins}：新内容整体替换旧记忆（用户偏好改了旧记忆立即失效）；</li>
 *   <li>{@code llm}：LLM 判定 merge（融合改写）/ replace（新胜旧），失败回退 concat。</li>
 * </ul>
 *
 * 解决"用户偏好改了但旧记忆还在打架"问题（路线图 4.3 第 3 步）。
 */
@Slf4j
@Component
public class MemoryConflictResolver {

    private static final String MERGE_PROMPT = """
            你是记忆合并助手。同一主题存在一条旧记忆和一条新记忆，两者可能冲突。
            请判定并返回 JSON（不要 markdown 代码块）：
            {"action":"merge","content":"<融合新旧信息后的单条记忆文本>"}
            或
            {"action":"replace","content":"<以新记忆为准的文本>"}

            旧记忆：%s
            新记忆：%s
            """;

    /** llm 失败回退策略 */
    private final String strategy;
    private final ChatModel chatModel;

    public MemoryConflictResolver(
            @Autowired(required = false) ChatModel chatModel,
            @Value("${aether.memory.conflict.strategy:concat}") String strategy) {
        this.chatModel = chatModel;
        this.strategy = strategy == null ? "concat" : strategy.trim().toLowerCase();
    }

    /**
     * @param existing    命中的旧记忆
     * @param newContent  新记忆内容
     * @param newImportance 新记忆重要性（LLM 编码分或默认）
     */
    public Outcome resolve(MemoryRecord existing, String newContent, float newImportance) {
        return switch (strategy) {
            case "new-wins" -> new Outcome("new-wins", newContent, newImportance);
            case "llm" -> resolveByLlm(existing, newContent, newImportance);
            default -> new Outcome("concat",
                    existing.getContent() + "\n\n" + newContent,
                    (existing.getImportance() + newImportance) / 2);
        };
    }

    private Outcome resolveByLlm(MemoryRecord existing, String newContent, float newImportance) {
        if (chatModel == null) {
            log.debug("conflict.strategy=llm 但 ChatModel 未就绪，回退 concat");
            return concatOutcome(existing, newContent);
        }
        try {
            String prompt = String.format(MERGE_PROMPT,
                    truncate(existing.getContent()), truncate(newContent));
            var response = chatModel.call(new Prompt(new UserMessage(prompt)));
            String text = response.getResult().getOutput().getText();
            JsonNode node = LlmJson.parse(text);
            String action = node.has("action") ? node.get("action").asText("merge") : "merge";
            String content = node.has("content") ? node.get("content").asText("") : "";
            if (content.isBlank()) {
                return concatOutcome(existing, newContent);
            }
            return new Outcome("replace".equals(action) ? "llm-replace" : "llm-merge",
                    content, Math.max(existing.getImportance(), newImportance));
        } catch (Exception e) {
            log.warn("LLM 冲突合并失败，回退 concat: {}", e.getMessage());
            return concatOutcome(existing, newContent);
        }
    }

    private Outcome concatOutcome(MemoryRecord existing, String newContent) {
        return new Outcome("concat-fallback",
                existing.getContent() + "\n\n" + newContent, existing.getImportance());
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /** 合并结论：策略（含回退标注）+ 合并后内容 + 重要性。 */
    public record Outcome(String strategy, String content, float importance) {
    }
}
