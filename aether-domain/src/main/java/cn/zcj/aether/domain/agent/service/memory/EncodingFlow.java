package cn.zcj.aether.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * LLM 编码管线 —— 推断记忆元数据（scope、categories、importance）。
 * 灵感来源：CrewAI EncodingFlow — 使用 LLM 推断记忆的元数据属性。
 *
 * 在存储记忆时调用，用于自动补全：
 * - categories：分类标签
 * - importance：重要性分数（0.0～1.0）
 * - 去重检测：返回是否需要合并以及合并到的目标记忆 ID
 */
@Slf4j
@Component
public class EncodingFlow {

    private static final String ENCODE_PROMPT = """
        你是一个记忆元数据推断助手。给定一条用户想要保存的记忆内容，请分析并返回 JSON 格式的元数据。

        需要推断的字段：
        1. categories: 分类标签列表（如 ["技术", "Java", "架构"]），2-5 个为宜
        2. importance: 重要性分数，0.0～1.0（1.0 = 极其重要，0.0 = 琐碎）
        3. shouldConsolidate: 是否需要与已有记忆合并（true/false）
        4. consolidationHint: 若应合并，简要描述应合并到哪种已有记忆（用于检索匹配）

        请严格返回如下 JSON 格式，不要包含 markdown 代码块标记：
        {"categories":["标签1","标签2"],"importance":0.75,"shouldConsolidate":false,"consolidationHint":""}

        记忆内容：
        %s
        """;

    /** ChatModel 动态注册，延迟注入 + 可选（无 LLM 时回退默认元数据） */
    private final ChatModel chatModel;

    public EncodingFlow(@org.springframework.beans.factory.annotation.Autowired(required = false) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 使用 LLM 推断记忆元数据。
     *
     * @param content 记忆文本内容
     * @return 编码结果，包含 categories、importance 等；LLM 调用失败时返回默认值
     */
    public EncodeResult encode(String content) {
        if (content == null || content.isBlank()) {
            return EncodeResult.defaults();
        }
        // ChatModel 尚未注册时（启动早期），直接返回默认元数据
        if (chatModel == null) {
            log.debug("ChatModel 尚未就绪，跳过 LLM 编码");
            return EncodeResult.defaults();
        }

        try {
            String prompt = String.format(ENCODE_PROMPT, content);
            var response = chatModel.call(new Prompt(new SystemMessage(prompt)));
            String text = response.getResult().getOutput().getText();
            return parseResponse(text);
        } catch (Exception e) {
            log.warn("LLM 记忆编码失败，使用默认元数据: error={}", e.getMessage());
            return EncodeResult.defaults();
        }
    }

    private EncodeResult parseResponse(String text) {
        if (text == null || text.isBlank()) return EncodeResult.defaults();
        try {
            // 清理可能的 markdown 代码块标记
            String json = text.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf('\n') + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```")).trim();
                }
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            List<String> categories = node.has("categories")
                ? Arrays.asList(mapper.convertValue(node.get("categories"), String[].class))
                : Collections.emptyList();
            float importance = node.has("importance")
                ? (float) node.get("importance").asDouble()
                : 0.5f;
            boolean shouldConsolidate = node.has("shouldConsolidate") && node.get("shouldConsolidate").asBoolean();
            String hint = node.has("consolidationHint") ? node.get("consolidationHint").asText() : "";
            return new EncodeResult(categories, importance, shouldConsolidate, hint);
        } catch (Exception e) {
            log.warn("解析 LLM 记忆编码结果失败: text=[{}], error={}", text, e.getMessage());
            return EncodeResult.defaults();
        }
    }

    /** 编码结果 */
    public record EncodeResult(
        List<String> categories,
        float importance,
        boolean shouldConsolidate,
        String consolidationHint
    ) {
        public static EncodeResult defaults() {
            return new EncodeResult(Collections.emptyList(), 0.5f, false, "");
        }
    }
}
