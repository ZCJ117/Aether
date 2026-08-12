# Aether 编排 hermes 对齐改造 — Batch 1（D2 异常处理与重试）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Aether 模型调用容错从"内联计数重试循环"升级为"turn 级恢复分支账本驱动"（对齐 hermes `turn_retry_state.py`），补齐凭据轮换与自适应限流退避。

**Architecture:** `TurnRetryState` 作为每 turn 决策中心，消费 `ModelErrorClassifier` 的分类结果（`ClassifiedError` 五向 hint），输出 `RecoveryDirective`；`ResilientChatModelExecutor` 作为执行循环按指令执行退避/压缩/轮换/fallback/终止。新增 `RetryBackoff`（自适应限流退避表，对齐 hermes `retry_utils.py`）与 `CredentialPool`（凭据轮换端口）。

**Tech Stack:** Java 17 / Spring Boot / Spring AI ChatModel / JUnit 5 / Mockito / Maven（`-am` 连带构建兄弟模块）

**设计依据:** `docs/superpowers/specs/2026-08-12-aether-orchestration-hermes-alignment-design.md` §4（已批准）

---

## 文件结构

```
aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/
├── ClassifiedError.java            [修改] 新增 shouldRotateCredential / shouldStripThinking 两个 hint
├── RecoveryBranch.java             [新建] 恢复分支枚举
├── RecoveryDirective.java          [新建] 决策输出 record
├── RetryBackoff.java               [新建] 抖动退避 + 自适应限流退避表
├── TurnRetryState.java             [新建] 每 turn 恢复分支账本（核心决策）
├── CredentialPool.java             [新建] 凭据轮换端口（domain 接口）
└── ResilientChatModelExecutor.java [修改] call() 委托 TurnRetryState，新增凭据轮换执行

aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/
└── RotatingCredentialPool.java     [新建] 按 provider 轮询凭据的实现

aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/
├── ClassifiedErrorTest.java        [新建]
├── RecoveryDirectiveTest.java      [新建]
├── RetryBackoffTest.java           [新建]
├── TurnRetryStateTest.java         [新建]
└── ResilientChatModelExecutorTest.java [新建]

aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/
└── RotatingCredentialPoolTest.java [新建]
```

**测试命令（已验证可运行）：**
- 单测（domain）：`mvn -pl aether-domain -am test -Dtest=<Test类名> -Dsurefire.failIfNoSpecifiedTests=false`
- 单测（infrastructure）：`mvn -pl aether-infrastructure -am test -Dtest=<Test类名> -Dsurefire.failIfNoSpecifiedTests=false`
- 全量回归：`mvn -pl aether-domain -am test`

> ⚠️ 必须带 `-Dsurefire.failIfNoSpecifiedTests=false`：`-am` 会连带构建 `aether-types` 等无匹配测试的模块，否则报 "No tests matching pattern"。

---

### Task 1: ClassifiedError 增加 shouldRotateCredential / shouldStripThinking

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/ClassifiedError.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ClassifiedErrorTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ClassifiedErrorTest.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifiedErrorTest {

    @Test
    void authTransientRotatesCredential() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401 unauthorized");
        assertTrue(e.retryable());
        assertTrue(e.shouldRotateCredential());
    }

    @Test
    void billingRotatesCredentialAndFallsBack() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.BILLING, 402, "openai", "gpt-4o", "quota exceeded");
        assertTrue(e.shouldRotateCredential());
        assertTrue(e.shouldFallback());
    }

    @Test
    void contentPolicyNotRetryableNoRotation() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.CONTENT_POLICY_BLOCKED, 400, "anthropic", "claude-sonnet", "blocked");
        assertFalse(e.retryable());
        assertFalse(e.shouldRotateCredential());
    }

    @Test
    void contextOverflowCompressesNoRotation() {
        ClassifiedError e = ClassifiedError.of(
                FailoverReason.CONTEXT_OVERFLOW, null, "anthropic", "claude-sonnet", "context too long");
        assertTrue(e.shouldCompress());
        assertFalse(e.shouldRotateCredential());
        assertFalse(e.shouldStripThinking());
    }

    @Test
    void unknownIsConservative() {
        ClassifiedError e = ClassifiedError.unknown("openai", "gpt-4o", "weird error");
        assertTrue(e.retryable());
        assertFalse(e.shouldCompress());
        assertFalse(e.shouldRotateCredential());
        assertFalse(e.shouldStripThinking());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=ClassifiedErrorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: method shouldRotateCredential()`

- [ ] **Step 3: 最小实现 — 修改 record**

在 `ClassifiedError.java` 的 record 组件列表末尾（`boolean shouldFallback` 之后）追加两个组件，并更新 `of()`/`unknown()`：

```java
        /** 是否应轮换凭据（AUTH_TRANSIENT / BILLING） */
        boolean shouldRotateCredential,

        /** 是否应剥离 thinking 签名后重试（Anthropic thinking 格式错，预留） */
        boolean shouldStripThinking
) {

    /**
     * 创建带默认动作提示的分类结果。
     * 根据 reason 自动填充 retryable/shouldCompress/shouldFallback/shouldRotateCredential。
     */
    public static ClassifiedError of(FailoverReason reason, Integer statusCode,
                                     String provider, String model, String detail) {
        boolean retryable = switch (reason) {
            case AUTH_PERMANENT, CONTENT_POLICY_BLOCKED -> false;
            case SSL_CERT -> false;
            default -> true;
        };
        boolean shouldCompress = reason == FailoverReason.CONTEXT_OVERFLOW
                || reason == FailoverReason.PAYLOAD_TOO_LARGE;
        boolean shouldFallback = reason == FailoverReason.MODEL_NOT_FOUND
                || reason == FailoverReason.UPSTREAM_RATE_LIMIT
                || reason == FailoverReason.BILLING
                || reason == FailoverReason.AUTH_TRANSIENT;
        boolean shouldRotateCredential = reason == FailoverReason.AUTH_TRANSIENT
                || reason == FailoverReason.BILLING;
        return new ClassifiedError(reason, statusCode, provider, model, detail,
                retryable, shouldCompress, shouldFallback, shouldRotateCredential, false);
    }

    /**
     * 创建 UNKNOWN 兜底分类（保守策略：可重试）。
     */
    public static ClassifiedError unknown(String provider, String model, String detail) {
        return new ClassifiedError(FailoverReason.UNKNOWN, null, provider, model, detail,
                true, false, false, false, false);
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=ClassifiedErrorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/ClassifiedError.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ClassifiedErrorTest.java
git commit -m "feat(failover): ClassifiedError 增加 shouldRotateCredential/shouldStripThinking hint"
```

---

### Task 2: RecoveryBranch 枚举 + RecoveryDirective 决策输出

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryBranch.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryDirective.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryDirectiveTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryDirectiveTest.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryDirectiveTest {

    @Test
    void terminateDirective() {
        RecoveryDirective d = RecoveryDirective.terminate("exhausted");
        assertEquals(RecoveryBranch.TERMINATE, d.branch());
        assertFalse(d.shouldRetry());
        assertFalse(d.rotateCredential());
        assertEquals("exhausted", d.reason());
    }

    @Test
    void retryDirectiveCarriesBackoff() {
        RecoveryDirective d = RecoveryDirective.retry(RecoveryBranch.JITTERED_BACKOFF, 2.5, "retry");
        assertEquals(RecoveryBranch.JITTERED_BACKOFF, d.branch());
        assertTrue(d.shouldRetry());
        assertEquals(2.5, d.backoffSec(), 0.001);
    }

    @Test
    void rotateCredentialDirective() {
        RecoveryDirective d = RecoveryDirective.rotateCredential("auth");
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, d.branch());
        assertTrue(d.rotateCredential());
        assertFalse(d.compress());
        assertFalse(d.fallback());
    }

    @Test
    void compressDirective() {
        RecoveryDirective d = RecoveryDirective.compress("overflow");
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, d.branch());
        assertTrue(d.compress());
    }

    @Test
    void fallbackDirective() {
        RecoveryDirective d = RecoveryDirective.fallback("upstream");
        assertEquals(RecoveryBranch.PROVIDER_FALLBACK, d.branch());
        assertTrue(d.fallback());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=RecoveryDirectiveTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: class RecoveryBranch / RecoveryDirective`

- [ ] **Step 3: 最小实现**

创建 `RecoveryBranch.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 恢复分支枚举 — 对齐 hermes turn_retry_state.py 的恢复动作清单。
 * TurnRetryState 据此去重并限制每个分支在单 turn 内的尝试次数。
 */
public enum RecoveryBranch {

    /** 轮换 API 凭据后重试（AUTH_TRANSIENT / BILLING） */
    CREDENTIAL_ROTATION,

    /** 切换 fallback 模型（上游限流 / 模型不存在 / 其它分支耗尽） */
    PROVIDER_FALLBACK,

    /** 自适应限流退避（429 / 过载，30/60/90/120s 表） */
    ADAPTIVE_RATE_LIMIT_BACKOFF,

    /** 抖动指数退避（服务端错误 / 超时 / 未知） */
    JITTERED_BACKOFF,

    /** 上下文压缩后重试（上下文溢出 / 载荷过大） */
    CONTEXT_COMPRESSION,

    /** 重建连接后重试（超时，预留） */
    TIMEOUT_RECONNECT,

    /** 终止：所有恢复策略耗尽或错误不可恢复 */
    TERMINATE
}
```

创建 `RecoveryDirective.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

/**
 * 恢复指令 — TurnRetryState 的决策输出。
 * ResilientChatModelExecutor 按 branch 执行对应恢复动作。
 */
public record RecoveryDirective(
        /** 恢复分支 */
        RecoveryBranch branch,

        /** 是否应立即退避后重试 */
        boolean shouldRetry,

        /** 退避秒数（仅 shouldRetry 时有意义） */
        double backoffSec,

        /** 是否应轮换凭据 */
        boolean rotateCredential,

        /** 是否应压缩上下文 */
        boolean compress,

        /** 是否应切换 fallback 模型 */
        boolean fallback,

        /** 人类可读的决策原因 */
        String reason
) {

    public static RecoveryDirective terminate(String reason) {
        return new RecoveryDirective(RecoveryBranch.TERMINATE, false, 0, false, false, false, reason);
    }

    public static RecoveryDirective retry(RecoveryBranch branch, double backoffSec, String reason) {
        return new RecoveryDirective(branch, true, backoffSec, false, false, false, reason);
    }

    public static RecoveryDirective rotateCredential(String reason) {
        return new RecoveryDirective(RecoveryBranch.CREDENTIAL_ROTATION, true, 0, true, false, false, reason);
    }

    public static RecoveryDirective compress(String reason) {
        return new RecoveryDirective(RecoveryBranch.CONTEXT_COMPRESSION, true, 0, false, true, false, reason);
    }

    public static RecoveryDirective fallback(String reason) {
        return new RecoveryDirective(RecoveryBranch.PROVIDER_FALLBACK, true, 0, false, false, true, reason);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=RecoveryDirectiveTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryBranch.java aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryDirective.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RecoveryDirectiveTest.java
git commit -m "feat(failover): 新增 RecoveryBranch 枚举与 RecoveryDirective 决策输出"
```

---

### Task 3: RetryBackoff 退避工具（自适应限流表）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RetryBackoff.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RetryBackoffTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RetryBackoffTest.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {

    @Test
    void adaptiveRateLimitTable() {
        assertEquals(30.0, RetryBackoff.adaptiveRateLimitBackoff(1), 0.001);
        assertEquals(60.0, RetryBackoff.adaptiveRateLimitBackoff(2), 0.001);
        assertEquals(90.0, RetryBackoff.adaptiveRateLimitBackoff(3), 0.001);
        assertEquals(120.0, RetryBackoff.adaptiveRateLimitBackoff(4), 0.001);
        assertEquals(120.0, RetryBackoff.adaptiveRateLimitBackoff(99), 0.001);
    }

    @Test
    void jitteredBackoffWithinBounds() {
        // base=2, jitter∈[0, 0.5*delay] → 结果 ∈ [delay, 1.5*delay]
        // attempt1: delay=2 → [2,3]; attempt2: 4 → [4,6]; attempt3: 8 → [8,12]; attempt4: min(16,30) → [16,24]
        assertBounds(1, 2.0, 3.0);
        assertBounds(2, 4.0, 6.0);
        assertBounds(3, 8.0, 12.0);
        assertBounds(4, 16.0, 24.0);
    }

    private static void assertBounds(int attempt, double lo, double hi) {
        for (int i = 0; i < 200; i++) {
            double d = RetryBackoff.jitteredBackoff(attempt);
            assertTrue(d >= lo - 0.0001, "attempt " + attempt + " below lo: " + d);
            assertTrue(d <= hi + 0.0001, "attempt " + attempt + " above hi: " + d);
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=RetryBackoffTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: class RetryBackoff`

- [ ] **Step 3: 最小实现**

创建 `RetryBackoff.java`（数值与现有 `ResilientChatModelExecutor.jitteredBackoff` 完全一致，仅上移为独立工具类）：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 退避计算工具 — 对齐 hermes retry_utils.py。
 * jittered_backoff（抖动指数退避）+ adaptive_rate_limit_backoff（自适应限流表）。
 */
public final class RetryBackoff {

    /** 对齐 hermes retry_utils.py: base=2s, max=30s, jitter_ratio=0.5 */
    private static final double BASE_DELAY_SEC = 2.0;
    private static final double MAX_DELAY_SEC = 30.0;
    private static final double JITTER_RATIO = 0.5;

    /** 线程安全计数器（对齐 hermes retry_utils.py _jitter_counter） */
    private static final AtomicInteger jitterCounter = new AtomicInteger(0);

    private RetryBackoff() {
    }

    /**
     * 抖动指数退避：delay = min(base * 2^(attempt-1), max) + [0, 0.5*delay]。
     */
    public static double jitteredBackoff(int attempt) {
        int tick = jitterCounter.incrementAndGet();
        int exponent = Math.max(0, attempt - 1);

        double delay;
        if (exponent >= 63 || BASE_DELAY_SEC <= 0) {
            delay = MAX_DELAY_SEC;
        } else {
            delay = Math.min(BASE_DELAY_SEC * Math.pow(2, exponent), MAX_DELAY_SEC);
        }

        double jitter = ThreadLocalRandom.current().nextDouble(0, JITTER_RATIO * delay);
        return delay + jitter;
    }

    /**
     * 自适应限流退避表：{1:30s, 2:60s, 3:90s, >=4:120s} — 对齐 hermes retry_utils.py L108。
     * 用于 RATE_LIMIT / OVERLOADED，比通用指数退避更陡峭。
     */
    public static double adaptiveRateLimitBackoff(int attempt) {
        return switch (attempt) {
            case 1 -> 30.0;
            case 2 -> 60.0;
            case 3 -> 90.0;
            default -> 120.0;
        };
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=RetryBackoffTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/RetryBackoff.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/RetryBackoffTest.java
git commit -m "feat(failover): 新增 RetryBackoff 自适应限流退避表（对齐 hermes retry_utils）"
```

---

### Task 4: TurnRetryState 恢复分支账本（核心决策）

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/TurnRetryState.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/TurnRetryStateTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/TurnRetryStateTest.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRetryStateTest {

    private static final ClassifiedError AUTH_401 = ClassifiedError.of(
            FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401 unauthorized");
    private static final ClassifiedError RATE_429 = ClassifiedError.of(
            FailoverReason.RATE_LIMIT, 429, "openai", "gpt-4o", "rate limited");
    private static final ClassifiedError OVERFLOW = ClassifiedError.of(
            FailoverReason.CONTEXT_OVERFLOW, null, "anthropic", "claude-sonnet", "context too long");

    @Test
    void credentialRotationAttemptedOnceThenFallback() {
        TurnRetryState st = new TurnRetryState(3, 2);
        RecoveryDirective d1 = st.nextDirective(AUTH_401);
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, d1.branch());
        assertTrue(d1.rotateCredential());
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);

        RecoveryDirective d2 = st.nextDirective(AUTH_401);
        assertEquals(RecoveryBranch.PROVIDER_FALLBACK, d2.branch());
        assertTrue(d2.fallback());
    }

    @Test
    void compressionLimitedToTwo() {
        TurnRetryState st = new TurnRetryState(3, 2);
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, st.nextDirective(OVERFLOW).branch());
        st.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
        assertEquals(RecoveryBranch.CONTEXT_COMPRESSION, st.nextDirective(OVERFLOW).branch());
        st.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);

        RecoveryDirective d3 = st.nextDirective(OVERFLOW);
        assertNotEquals(RecoveryBranch.CONTEXT_COMPRESSION, d3.branch());
    }

    @Test
    void adaptiveBackoffUsesTable() {
        TurnRetryState st = new TurnRetryState(3, 1);
        RecoveryDirective d1 = st.nextDirective(RATE_429);
        assertEquals(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF, d1.branch());
        assertEquals(30.0, d1.backoffSec(), 0.001);
        st.markAttempted(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF);

        RecoveryDirective d2 = st.nextDirective(RATE_429);
        assertEquals(60.0, d2.backoffSec(), 0.001);
    }

    @Test
    void nonRecoverableTerminates() {
        TurnRetryState st = new TurnRetryState(3, 2);
        ClassifiedError cpb = ClassifiedError.of(
                FailoverReason.CONTENT_POLICY_BLOCKED, 400, "anthropic", "claude-sonnet", "blocked");
        assertEquals(RecoveryBranch.TERMINATE, st.nextDirective(cpb).branch());
    }

    @Test
    void exhaustedBranchesTerminate() {
        TurnRetryState st = new TurnRetryState(1, 0); // fallback 链为空
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION); // 轮换已用
        assertEquals(RecoveryBranch.TERMINATE, st.nextDirective(AUTH_401).branch());
    }

    @Test
    void resetClearsLedger() {
        TurnRetryState st = new TurnRetryState(3, 2);
        st.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
        st.reset();
        assertEquals(RecoveryBranch.CREDENTIAL_ROTATION, st.nextDirective(AUTH_401).branch());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=TurnRetryStateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: class TurnRetryState`

- [ ] **Step 3: 最小实现**

创建 `TurnRetryState.java`（对齐 hermes `turn_retry_state.py`：每 turn 账本 + 去重 + 限次）：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import java.util.HashMap;
import java.util.Map;

/**
 * Turn 级恢复分支账本 — 对齐 hermes turn_retry_state.py。
 *
 * <p>每个模型调用（call）视为一个 turn，持有一个 TurnRetryState 实例。
 * 它消费 {@link ModelErrorClassifier} 的分类结果，结合"已尝试分支"账本
 * 去重并限次，输出下一个恢复动作 {@link RecoveryDirective}。</p>
 *
 * <p>分支限次：CREDENTIAL_ROTATION=1、CONTEXT_COMPRESSION=2、
 * ADAPTIVE_RATE_LIMIT_BACKOFF / JITTERED_BACKOFF=maxAttempts、
 * PROVIDER_FALLBACK=fallback 链长度。</p>
 */
public class TurnRetryState {

    private static final int MAX_CREDENTIAL_ROTATIONS = 1;
    private static final int MAX_COMPRESSION_ATTEMPTS = 2;

    /** 通用退避最大次数（来自 ModelConfig.maxAttempts） */
    private final int maxAttempts;

    /** fallback 链长度（决定 PROVIDER_FALLBACK 上限） */
    private final int fallbackChainSize;

    /** 分支 → 已尝试次数 */
    private final Map<RecoveryBranch, Integer> attemptCount = new HashMap<>();

    public TurnRetryState(int maxAttempts, int fallbackChainSize) {
        this.maxAttempts = maxAttempts;
        this.fallbackChainSize = fallbackChainSize;
    }

    /**
     * 根据分类结果与当前账本，输出下一个恢复指令。
     */
    public RecoveryDirective nextDirective(ClassifiedError e) {
        FailoverReason r = e.reason();

        // ── 确定性终止 ──
        if (r == FailoverReason.AUTH_PERMANENT
                || r == FailoverReason.CONTENT_POLICY_BLOCKED
                || r == FailoverReason.SSL_CERT) {
            return RecoveryDirective.terminate("不可恢复错误: " + r);
        }

        // ── 上下文溢出 → 压缩（上限 2 次）→ 退化 fallback/终止 ──
        if (r == FailoverReason.CONTEXT_OVERFLOW || r == FailoverReason.PAYLOAD_TOO_LARGE) {
            if (count(RecoveryBranch.CONTEXT_COMPRESSION) < MAX_COMPRESSION_ATTEMPTS) {
                return RecoveryDirective.compress("上下文溢出: " + r);
            }
            return exhaustedBranch(RecoveryBranch.CONTEXT_COMPRESSION, e);
        }

        // ── 认证/计费 → 轮换凭据（1 次）→ fallback → 终止 ──
        if (r == FailoverReason.AUTH_TRANSIENT || r == FailoverReason.BILLING) {
            if (count(RecoveryBranch.CREDENTIAL_ROTATION) < MAX_CREDENTIAL_ROTATIONS) {
                return RecoveryDirective.rotateCredential("认证/计费: " + r);
            }
            return exhaustedBranch(RecoveryBranch.CREDENTIAL_ROTATION, e);
        }

        // ── 限流/过载 → 自适应限流退避（30/60/90/120s）→ fallback/终止 ──
        if (r == FailoverReason.RATE_LIMIT || r == FailoverReason.OVERLOADED) {
            int attempt = count(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF) + 1;
            if (attempt <= maxAttempts) {
                return RecoveryDirective.retry(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF,
                        RetryBackoff.adaptiveRateLimitBackoff(attempt), "限流/过载: " + r);
            }
            return exhaustedBranch(RecoveryBranch.ADAPTIVE_RATE_LIMIT_BACKOFF, e);
        }

        // ── 上游限流 / 模型不存在 → 直接 fallback ──
        if (r == FailoverReason.UPSTREAM_RATE_LIMIT || r == FailoverReason.MODEL_NOT_FOUND) {
            return exhaustedBranch(RecoveryBranch.PROVIDER_FALLBACK, e);
        }

        // ── 服务端错误 / 超时 / 未知 → 抖动退避 → fallback/终止 ──
        if (r == FailoverReason.SERVER_ERROR || r == FailoverReason.TIMEOUT || r == FailoverReason.UNKNOWN) {
            int attempt = count(RecoveryBranch.JITTERED_BACKOFF) + 1;
            if (attempt <= maxAttempts) {
                return RecoveryDirective.retry(RecoveryBranch.JITTERED_BACKOFF,
                        RetryBackoff.jitteredBackoff(attempt), "可重试: " + r);
            }
            return exhaustedBranch(RecoveryBranch.JITTERED_BACKOFF, e);
        }

        // ── 其它（FORMAT_ERROR 等）→ 终止 ──
        return RecoveryDirective.terminate("无恢复分支: " + r);
    }

    /**
     * 分支尝试耗尽后的退化路径：若 fallback 链未用尽则切 fallback，否则终止。
     */
    private RecoveryDirective exhaustedBranch(RecoveryBranch branch, ClassifiedError e) {
        if (count(RecoveryBranch.PROVIDER_FALLBACK) < fallbackChainSize) {
            return RecoveryDirective.fallback("分支耗尽(" + branch + ") → fallback");
        }
        return RecoveryDirective.terminate("所有恢复分支耗尽: " + e.reason());
    }

    /** 记录一次分支尝试（由执行器在成功执行恢复动作后调用） */
    public void markAttempted(RecoveryBranch branch) {
        attemptCount.merge(branch, 1, Integer::sum);
    }

    /** 查询某分支已尝试次数 */
    public int count(RecoveryBranch branch) {
        return attemptCount.getOrDefault(branch, 0);
    }

    /** 清空账本（fallback 切换成功或整个 turn 成功时调用） */
    public void reset() {
        attemptCount.clear();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=TurnRetryStateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（6 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/TurnRetryState.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/TurnRetryStateTest.java
git commit -m "feat(failover): 新增 TurnRetryState turn 级恢复分支账本（对齐 hermes turn_retry_state）"
```

---

### Task 5: CredentialPool 接口 + RotatingCredentialPool 实现

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPool.java`
- Create: `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPool.java`
- Test: `aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPoolTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPoolTest.java`：

```java
package cn.zcj.aether.infrastructure.credential;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotatingCredentialPoolTest {

    @Test
    void rotatesToNextCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.register("openai", List.of(
                new RotatingCredentialPool.CredentialEntry("key1", "https://a", null),
                new RotatingCredentialPool.CredentialEntry("key2", "https://b", null)));

        ModelConfig current = ModelConfig.builder()
                .modelId("gpt-4o").apiKey("key1").baseUrl("https://a").build();

        ModelConfig next = pool.rotate(current, "openai").orElseThrow();
        assertEquals("key2", next.getApiKey());
        assertEquals("gpt-4o", next.getModelId());
        assertEquals("https://b", next.getBaseUrl());
    }

    @Test
    void emptyWhenOnlyOneCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.register("openai", List.of(
                new RotatingCredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenKeyUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.register("openai", List.of(
                new RotatingCredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("unknown").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenProviderUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "unknown-provider").isEmpty());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-infrastructure -am test -Dtest=RotatingCredentialPoolTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: class RotatingCredentialPool`

- [ ] **Step 3: 最小实现**

创建 `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPool.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;

import java.util.Optional;

/**
 * 凭据轮换端口 — 对齐 hermes agent_runtime_helpers.py recover_with_credential_pool。
 *
 * <p>当错误分类标记 shouldRotateCredential（AUTH_TRANSIENT / BILLING）时，
 * ResilientChatModelExecutor 调用本接口获取下一个可用凭据并重建 ChatModel。</p>
 */
public interface CredentialPool {

    /**
     * 为当前模型配置返回下一组可用凭据。
     *
     * @param current 当前模型配置（含当前 apiKey）
     * @param provider 当前 Provider 名称
     * @return 轮换后的配置；无可用备选凭据时返回 empty
     */
    Optional<ModelConfig> rotate(ModelConfig current, String provider);
}
```

创建 `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPool.java`：

```java
package cn.zcj.aether.infrastructure.credential;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.failover.CredentialPool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于轮询的凭据池 — 按 provider 维护一组凭据，轮换时返回下一个。
 * 语义对齐 hermes child_pool.acquire_lease + recover_with_credential_pool：
 * 每个凭据在同一时刻被唯一使用，轮换后旧凭据不会立即被复用。
 */
@Component
public class RotatingCredentialPool implements CredentialPool {

    /** 一组备用凭据 */
    public record CredentialEntry(String apiKey, String baseUrl, String completionsPath) {
    }

    private final Map<String, List<CredentialEntry>> credentials = new HashMap<>();

    /** 注册某 provider 的凭据组（至少 2 组才可轮换） */
    public void register(String provider, List<CredentialEntry> entries) {
        credentials.put(provider, new ArrayList<>(entries));
    }

    @Override
    public Optional<ModelConfig> rotate(ModelConfig current, String provider) {
        List<CredentialEntry> list = credentials.get(provider);
        if (list == null || list.size() < 2) {
            return Optional.empty(); // 无备选凭据，无法轮换
        }
        int idx = indexOf(current.getApiKey(), list);
        if (idx < 0) {
            return Optional.empty(); // 当前凭据不在池中（如 fallback 模型的 key），无法轮换
        }
        CredentialEntry next = list.get((idx + 1) % list.size());
        ModelConfig rotated = ModelConfig.builder()
                .modelId(current.getModelId())
                .baseUrl(next.baseUrl() != null ? next.baseUrl() : current.getBaseUrl())
                .apiKey(next.apiKey())
                .completionsPath(next.completionsPath() != null ? next.completionsPath() : current.getCompletionsPath())
                .maxAttempts(current.getMaxAttempts())
                .build();
        return Optional.of(rotated);
    }

    private static int indexOf(String apiKey, List<CredentialEntry> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).apiKey().equals(apiKey)) {
                return i;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-infrastructure -am test -Dtest=RotatingCredentialPoolTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPool.java aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPool.java aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPoolTest.java
git commit -m "feat(failover): CredentialPool 端口 + RotatingCredentialPool 凭据轮换实现"
```

---

### Task 6: ResilientChatModelExecutor 委托 TurnRetryState

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutor.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutorTest.java`

- [ ] **Step 1: 写失败测试**

创建 `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutorTest.java`：

```java
package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientChatModelExecutorTest {

    private ChatModel chatModel;
    private ModelProvider provider;
    private ModelProviderRegistry registry;
    private ModelErrorClassifier classifier;

    private static ChatResponse ok() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
    }

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        provider = mock(ModelProvider.class);
        registry = mock(ModelProviderRegistry.class);
        classifier = mock(ModelErrorClassifier.class);
        when(provider.providerName()).thenReturn("openai");
        when(provider.createChatModel(any())).thenReturn(chatModel);
    }

    private ResilientChatModelExecutor build(ModelConfig cfg, List<ModelRoute> chain) {
        return new ResilientChatModelExecutor(chatModel, cfg, provider, registry, classifier, chain);
    }

    @Test
    void successNoRetry() {
        when(chatModel.call(any(Prompt.class))).thenReturn(ok());
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();

        ChatResponse resp = build(cfg, List.of()).call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getContent());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void rateLimitAdaptiveBackoffThenSuccess() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("rate limited"))
                .thenReturn(ok());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.RATE_LIMIT, 429, "openai", "gpt-4o", "rate"));
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();

        ResilientChatModelExecutor executor = build(cfg, List.of());
        executor.setBackoffWaiter(sec -> { }); // 测试中跳过真实退避等待
        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getContent());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void authTransientRotatesCredentialThenSuccess() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("401 unauthorized"))
                .thenReturn(ok());
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.AUTH_TRANSIENT, 401, "openai", "gpt-4o", "401"));

        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.register("openai", List.of(
                new RotatingCredentialPool.CredentialEntry("key1", "https://a", null),
                new RotatingCredentialPool.CredentialEntry("key2", "https://b", null)));
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").baseUrl("https://a").build();

        ResilientChatModelExecutor executor = build(cfg, List.of());
        executor.setCredentialPool(pool);
        ChatResponse resp = executor.call(new Prompt("hi"));

        assertEquals("ok", resp.getResult().getOutput().getContent());
        ArgumentCaptor<ModelConfig> cap = ArgumentCaptor.forClass(ModelConfig.class);
        verify(provider, times(2)).createChatModel(cap.capture());
        assertEquals("key2", cap.getAllValues().get(1).getApiKey());
    }

    @Test
    void exhaustedBackoffThenFallbackThenTerminate() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("server down"));
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.SERVER_ERROR, 500, "openai", "gpt-4o", "server"));
        // fallback 路由指向另一个 provider，其 ChatModel 也抛错
        ModelProvider fbProvider = mock(ModelProvider.class);
        when(fbProvider.providerName()).thenReturn("anthropic");
        ChatModel fbModel = mock(ChatModel.class);
        when(fbModel.call(any(Prompt.class))).thenThrow(new RuntimeException("also down"));
        when(fbProvider.createChatModel(any())).thenReturn(fbModel);
        when(registry.resolve("claude-sonnet")).thenReturn(fbProvider);

        ModelRoute fb = ModelRoute.builder().modelId("claude-sonnet").provider("anthropic").build();
        // maxAttempts=1：抖动退避 1 次 → fallback 1 次 → 终止
        ModelConfig cfg = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").maxAttempts(1).build();

        ResilientChatModelExecutor executor = build(cfg, List.of(fb));
        executor.setBackoffWaiter(sec -> { }); // 测试中跳过真实退避等待

        assertThrows(ResilientChatModelExecutor.ResilientCallException.class,
                () -> executor.call(new Prompt("hi")));
    }
}
```

> 说明：`ResilientChatModelExecutor` 位于 `aether-infrastructure` 的调用方（`ChatModelNode`）用 Spring 装配；本测试直接用构造函数构造，`credentialPool` 通过新增的 setter 注入。`ArgumentCaptor` 断言轮换后的 `ModelConfig.apiKey == "key2"`，证明凭据轮换确实重建了 ChatModel。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl aether-domain -am test -Dtest=ResilientChatModelExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 编译错误 `cannot find symbol: method setCredentialPool(CredentialPool)`

- [ ] **Step 3: 最小实现 — 改造执行器**

在 `ResilientChatModelExecutor.java` 中：

**(a) 调整 import**：删除 `java.util.concurrent.ThreadLocalRandom` 与 `java.util.concurrent.atomic.AtomicInteger`，新增 `java.util.Optional`：

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
```

**(b) 新增字段与 setter**（放在 `compressCallback` 字段之后）：

```java
    // ── 凭据轮换池（可选，未注入则不轮换）──
    /** 凭据轮换池（对齐 hermes recover_with_credential_pool） */
    private CredentialPool credentialPool;

    /**
     * 注入凭据轮换池。未注入时 AUTH_TRANSIENT/BILLING 走 fallback 而非轮换。
     */
    public void setCredentialPool(CredentialPool pool) {
        this.credentialPool = pool;
    }

    // ── 退避等待器（默认真实 sleep；测试可替换为 no-op 以跳过退避等待）──
    /** 退避等待器：输入退避秒数并阻塞等待。默认真实 sleep，测试可替换。 */
    private java.util.function.Consumer<Double> backoffWaiter =
            sec -> sleepMs((long) (sec * 1000));

    /**
     * 替换退避等待器（仅测试用，避免真实 sleep 拖慢用例）。
     */
    void setBackoffWaiter(java.util.function.Consumer<Double> waiter) {
        this.backoffWaiter = waiter;
    }
```

**(c) 删除静态字段 `jitterCounter` 与静态方法 `jitteredBackoff`**（已上移为 `RetryBackoff.jitteredBackoff`）。

**(d) 重写 `call(Prompt)` 方法**：

```java
    @Override
    public ChatResponse call(Prompt prompt) {
        int maxAttempts = currentModelConfig.getMaxAttempts() != null
                ? currentModelConfig.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
        // 每次调用 = 一个 turn，持有一个恢复分支账本
        TurnRetryState turnRetry = new TurnRetryState(maxAttempts, fallbackChain.size());

        while (true) {
            try {
                ChatResponse response = currentChatModel.call(prompt);
                // 成功 — 恢复 fallback 状态标记
                if (fallbackActivated && !isInFallbackCooldown()) {
                    log.info("Fallback 模型调用成功，下一轮将尝试恢复主模型");
                    fallbackActivated = false;
                    setStateAttr("resilient:fallbackActivated", Boolean.FALSE);
                }
                return response;

            } catch (Exception e) {
                ClassifiedError classified = classifyError(e);

                log.warn("模型调用失败 provider={} model={} reason={} status={}",
                        currentProvider.providerName(), currentModelConfig.getModelId(),
                        classified.reason(), classified.statusCode());

                RecoveryDirective d = turnRetry.nextDirective(classified);
                log.info("恢复指令: branch={} reason={}", d.branch(), d.reason());

                switch (d.branch()) {
                    case JITTERED_BACKOFF, ADAPTIVE_RATE_LIMIT_BACKOFF -> {
                        turnRetry.markAttempted(d.branch());
                        backoffWaiter.accept(d.backoffSec());
                    }
                    case CONTEXT_COMPRESSION -> {
                        turnRetry.markAttempted(RecoveryBranch.CONTEXT_COMPRESSION);
                        tryCompress(classified);
                    }
                    case CREDENTIAL_ROTATION -> {
                        turnRetry.markAttempted(RecoveryBranch.CREDENTIAL_ROTATION);
                        tryRotateCredential(classified);
                    }
                    case PROVIDER_FALLBACK -> {
                        turnRetry.markAttempted(RecoveryBranch.PROVIDER_FALLBACK);
                        if (tryActivateFallback(classified.reason())) {
                            turnRetry.reset(); // fallback 切换成功，重置本轮账本
                        }
                    }
                    case TIMEOUT_RECONNECT -> {
                        turnRetry.markAttempted(RecoveryBranch.TIMEOUT_RECONNECT);
                        backoffWaiter.accept(1.0); // 固定 1s 重建连接等待
                    }
                    case TERMINATE -> throw new ResilientCallException(
                            "所有恢复策略耗尽: " + classified.reason(), classified, e);
                }
            }
        }
    }
```

**(e) 新增两个私有辅助方法**（放在 `classifyError` 之后）：

```java
    // ── 恢复动作执行 ──

    /**
     * 执行上下文压缩。失败仅记录，不阻断（下一轮指令会退化到 fallback/终止）。
     */
    private void tryCompress(ClassifiedError classified) {
        if (compressCallback == null) {
            log.debug("无压缩回调，跳过压缩");
            return;
        }
        try {
            if (compressCallback.compress(agentState, classified.reason())) {
                log.info("上下文压缩完成，重试模型调用");
            }
        } catch (Exception ce) {
            log.warn("上下文压缩失败: {}", ce.getMessage());
        }
    }

    /**
     * 执行凭据轮换。未注入池或池中无备选时，跳过（下一轮指令退化为 fallback）。
     */
    private void tryRotateCredential(ClassifiedError classified) {
        if (credentialPool == null) {
            log.debug("未注入凭据池，跳过轮换");
            return;
        }
        try {
            Optional<ModelConfig> rotated =
                    credentialPool.rotate(currentModelConfig, currentProvider.providerName());
            if (rotated.isEmpty()) {
                log.warn("凭据池无可用备选，跳过轮换");
                return;
            }
            this.currentChatModel = currentProvider.createChatModel(rotated.get());
            this.currentModelConfig = rotated.get();
            setStateAttr("resilient:credentialRotated", Boolean.TRUE);
            log.info("凭据已轮换: provider={} model={}",
                    currentProvider.providerName(), currentModelConfig.getModelId());
        } catch (Exception e) {
            log.warn("凭据轮换失败: {}", e.getMessage());
        }
    }
```

> 注意：改造后不再有 `retryCount`/`compressionAttempts` 局部变量与 `MAX_COMPRESSION_ATTEMPTS` 的使用（由 `TurnRetryState` 接管）。`MAX_COMPRESSION_ATTEMPTS` 常量删除。`tryActivateFallback`/`isInFallbackCooldown`/`sleepMs` 等保持原样。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl aether-domain -am test -Dtest=ResilientChatModelExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 tests, 0 failures）

> 若 `successNoRetry` 中 `resp.getResult()` 为 null，说明当前 Spring AI 版本 `ChatResponse` 结构不同 —— 改为断言 `resp.getResult().getOutput().getContent()` 或直接断言 `resp` 非空（跟随 `DefaultMemoryFacadeMergeTest` 的构造方式即可）。

- [ ] **Step 5: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutor.java aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/ResilientChatModelExecutorTest.java
git commit -m "refactor(failover): ResilientChatModelExecutor 委托 TurnRetryState 驱动恢复，新增凭据轮换"
```

---

### Task 7: 全量回归验证

**Files:** 无新增。

- [ ] **Step 1: domain 全量测试**

Run: `mvn -pl aether-domain -am test`
Expected: BUILD SUCCESS，全部测试通过（含既有 33 个 GraphExecutorTest 等 + 本批次新增 5 个测试类）

- [ ] **Step 2: infrastructure 全量测试**

Run: `mvn -pl aether-infrastructure -am test`
Expected: BUILD SUCCESS

- [ ] **Step 3: 检查既有 failover 相关回归**

Run: `mvn -pl aether-domain -am test -Dtest=GraphExecutorTest,ModelInvokerTest,ReActAgentTest,ModelProviderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（这些类都经 `ResilientChatModelExecutor` 或 `ChatModel` 路径，验证容错改造未破坏既有行为）

- [ ] **Step 4: 确认工作区仅含本批次改动**

Run: `git status --short`
Expected: 仅显示 Batch 1 相关的新增/修改文件 + 既有未提交文件（.class 编译产物等，不处理）

---

## 计划自审

- **Spec 覆盖**：设计文档 §4 的每个缺口均有对应任务 — ① TurnRetryState→Task 4；② 自适应限流表→Task 3；③ 凭据轮换→Task 5+6；④ 五向 hint→Task 1；⑤ provider 专用重试上限（`zai_coding_overload_retry_ceiling`）为可选，设计文档标注"可选"，本批次不做，避免过度设计。
- **占位符扫描**：无 TBD/TODO；每个代码步骤均有完整可编译代码。
- **类型一致性**：`RecoveryDirective` 的 `branch()/shouldRetry()/backoffSec()/rotateCredential()/compress()/fallback()/reason()` 在 Task 2（定义）与 Task 6（使用）一致；`TurnRetryState` 构造 `(maxAttempts, fallbackChainSize)` 在 Task 4 定义与 Task 6 调用一致；`setCredentialPool` 在 Task 5 接口与 Task 6 setter 一致。
- **命令一致性**：所有 mvn 命令均带 `-Dsurefire.failIfNoSpecifiedTests=false`（已验证必须）。

## 后续批次

本计划仅覆盖 **Batch 1（D2）**。Batch 2（D3 可扩展配置）、Batch 3（D1 多Agent协作）、Batch 4（D4 观测）将在各自批次启动时按同一 TDD 结构生成独立计划文件（`docs/superpowers/plans/` 目录），设计依据均为 `docs/superpowers/specs/2026-08-12-aether-orchestration-hermes-alignment-design.md` 的 §5/§6/§7。
