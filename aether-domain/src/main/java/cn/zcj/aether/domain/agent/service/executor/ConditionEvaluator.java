package cn.zcj.aether.domain.agent.service.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.AccessException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SpEL 条件表达式求值器（O19: 白名单沙箱求值）。
 * 用于 GraphFlow 的条件边（condition）和退出条件（exitCondition）。
 *
 * <p><b>安全约束（O19，fail-closed）</b>：表达式来自 {@code ai.agent.config} YAML
 * （经 {@code AgentGraphCompiler} 编译进 {@code AgentEdge}），信任边界为
 * "仅可信管理员可编辑该配置"；即便如此，求值也必须在白名单沙箱内完成，
 * 不允许任意 Java 方法调用：
 * <ol>
 *   <li>求值前结构校验（对剥离字符串字面量后的表达式）：拒绝 {@code T(...)} 类型引用、
 *       {@code new ...} 构造调用、{@code @} bean 引用，以及任何非白名单变量/方法名；</li>
 *   <li>求值上下文使用 {@link SimpleEvaluationContext}（原生禁用类型引用、构造器、
 *       bean 引用与索引/选择等全量 SpEL 能力），方法解析器仅放行
 *       {@code output}（String）上的白名单方法；</li>
 *   <li>仅暴露两个数据源：{@code output}（节点输出文本）与 {@code length}（输出长度），
 *       兼容 {@code output.contains('错误')}（根对象属性）与
 *       {@code #output.contains('错误')}（变量）两种写法；</li>
 *   <li>任何校验/求值失败一律返回 false（该边不激活），绝不回退到完整求值。</li>
 * </ol>
 */
@Slf4j
@Component
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /** O19: 允许在 output（String）上调用的方法白名单 */
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "contains", "startsWith", "endsWith",
            "length", "isBlank", "isEmpty",
            "equals", "equalsIgnoreCase",
            "toLowerCase", "toUpperCase", "trim");

    /** O19: 允许引用的变量/属性白名单 */
    private static final Set<String> ALLOWED_VARIABLES = Set.of("output", "length");

    /** O19: 类型引用 T(...) —— SimpleEvaluationContext 已原生拒绝，此处显式预校验以便日志归因 */
    private static final Pattern TYPE_REF = Pattern.compile("(?<![\\w$.])T\\s*\\(");

    /** O19: 构造调用 new X(...) */
    private static final Pattern NEW_OBJECT = Pattern.compile("\\bnew\\s+[A-Za-z_]");

    /** O19: bean 引用 @foo */
    private static final Pattern BEAN_REF = Pattern.compile("(?<![\\w])@");

    /** O19: 变量引用 #name（#this/#root 一并被白名单拒绝） */
    private static final Pattern VARIABLE_REF = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");

    /** O19: 方法调用 .name(...) */
    private static final Pattern METHOD_CALL = Pattern.compile("\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    /** O19: 属性导航 .name（后随非 "(" 时）—— 一律拒绝，防 .class/.bytes 等属性链绕过。
     *  占有量词（*+）防止回溯把 .contains( 的前缀误判为属性访问。 */
    private static final Pattern PROPERTY_NAV = Pattern.compile("\\.\\s*+([A-Za-z_][A-Za-z0-9_]*+)\\s*+(?!\\()");

    /** O19: 字符串字面量（结构校验前剥离，避免字面量内容被误判为代码结构） */
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");

    /**
     * O19: 方法解析器 —— 仅放行 String 目标上的白名单方法，其余一律返回 null（求值失败）。
     */
    private static final MethodResolver WHITELISTED_METHOD_RESOLVER = new ReflectiveMethodResolver() {
        @Override
        public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name,
                                      List<TypeDescriptor> argumentTypes) throws AccessException {
            if (!(targetObject instanceof String) || !ALLOWED_METHODS.contains(name)) {
                return null;
            }
            return super.resolve(context, targetObject, name, argumentTypes);
        }
    };

    /**
     * O19: 根对象 —— 兼容裸 {@code output}/裸 {@code length} 写法（只读属性访问）。
     */
    public static final class OutputScope {
        private final String output;
        private final int length;

        public OutputScope(String output) {
            this.output = output != null ? output : "";
            this.length = this.output.length();
        }

        public String getOutput() { return output; }

        public int getLength() { return length; }
    }

    /**
     * 对条件表达式求值。
     * @param expression SpEL 表达式（如 "output.contains('错误')"）
     * @param output     当前节点的输出文本
     * @return 表达式求值结果（表达式为 null 或 "true" 时默认返回 true；
     *         表达式违反白名单或求值失败时返回 false —— fail-closed）
     */
    public boolean evaluate(String expression, String output) {
        if (expression == null || expression.isBlank() || "true".equalsIgnoreCase(expression.trim())) {
            return true; // 无条件边，始终激活
        }
        String expr = expression.trim();

        // O19: 结构白名单预校验 —— 拒绝类型引用/构造/bean 引用/非白名单变量与方法
        String structuralViolation = findStructuralViolation(expr);
        if (structuralViolation != null) {
            log.warn("条件表达式被白名单拒绝（{}）: expression=[{}]",
                    structuralViolation, abbreviate(expression));
            return false;
        }

        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withMethodResolvers(WHITELISTED_METHOD_RESOLVER)
                    .withRootObject(new OutputScope(output))
                    .build();
            ctx.setVariable("output", output != null ? output : "");
            ctx.setVariable("length", output != null ? output.length() : 0);

            Boolean result = parser.parseExpression(expr).getValue(ctx, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("条件表达式求值失败: expression=[{}], output=[{}...], error={}",
                    abbreviate(expression),
                    output != null ? output.substring(0, Math.min(output.length(), 100)) : "null",
                    e.getMessage());
            return false; // 表达式求值失败，默认不激活
        }
    }

    /**
     * O19: 结构校验。返回违规原因描述；合规返回 null。
     */
    private String findStructuralViolation(String expression) {
        String code = STRING_LITERAL.matcher(expression).replaceAll("''");

        if (TYPE_REF.matcher(code).find()) {
            return "禁止类型引用 T(...)";
        }
        if (NEW_OBJECT.matcher(code).find()) {
            return "禁止构造调用 new ...";
        }
        if (BEAN_REF.matcher(code).find()) {
            return "禁止 bean 引用 @...";
        }

        java.util.regex.Matcher varMatcher = VARIABLE_REF.matcher(code);
        while (varMatcher.find()) {
            if (!ALLOWED_VARIABLES.contains(varMatcher.group(1))) {
                return "禁止非白名单变量 #" + varMatcher.group(1);
            }
        }

        java.util.regex.Matcher methodMatcher = METHOD_CALL.matcher(code);
        while (methodMatcher.find()) {
            if (!ALLOWED_METHODS.contains(methodMatcher.group(1))) {
                return "禁止非白名单方法 ." + methodMatcher.group(1) + "(...)";
            }
        }

        java.util.regex.Matcher navMatcher = PROPERTY_NAV.matcher(code);
        while (navMatcher.find()) {
            return "禁止属性访问 ." + navMatcher.group(1);
        }
        return null;
    }

    private String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
