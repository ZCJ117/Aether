package cn.zcj.aether.domain.agent.service.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O19: ConditionEvaluator 白名单沙箱回归测试。
 *
 * <p>验收标准（对齐 03 文档 O19）：
 * <ul>
 *   <li>合法表达式（如 {@code output.contains('错误')}）不受影响；</li>
 *   <li>类型引用 {@code T(...)}、构造 {@code new ...}、bean 引用、
 *       非白名单方法/变量一律 fail-closed 返回 false，绝不执行任意 Java。</li>
 * </ul>
 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    // ===== 合法表达式 =====

    @Test
    void blankOrNullAlwaysActivates() {
        assertTrue(evaluator.evaluate(null, "任意输出"));
        assertTrue(evaluator.evaluate("", "任意输出"));
        assertTrue(evaluator.evaluate("  ", "任意输出"));
        assertTrue(evaluator.evaluate("true", "任意输出"));
    }

    @Test
    void bareOutputPropertyAccessWorks() {
        assertTrue(evaluator.evaluate("output.contains('错误')", "处理中发生错误"));
        assertFalse(evaluator.evaluate("output.contains('错误')", "一切正常"));
    }

    @Test
    void variableSyntaxWorks() {
        assertTrue(evaluator.evaluate("#output.contains('错误')", "发生错误"));
        assertTrue(evaluator.evaluate("#output.length() > 3", "很长的输出内容"));
        assertTrue(evaluator.evaluate("length > 2", "abc"));
        assertTrue(evaluator.evaluate("#output.startsWith('开始')", "开始处理"));
        assertTrue(evaluator.evaluate("!#output.isBlank()", "内容"));
    }

    @Test
    void combinedBooleanExpressionWorks() {
        assertTrue(evaluator.evaluate("#output.contains('错误') || #output.contains('异常')", "发现异常"));
        assertFalse(evaluator.evaluate("#output.contains('错误') && #output.contains('异常')", "只有错误"));
    }

    // ===== 恶意表达式 fail-closed =====

    @Test
    void typeReferenceRejected() {
        assertFalse(evaluator.evaluate("T(java.lang.Runtime).getRuntime().exec('calc')", "x"));
        assertFalse(evaluator.evaluate("T(java.lang.System).getProperty('user.dir') == 'x'", "x"));
    }

    @Test
    void constructorInvocationRejected() {
        assertFalse(evaluator.evaluate("new java.io.File('/etc/passwd').exists()", "x"));
    }

    @Test
    void beanReferenceRejected() {
        assertFalse(evaluator.evaluate("@systemService.doSomething() == 'x'", "x"));
    }

    @Test
    void nonWhitelistedMethodRejected() {
        assertFalse(evaluator.evaluate("#output.getClass().getName() == 'x'", "x"));
        assertFalse(evaluator.evaluate("#output.bytes.length > 0", "x"));
        assertFalse(evaluator.evaluate("#output.repeat(3) == 'x'", "x"));
    }

    @Test
    void nonWhitelistedVariableRejected() {
        assertFalse(evaluator.evaluate("#this.output != null", "x"));
        assertFalse(evaluator.evaluate("#root != null", "x"));
        assertFalse(evaluator.evaluate("#systemProperties['user.dir'] != null", "x"));
    }

    @Test
    void malformedExpressionFailsClosed() {
        assertFalse(evaluator.evaluate("#output.contains('未闭合", "x"));
        assertFalse(evaluator.evaluate("%%%garbage%%%", "x"));
    }

    @Test
    void nullOutputHandledSafely() {
        assertTrue(evaluator.evaluate("#output.isBlank()", null));
        assertFalse(evaluator.evaluate("#output.contains('x')", null));
    }

    @Test
    void whitelistedContentInsideStringLiteralNotFlagged() {
        // 字面量内的 '.xxx(' 不应被结构校验误判为代码
        assertTrue(evaluator.evaluate("#output.contains('调用 T(java.lang.Runtime) 之类的文本')", "文本: 调用 T(java.lang.Runtime) 之类的文本"));
    }
}
