package cn.zcj.aether.infrastructure.classifier;

import cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError;
import cn.zcj.aether.domain.agent.service.model.failover.FailoverReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DefaultModelErrorClassifier 单元测试 — P0-2 重试收口
 *
 * 验证：原 ModelInvoker.isRetryable 的 mimo 400 特判迁移至分类器——
 * 小觅（mimo）400 → UNKNOWN（可重试，走 Resilient 层抖动退避）；
 * 其余 Provider 400 → FORMAT_ERROR（终止，不重试）。
 */
class DefaultModelErrorClassifierTest {

    private final DefaultModelErrorClassifier classifier = new DefaultModelErrorClassifier();

    @Test
    void mimo400IsRetryableUnknown() {
        ClassifiedError e = classifier.classify(
                new RuntimeException("HTTP 400 Bad Request"), "openai", "mimo-v2.5-pro");

        assertEquals(FailoverReason.UNKNOWN, e.reason(),
                "mimo 400 应映射为 UNKNOWN 以便 Resilient 层抖动退避重试");
    }

    @Test
    void nonMimo400IsFormatError() {
        ClassifiedError e = classifier.classify(
                new RuntimeException("HTTP 400 Bad Request"), "openai", "gpt-4o");

        assertEquals(FailoverReason.FORMAT_ERROR, e.reason(),
                "非 mimo Provider 的 400 是真实客户端错误，不应重试");
    }

    @Test
    void timeoutIsClassifiedAsTimeout() {
        ClassifiedError e = classifier.classify(
                new java.util.concurrent.TimeoutException("read timed out"), "openai", "gpt-4o");

        assertEquals(FailoverReason.TIMEOUT, e.reason());
    }
}
