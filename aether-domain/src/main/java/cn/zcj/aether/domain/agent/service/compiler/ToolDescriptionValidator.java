package cn.zcj.aether.domain.agent.service.compiler;

import java.util.Set;

/**
 * 工具描述校验器 —— 编译时检查工具描述是否包含模糊词，确保 LLM 能准确理解工具用途。
 */
public class ToolDescriptionValidator {

    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "可能", "大概", "也许", "或许", "或", "等", "等等",
            "maybe", "perhaps", "probably", "approximately", "etc", "and so on");

    public static void validate(String toolName, String description) {
        if (description == null || description.isBlank()) {
            throw new AgentCompileException("工具 [" + toolName + "] 描述为空");
        }

        String lower = description.toLowerCase();
        for (String word : FORBIDDEN_WORDS) {
            if (lower.contains(word.toLowerCase())) {
                throw new AgentCompileException(
                        "工具 [" + toolName + "] 描述含模糊词: \"" + word + "\"。请使用精确描述。");
            }
        }
    }
}
