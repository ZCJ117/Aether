package cn.zcj.aether.domain.agent.service.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * SpEL 条件表达式求值器。
 * 用于 GraphFlow 的条件边（condition）和退出条件（exitCondition）。
 */
@Slf4j
@Component
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 对条件表达式求值。
     * @param expression SpEL 表达式（如 "output.contains('错误')"）
     * @param output     当前节点的输出文本
     * @return 表达式求值结果（表达式为 null 或 "true" 时默认返回 true）
     */
    public boolean evaluate(String expression, String output) {
        if (expression == null || expression.isBlank() || "true".equalsIgnoreCase(expression.trim())) {
            return true; // 无条件边，始终激活
        }
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("output", output != null ? output : "");
            // 同时暴露 length 和对 contains/startsWith/endsWith 的直接支持
            ctx.setVariable("length", output != null ? output.length() : 0);

            Boolean result = parser.parseExpression(expression).getValue(ctx, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("条件表达式求值失败: expression=[{}], output=[{}...], error={}",
                expression,
                output != null ? output.substring(0, Math.min(output.length(), 100)) : "null",
                e.getMessage());
            return false; // 表达式求值失败，默认不激活
        }
    }
}
