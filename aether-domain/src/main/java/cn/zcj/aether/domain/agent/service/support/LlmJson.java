package cn.zcj.aether.domain.agent.service.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * LLM 结构化输出解析 —— 查询改写 / 记忆冲突合并等「提示词要求返回 JSON」的调用共用：
 * 剥离 markdown 代码围栏后解析（模型偶尔回 {@code ```json ... ```} 包裹）。
 */
public final class LlmJson {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private LlmJson() {
    }

    /**
     * @return 解析后的 JSON 树；文本为 null/空或解析失败抛 {@link IOException}（调用方按降级路径处理）。
     */
    public static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(stripFences(text));
    }

    /** 剥离 {@code ```} / {@code ```json} 围栏（若有）。 */
    static String stripFences(String text) {
        String json = text == null ? "" : text.trim();
        if (json.startsWith("```")) {
            json = json.substring(json.indexOf('\n') + 1);
            if (json.endsWith("```")) {
                json = json.substring(0, json.lastIndexOf("```")).trim();
            }
        }
        return json;
    }
}
