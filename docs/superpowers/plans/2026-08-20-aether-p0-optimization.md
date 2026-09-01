# aether P0 优化实施计划（O7 / O12 / O14 / O16）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **不提交 git**：用户明确要求"新建分支不提交"。所有 Task 的收尾步骤是运行验证 + `git status` 确认改动范围，**不执行 `git commit`**。

**Goal:** 在分支 `feat/p0-fixes` 上实现《03-aether-优化方案》P0 四项（O7 压缩管线、O12 超时统一、O14 权限 BYPASS 修复、O16 生产配置/默认凭据），每项按验收标准补 JUnit 并定向 mvn 跑通。

**Architecture:** 严格按方案文档 O7/O12/O14/O16 的改造步骤实施，代码结构与 hermes-agent-main 对应实现对齐（`context_compressor.py` / `chat_completion_helpers.py` / `tool_guardrails.py` / `config.py`+`.env`）。不实现 P0 之外的优化项。hermes-agent-main/ 只读不改。

**Tech Stack:** Java 17 / Spring Boot 3.4.3 / Spring AI 1.0.0-M6 / Maven（surefire 3.5.2）/ JUnit 5 / Mockito。

**设计文档:** `aether/docs/superpowers/specs/2026-08-20-aether-p0-optimization-design.md`

---

## 前置说明

- **工作目录**：`D:\code\Agents-framework\aether`（已建分支 `feat/p0-fixes`，含 108 个此前未提交改动）。
- **mvn 测试命令**（surefire 坑）：`mvn -pl aether-domain -am test -Dtest=<TestClass> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
- 实施顺序：**O14 → O12 → O7 → O16**，每项独立验证后进入下一项。
- 若某项实现中涉及 hermes 对照，按需阅读 `hermes-agent-main/` 对应文件的最小范围（O14→`tool_guardrails.py`、O12→`chat_completion_helpers.py`、O7→`context_compressor.py`、O16→`hermes_cli/config.py`）。

---

## Task 0: 基线验证

**Files:** 无改动

- [ ] **Step 1: 确认分支**

```bash
cd "D:/code/Agents-framework/aether" && git branch --show-current
```
Expected: `feat/p0-fixes`

- [ ] **Step 2: 基线编译（确认脏工作树可编译）**

```bash
mvn -q -pl aether-domain -am test-compile
```
Expected: BUILD SUCCESS。若失败，**停下来报告**（属既有残留问题，非本次改动引入），不自行修复无关错误。

---

## Task 1（O14）: 新增 PermissionEngineBypassTest（TDD 红）

**Files:**
- Create: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/permission/PermissionEngineBypassTest.java`

- [ ] **Step 1: 写失败测试**

```java
package cn.zcj.aether.domain.agent.service.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineBypassTest {

    private PermissionEngine engine() {
        return new PermissionEngine(new DangerousToolRule());
    }

    private PermissionContext ctx(String tool, String cmd) {
        return PermissionContext.builder()
                .toolName(tool)
                .toolInput(Map.of("command", cmd))
                .userId("u1")
                .build();
    }

    @Test
    void bypassModeStillDeniesHardblockedCommands() {
        PermissionEngine engine = engine();
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "rm -rf /"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "mkfs.ext4 /dev/sda1"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "dd if=/dev/zero of=/dev/sda"), PermissionMode.BYPASS));
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "DROP TABLE users"), PermissionMode.BYPASS));
    }

    @Test
    void bypassModeAllowsNonHardblockedCommand() {
        PermissionEngine engine = engine();
        assertEquals(PermissionDecision.ALLOW,
                engine.check(ctx("Bash", "ls -la"), PermissionMode.BYPASS));
    }

    @Test
    void ruleExceptionFailsClosed() {
        PermissionEngine engine = engine();
        engine.registerDenyRule(new PermissionRule() {
            @Override public String name() { return "boom-deny"; }
            @Override public int priority() { return 0; }
            @Override public PermissionDecision evaluate(PermissionContext c) {
                throw new RuntimeException("rule boom");
            }
        });
        assertEquals(PermissionDecision.DENY,
                engine.check(ctx("Bash", "ls -la"), PermissionMode.DEFAULT));
    }

    @Test
    void dangerousToolRuleIsHardblockedDirect() {
        DangerousToolRule rule = new DangerousToolRule();
        assertTrue(rule.isHardblocked(ctx("Bash", "rm -rf /")));
        assertFalse(rule.isHardblocked(ctx("Bash", "ls -la")));
    }
}
```

- [ ] **Step 2: 运行确认失败（编译错误）**

```bash
mvn -pl aether-domain -am test -Dtest=PermissionEngineBypassTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD FAILURE（`PermissionEngine` 无 `PermissionEngine(DangerousToolRule)` 构造器、`DangerousToolRule.isHardblocked` 不存在）。

---

## Task 2（O14）: 实现 PermissionEngine + DangerousToolRule 修复（TDD 绿）

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/permission/DangerousToolRule.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/permission/PermissionEngine.java`

- [ ] **Step 1: `DangerousToolRule` 改为 `@Component` + 抽出 `isHardblocked`**

在类声明加 `@Component`（import `org.springframework.stereotype.Component`），并把 `evaluate` 的 Layer 1 提取为独立方法：

```java
@Component
public class DangerousToolRule implements PermissionRule {
    // ...HARDBLOCK/DANGEROUS 常量与 sessionAllowlist 保持不变...

    /**
     * 仅判断是否命中硬封锁（deny-first 最高层）。
     * O14: 独立入口，供 ToolExecutor.executeOne 关卡 2 二次强制校验。
     */
    public boolean isHardblocked(PermissionContext ctx) {
        String toolCall = ctx.getToolName() + " " + ctx.getToolInput();
        for (Pattern p : HARDBLOCK) {
            if (p.matcher(toolCall).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        // Layer 1: 硬封锁（即使 BYPASS 也拒绝）—— 复用 isHardblocked
        if (isHardblocked(ctx)) {
            log.warn("硬封锁触发: tool={}, user={}", ctx.getToolName(), ctx.getUserId());
            return PermissionDecision.DENY;
        }
        // Layer 2/3 不变...
    }
}
```

- [ ] **Step 2: `PermissionEngine` 构造器注入 + BYPASS deny-first + fail-closed**

```java
@Component
public class PermissionEngine {

    private final List<PermissionRule> denyRules = new CopyOnWriteArrayList<>();
    private final List<PermissionRule> askRules = new CopyOnWriteArrayList<>();
    private final List<PermissionRule> allowRules = new CopyOnWriteArrayList<>();

    /** 外部可配置的工具白名单/黑名单规则引用 */
    private final ToolAllowlistRule toolAllowlistRule;

    /** H4 新增：注入防护规则引用 */
    private final InjectionGuardRule injectionGuardRule;

    /** O14: 危险工具硬封锁规则（BYPASS 模式仍须执行） */
    private final DangerousToolRule dangerousToolRule;

    public PermissionEngine(DangerousToolRule dangerousToolRule) {
        this.dangerousToolRule = dangerousToolRule;
        this.toolAllowlistRule = new ToolAllowlistRule();
        this.injectionGuardRule = new InjectionGuardRule();

        // ====== deny 组 ======
        registerDenyRule(dangerousToolRule);       // p=0 危险工具硬封锁
        registerDenyRule(new SubAgentDenyApprovalRule());
        registerDenyRule(new PlanModeDenyWriteRule());

        // ====== ask 组 ======
        registerAskRule(injectionGuardRule);

        // ====== allow 组 ======
        registerAllowRule(new ReadOnlyAllowRule());
        registerAllowRule(toolAllowlistRule);
    }
```

`check` 方法 BYPASS 分支改为 deny-first：

```java
    public PermissionDecision check(PermissionContext ctx, PermissionMode mode) {
        // O14: BYPASS 仍须执行 deny 组（硬封锁不可绕过，对齐 hermes tool_guardrails 正交语义）
        if (mode == PermissionMode.BYPASS) {
            PermissionDecision deny = evaluateGroup(denyRules, ctx);
            if (deny != null) {
                log.debug("BYPASS 模式 deny 组命中: tool={}, userId={}, decision={}",
                        ctx.getToolName(), ctx.getUserId(), deny);
                return deny;
            }
            log.debug("权限绕过(deny 组未命中): tool={}, userId={}", ctx.getToolName(), ctx.getUserId());
            return PermissionDecision.ALLOW;
        }

        // 1. deny 组（最高优先）……（后续代码不变）
```

`evaluateGroup` 改为 fail-closed：

```java
    private PermissionDecision evaluateGroup(List<PermissionRule> group, PermissionContext ctx) {
        for (PermissionRule rule : group) {
            try {
                PermissionDecision decision = rule.evaluate(ctx);
                if (decision != null) {
                    return decision;
                }
            } catch (Exception e) {
                // O14: 规则异常 fail-closed，绝不静默放行
                log.error("权限规则 [{}] 评估异常，fail-closed 拒绝: {}", rule.name(), e.getMessage(), e);
                return PermissionDecision.DENY;
            }
        }
        return null;
    }
```

- [ ] **Step 3: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=PermissionEngineBypassTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，4 个测试全绿。

> **实施偏差（2026-08-20 记录，spec+quality 双审通过）**：deny 组改走新增 `evaluateDenyGroup`（DENY/异常→DENY；ALLOW 记录但不短路，继续评估其余 deny 规则；纯 ASK_USER 视为未否定）。原因：`DangerousToolRule.evaluate` 恒非 null（良性返回 ALLOW），短路 `evaluateGroup` 会跳过 `SubAgentDenyApprovalRule`/`PlanModeDenyWriteRule` 且无法触发抛异常规则的 fail-closed 测试。新语义保留常见情形行为、修复 deny 组短路缺陷。fail-closed 全局化至 ask/allow 组，与方案"规则异常 fail-closed"一致。

- [ ] **Step 4: 回归既有权限相关测试**

```bash
mvn -pl aether-domain -am test -Dtest=PermissionMiddleware*,ReActAgent*,ToolExecutor* -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 5: 核对无 `new PermissionEngine()` 残留**

```bash
grep -rn "new PermissionEngine(" aether-domain/src aether-app/src --include=*.java
```
Expected: 无匹配（有则改为 `new PermissionEngine(new DangerousToolRule())`）。

- [ ] **Step 6: 记录改动范围**

```bash
git status --short -- aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/permission/ aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/permission/
```
Expected: 仅 DangerousToolRule.java、PermissionEngine.java、PermissionEngineBypassTest.java。**不 commit。**

---

## Task 3（O14）: ToolExecutor 关卡 2 硬封锁二次校验

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/tool/ToolExecutor.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/tool/ToolExecutorTest.java`

- [ ] **Step 1: `ToolExecutor` 注入 `DangerousToolRule` + executeOne 硬封锁关卡**

新增字段与 import：

```java
import cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;

    /**
     * O14: 危险工具硬封锁规则（关卡 2 二次校验，工具执行前无条件执行）。
     * 字段注入（可空）：测试直 new 时不注入则跳过该关卡。
     */
    @Resource
    private DangerousToolRule dangerousToolRule;
```

在 `executeOne` 的关卡 2（`tool.checkPermissions(request.input())` **之前**）插入：

```java
            // ====== P0-1 关卡 2：工具自定义校验 + 权限检查 ======
            ValidationResult customResult = tool.validate(request.input());
            if (!customResult.valid()) {
                return ToolResult.error(
                        request.toolCallId,
                        request.toolName,
                        "Tool '" + request.toolName + "' custom validation failed: "
                                + customResult.errorMessage(),
                        ToolResult.ErrorType.VALIDATION);
            }

            // ====== O14 关卡 2b：危险工具硬封锁（BYPASS 模式亦不可绕过）======
            if (dangerousToolRule != null && dangerousToolRule.isHardblocked(
                    PermissionContext.builder()
                            .toolName(request.toolName)
                            .toolInput(request.input)
                            .userId(ctx.userId())
                            .build())) {
                return ToolResult.error(
                        request.toolCallId,
                        request.toolName,
                        "Tool hard-blocked: command matches dangerous pattern",
                        ToolResult.ErrorType.PERMISSION);
            }

            if (!tool.checkPermissions(request.input())) {
                // ...既有代码不变
```

- [ ] **Step 2: `ToolExecutorTest` 增加硬封锁用例**

在类内追加（`injectField` 助手与 `createTool` 助手沿用既有实现）：

```java
    @Test
    void shouldHardblockDangerousCommandInGateTwo() {
        injectField(executor, "dangerousToolRule",
                new cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule());
        registry.register(createTool("Bash", false));
        List<ToolExecutor.ToolCallRequest> requests = List.of(
                new ToolExecutor.ToolCallRequest("c1", "Bash", Map.of("command", "rm -rf /")));
        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertEquals(ToolResult.ErrorType.PERMISSION, results.get(0).getErrorType());
    }

    @Test
    void shouldNotHardblockSafeCommand() {
        injectField(executor, "dangerousToolRule",
                new cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule());
        registry.register(createTool("Bash", false));
        List<ToolExecutor.ToolCallRequest> requests = List.of(
                new ToolExecutor.ToolCallRequest("c1", "Bash", Map.of("command", "ls -la")));
        List<ToolResult> results = executor.executeBatch(requests, "user1", "session1");
        assertFalse(results.get(0).isError());
    }
```

- [ ] **Step 3: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ToolExecutorTest,PermissionEngineBypassTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，全部通过。

- [ ] **Step 4: 记录改动范围（不 commit）**

---

## Task 4（O12）: ModelProviderTest 超时测试（TDD 红）

**Files:**
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/ModelProviderTest.java`

- [ ] **Step 1: 追加超时测试**

新增 import：

```java
import cn.zcj.aether.domain.agent.service.model.impl.AnthropicProvider;
import cn.zcj.aether.domain.agent.service.model.impl.DashScopeProvider;
import cn.zcj.aether.domain.agent.service.model.impl.OpenAIProvider;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
```

在类内追加：

```java
    private ModelConfig sampleConfig() {
        return ModelConfig.builder()
                .modelId("claude-sonnet-4-6")
                .baseUrl("https://api.anthropic.com")
                .apiKey("sk-test")
                .build();
    }

    @Test
    void httpRequestFactorySeamAppliesTimeouts() throws Exception {
        OpenAIProvider provider = new OpenAIProvider(1111, 2222);
        SimpleClientHttpRequestFactory f = provider.httpRequestFactory(1111, 2222);
        assertEquals(1111, intField(f, "connectTimeout"));
        assertEquals(2222, intField(f, "readTimeout"));
    }

    /** 读取 SimpleClientHttpRequestFactory 私有 int 字段（Spring 6.2 无 getter） */
    private static int intField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    @Test
    void buildOpenAiApiWithTimeoutsReturnsApi() {
        OpenAIProvider provider = new OpenAIProvider(1111, 2222);
        OpenAiApi api = provider.buildOpenAiApi(sampleConfig(), 1111, 2222);
        assertNotNull(api);
    }

    @Test
    void anthropicProviderUsesConfiguredTimeouts() {
        AnthropicProvider p = new AnthropicProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }

    @Test
    void dashScopeProviderUsesConfiguredTimeouts() {
        DashScopeProvider p = new DashScopeProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }

    @Test
    void openAiProviderUsesConfiguredTimeouts() {
        OpenAIProvider p = new OpenAIProvider(1111, 2222);
        assertEquals(1111, p.connectTimeoutMs());
        assertEquals(2222, p.readTimeoutMs());
        assertNotNull(p.createChatModel(sampleConfig()));
    }
```

- [ ] **Step 2: 运行确认失败（编译错误）**

```bash
mvn -pl aether-domain -am test -Dtest=ModelProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD FAILURE（`connectTimeoutMs()`/`readTimeoutMs()`/`httpRequestFactory`/带参构造器不存在）。

---

## Task 5（O12）: ModelProvider 超时工厂 + 三 Provider 统一（TDD 绿）

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/ModelProvider.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/impl/AnthropicProvider.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/impl/DashScopeProvider.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/impl/OpenAIProvider.java`
- Modify: `aether-app/src/main/resources/application.yml`

- [ ] **Step 1: `ModelProvider` 增加超时钩子 + 参数化 `buildOpenAiApi`**

将现有 1 参 `buildOpenAiApi(ModelConfig config)` 的方法体改为委托参数化版本，并新增两个超时钩子：

```java
    /**
     * O12: 超时钩子。默认常量；Provider 可覆盖为从 aether.model.invoker.* 读取的配置值。
     */
    default int connectTimeoutMs() { return CONNECT_TIMEOUT_MS; }
    default int readTimeoutMs() { return READ_TIMEOUT_MS; }

    /**
     * O12: 可测试接缝 —— 构造带显式超时的 RestClient 请求工厂。
     */
    default SimpleClientHttpRequestFactory httpRequestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * 构建带显式 HTTP 超时的 {@link OpenAiApi}（使用 Provider 超时钩子）。
     */
    default OpenAiApi buildOpenAiApi(ModelConfig config) {
        return buildOpenAiApi(config, connectTimeoutMs(), readTimeoutMs());
    }

    /**
     * 构建带显式 HTTP 超时的 {@link OpenAiApi}（参数化）。
     */
    default OpenAiApi buildOpenAiApi(ModelConfig config, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory httpFactory = httpRequestFactory(connectTimeoutMs, readTimeoutMs);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(httpFactory);

        HttpClient nettyHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(nettyHttpClient));

        return OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .completionsPath(StringUtils.isNotBlank(config.getCompletionsPath())
                ? config.getCompletionsPath() : defaultCompletionsPath())
            .embeddingsPath(StringUtils.isNotBlank(config.getEmbeddingsPath())
                ? config.getEmbeddingsPath() : "v1/embeddings")
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
            .build();
    }
```

- [ ] **Step 2: `AnthropicProvider` 构造器注入超时 + 改走 `buildOpenAiApi`**

替换 `createChatModel` 中的 `new OpenAiApi.builder()`，新增构造器与超时钩子，并保留 `v1/messages` 默认路径：

```java
@Slf4j
@Component
public class AnthropicProvider implements ModelProvider {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public AnthropicProvider(
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.connect-timeout-ms:30000}") int connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.read-timeout-ms:120000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public int connectTimeoutMs() { return connectTimeoutMs; }
    @Override
    public int readTimeoutMs() { return readTimeoutMs; }

    @Override
    public String defaultCompletionsPath() { return "v1/messages"; }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        // O12: 统一走 buildOpenAiApi（RestClient/WebClient 双通道显式超时）
        OpenAiApi openAiApi = buildOpenAiApi(config);

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("AnthropicProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }
}
```

> 同时删除不再使用的 `import org.apache.commons.lang3.StringUtils;`（createChatModel 不再直接用）。

> **基建修复（2026-08-20 记录）**：`xfg-wrench-starter-design-framework-3.0.0.jar` 捆绑了 6882 个旧版 `org/springframework` 类（含无 `yaml()` 的 `Jackson2ObjectMapperBuilder`），遮蔽 spring-web 6.2.3，导致 aether-domain 测试环境构造 `OpenAiApi` 时 `NoSuchMethodError`。在 `aether-domain/pom.xml` 显式声明 `spring-web` 依赖并置于 xfg-wrench 之前，让真实类在类路径优先。此为既有依赖污染修复，非 O12 引入。
>
> **测试缺陷修复（2026-08-20 记录）**：Spring 6.2.3 的 `SimpleClientHttpRequestFactory` 无 `getConnectTimeout()`/`getReadTimeout()` getter，`httpRequestFactorySeamAppliesTimeouts` 改用反射读取私有 `connectTimeout`/`readTimeout` 字段。

- [ ] **Step 3: `DashScopeProvider` 同样改造**

```java
@Slf4j
@Component
public class DashScopeProvider implements ModelProvider {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public DashScopeProvider(
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.connect-timeout-ms:30000}") int connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.read-timeout-ms:120000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public int connectTimeoutMs() { return connectTimeoutMs; }
    @Override
    public int readTimeoutMs() { return readTimeoutMs; }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        // O12: 统一走 buildOpenAiApi
        OpenAiApi openAiApi = buildOpenAiApi(config);

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("DashScopeProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }

    @Override
    public String defaultCompletionsPath() { return "compatible-mode/v1/chat/completions"; }
}
```

> 删除不再使用的 `import org.apache.commons.lang3.StringUtils;`。

- [ ] **Step 4: `OpenAIProvider` 同样改造**

```java
@Slf4j
@Component
public class OpenAIProvider implements ModelProvider {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public OpenAIProvider(
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.connect-timeout-ms:30000}") int connectTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${aether.model.invoker.read-timeout-ms:120000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public int connectTimeoutMs() { return connectTimeoutMs; }
    @Override
    public int readTimeoutMs() { return readTimeoutMs; }

    @Override
    public ChatModel createChatModel(ModelConfig config) {
        OpenAiApi openAiApi = buildOpenAiApi(config);   // 既有调用不变，现经超时钩子

        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .build())
            .build();

        log.info("OpenAIProvider 创建 ChatModel: model={}, baseUrl={} (connect={}ms, read={}ms)",
                config.getModelId(), config.getBaseUrl(), connectTimeoutMs, readTimeoutMs);
        return chatModel;
    }
}
```

- [ ] **Step 5: `application.yml` 补 `read-timeout-ms` 并更新注释**

`aether-app/src/main/resources/application.yml` 的 `aether.model.invoker` 段改为：

```yaml
  # ====== P0-2: 模型调用超时（ModelProvider.buildOpenAiApi 统一接入，三 Provider 一致）======
  model:
    invoker:
      call-timeout-ms: 120000      # 单次模型调用总超时（ModelInvoker 与 ReActAgent 同源）
      connect-timeout-ms: 30000    # 建连超时（RestClient + WebClient 双通道）
      read-timeout-ms: 120000      # 读取超时（RestClient + WebClient 双通道）
```

- [ ] **Step 6: 核对 Provider 无直接 `new OpenAiApi.builder()` 残留**

```bash
grep -rn "new OpenAiApi.builder()" aether-domain/src/main/java --include=*.java
```
Expected: 无匹配（MemoryEmbeddingConfig 经 `provider.buildOpenAiApi` 复用，自动受益，无需改动）。

- [ ] **Step 7: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ModelProviderTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，5 个新测试 + 既有 5 个全绿。

- [ ] **Step 8: 记录改动范围（不 commit）**

---

## Task 6（O7）: ContextManager 摘要纳入管线 + TokenBudget 累计 + 冷却

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/context/ContextManager.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/context/ContextManagerTest.java`
- Modify: `aether-app/src/main/resources/application.yml`

- [ ] **Step 1: 新增构造器注入 + 显式 ChatModel 定名 + 冷却字段**

`ContextManager` 类头改动：

```java
@Slf4j
@Service
public class ContextManager {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** O7: 摘要 LLM 调用经由 ModelInvoker（超时/重试收口） */
    private final ModelInvoker modelInvoker;

    /** O7: 成本累计定价查询 */
    private final ModelPricingRegistry pricingRegistry;

    public ContextManager(ModelInvoker modelInvoker, ModelPricingRegistry pricingRegistry) {
        this.modelInvoker = modelInvoker;
        this.pricingRegistry = pricingRegistry;
    }

    @Resource
    private TokenEstimator tokenEstimator;

    // O7: 显式定名全局默认 chatModel（该 bean 由 ChatModelNode 运行时注册，须 @Lazy）
    @Resource(name = "chatModel")
    @org.springframework.context.annotation.Lazy
    private ChatModel chatModel;

    @org.springframework.beans.factory.annotation.Autowired
    private CompactionPipeline compactionPipeline;

    /** O7: 摘要 LLM 冷却窗口（对齐 hermes context_compressor 600s 冷却熔断） */
    @org.springframework.beans.factory.annotation.Value("${aether.context.compaction.summary-cooldown-ms:600000}")
    private long summaryCooldownMs = 600_000;

    /** O7: 会话级摘要冷却截止时间戳 */
    private final ConcurrentHashMap<String, Long> compactCooldownUntil = new ConcurrentHashMap<>();
```

> 保留 `compactFailureCounts` 字段不动。新增 import：`ModelInvoker`、`ModelPricingRegistry`、`ModelPricing`。

- [ ] **Step 2: 重写 `autoCompactIfNeeded`（4 参重载）+ 冷却逻辑**

新增 4 参重载，原 3 参委托：

```java
    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName, String sessionId) {
        return autoCompactIfNeeded(messages, modelName, sessionId, null);
    }

    public AutoCompactResult autoCompactIfNeeded(
            List<TurnMessage> messages, String modelName, String sessionId, TokenBudget tokenBudget) {

        int currentTokens = estimateTokens(messages);
        int contextWindow = tokenEstimator.getContextWindow(modelName);
        int effectiveWindow = contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY;
        int threshold = (int) (effectiveWindow * 0.9);

        if (currentTokens <= threshold || messages.size() < 20) {
            return AutoCompactResult.notNeeded();
        }

        // P2-4: 熔断器检查
        if (sessionId != null) {
            int failCount = compactFailureCounts.getOrDefault(sessionId, 0);
            if (failCount >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                log.warn("[熔断] 会话 {} 连续压缩失败 {} 次，本会话永久放弃自动压缩。"
                        + "当前 tokens={}, threshold={}, messages={}",
                        sessionId, failCount, currentTokens, threshold, messages.size());
                return AutoCompactResult.notNeeded();
            }
        }

        log.info("触发自动压缩: currentTokens={} threshold={} messages={}",
                currentTokens, threshold, messages.size());

        int keepRecent = Math.min(15, messages.size());
        List<TurnMessage> recent = new ArrayList<>(
                messages.subList(Math.max(0, messages.size() - keepRecent), messages.size()));
        List<TurnMessage> toCompact = new ArrayList<>(
                messages.subList(0, Math.max(0, messages.size() - keepRecent)));

        // P2-3: 切分点对齐（既有逻辑不变）
        int movedToCompact = 0;
        while (!recent.isEmpty() && isOrphanToolResult(recent.get(0))) {
            TurnMessage orphan = recent.remove(0);
            toCompact.add(orphan);
            movedToCompact++;
        }
        while (!toCompact.isEmpty() && isDanglingToolUse(toCompact.get(toCompact.size() - 1))) {
            TurnMessage dangling = toCompact.remove(toCompact.size() - 1);
            recent.add(0, dangling);
        }
        if (movedToCompact > 0) {
            log.info("切分点对齐: 将 {} 条孤儿 tool_result 从 recent 移入 toCompact", movedToCompact);
        }

        // C2 + O7: 摘要 LLM 调用（冷却窗口内跳过）
        boolean llmSuccess = false;
        String summary = null;
        long llmDuration = 0;
        boolean inCooldown = sessionId != null && isInSummaryCooldown(sessionId);
        if (inCooldown) {
            log.warn("[冷却] 会话 {} 处于摘要冷却窗口 ({}ms)，跳过 LLM 摘要调用，直接降级",
                    sessionId, summaryCooldownMs);
        } else {
            long llmStart = System.currentTimeMillis();
            summary = generateSummary(toCompact, modelName, tokenBudget);
            llmDuration = System.currentTimeMillis() - llmStart;
            llmSuccess = summary != null && !summary.isBlank();
        }

        // P2-4 + O7: 熔断计数 + 冷却窗口维护
        if (sessionId != null) {
            if (inCooldown) {
                log.debug("会话 {} 冷却期内压缩被跳过，不重复计失败", sessionId);
            } else if (llmSuccess) {
                compactFailureCounts.remove(sessionId);
                compactCooldownUntil.remove(sessionId);
            } else {
                int newCount = compactFailureCounts.merge(sessionId, 1, Integer::sum);
                compactCooldownUntil.put(sessionId, System.currentTimeMillis() + summaryCooldownMs);
                if (newCount >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                    log.error("[熔断触发] 会话 {} 连续压缩失败 {} 次，已触发熔断，本会话不再尝试压缩。",
                            sessionId, newCount);
                } else {
                    log.warn("[压缩失败] 会话 {} 连续压缩失败 {}/{} 次",
                            sessionId, newCount, MAX_CONSECUTIVE_COMPACT_FAILURES);
                }
            }
        }

        if (!llmSuccess) {
            summary = fallbackSummary(toCompact);
        }

        List<TurnMessage> compacted = new ArrayList<>();
        compacted.add(TurnMessage.user(SUMMARY_PREFIX + summary));
        compacted.addAll(recent);

        int postTokens = estimateTokens(compacted);
        log.info("压缩完成: {} → {} tokens (LLM={}ms, success={})",
                currentTokens, postTokens, llmDuration, llmSuccess);

        RuntimeEvent llmCallEvent = RuntimeEvent.internalLlmCall(
                "context-compaction", modelName, llmDuration, llmSuccess);

        return AutoCompactResult.compacted(summary, compacted,
                currentTokens, postTokens, llmCallEvent);
    }
```

- [ ] **Step 3: 重写 `generateSummary`（走 ModelInvoker + 计成本）**

```java
    /**
     * 调用 LLM 生成对话摘要 —— O7: 经 ModelInvoker.callWithStream（超时收口），
     * 成功后将 tokens 计入 TokenBudget 成本熔断；tokenBudget 为 null 时跳过累计（旧 3 参路径）。
     */
    private String generateSummary(List<TurnMessage> toCompact, String modelName, TokenBudget tokenBudget) {
        try {
            StringBuilder conversation = new StringBuilder();
            for (TurnMessage msg : toCompact) {
                String content = msg.content() != null ? msg.content() : "";
                String preview = content.length() > 300
                        ? content.substring(0, 300) + "..."
                        : content;
                conversation.append("[").append(msg.role()).append("]: ")
                        .append(preview).append("\n");
            }

            String instruction = """
                    请将以下对话历史压缩为简洁的摘要（不超过500字）。
                    保留关键决策、重要结论、文件修改操作和未完成的任务。
                    使用中文输出。
                    """;

            ModelInvoker.ModelCallResult result = modelInvoker.callWithStream(
                    chatModel,
                    List.of(new UserMessage(conversation.toString())),
                    instruction,
                    modelName);

            if (result != null && !result.hasError()
                    && result.getFullText() != null && !result.getFullText().isBlank()) {
                if (tokenBudget != null && pricingRegistry != null) {
                    tokenBudget.accumulateCost(result.getInputTokens(), result.getOutputTokens(),
                            pricingRegistry.lookup(modelName));
                }
                return result.getFullText().trim();
            }
        } catch (Exception e) {
            log.warn("LLM摘要生成失败，降级为字符串拼接", e);
        }
        return null;
    }
```

- [ ] **Step 4: 新增冷却辅助方法 + 更新 `runCompactionPipeline` 5 参重载**

```java
    /**
     * O7: 会话是否处于摘要冷却窗口。
     */
    public boolean isInSummaryCooldown(String sessionId) {
        Long until = compactCooldownUntil.get(sessionId);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * O7: 重置会话摘要冷却（测试/手动恢复）。
     */
    public void resetSummaryCooldown(String sessionId) {
        compactCooldownUntil.remove(sessionId);
    }
```

`runCompactionPipeline` 增加 5 参重载（供 ReActAgent 传 tokenBudget），原 4 参委托：

```java
    public CompactionPipeline.CompactionResult runCompactionPipeline(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn) {
        return runCompactionPipeline(messages, modelName, sessionId, startTurn, null);
    }

    public CompactionPipeline.CompactionResult runCompactionPipeline(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn, TokenBudget tokenBudget) {
        return compactionPipeline.compactIfNeeded(messages, modelName, sessionId, startTurn, tokenBudget);
    }
```

- [ ] **Step 5: `application.yml` 增加冷却配置**

`aether-app/src/main/resources/application.yml` 新增：

```yaml
  # ====== O7: 上下文压缩（摘要 LLM 冷却熔断）======
  context:
    compaction:
      summary-cooldown-ms: 600000   # 摘要失败后冷却窗口（对齐 hermes context_compressor 600s）
```

- [ ] **Step 6: 更新 `ContextManagerTest`（构造器 + 新测试）**

原 `@BeforeEach` 增加小窗口注册；原 L356 `new ContextManager()` 改为 `new ContextManager(null, null)`；追加两个新测试：

```java
    @BeforeEach
    void setUp() {
        windowRegistry = new ModelContextWindowRegistry();
        tokenEstimator = new TokenEstimator();
        try {
            var field = TokenEstimator.class.getDeclaredField("windowRegistry");
            field.setAccessible(true);
            field.set(tokenEstimator, windowRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // O7: 注册小窗口模型便于触发压缩（threshold 为负）
        windowRegistry.register("p0-model", 8_000);
    }
```

L356 处 `ContextManager cm = new ContextManager();` 改为 `ContextManager cm = new ContextManager(null, null);`。

追加 import 与新测试：

```java
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import org.springframework.ai.chat.model.ChatModel;
import static org.mockito.Mockito.*;
```

```java
    // ========== O7: 摘要纳入管线 ==========

    private void injectField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ContextManager newCompactionContextManager(ModelInvoker invoker, ModelPricingRegistry pricing)
            throws Exception {
        ContextManager cm = new ContextManager(invoker, pricing);
        injectField(cm, "tokenEstimator", tokenEstimator);
        injectField(cm, "chatModel", mock(ChatModel.class));
        return cm;
    }

    private List<TurnMessage> manyMessages(int n) {
        List<TurnMessage> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(TurnMessage.user("message " + i + " " + "x".repeat(50)));
        }
        return msgs;
    }

    @Test
    void autoCompactRoutesSummaryThroughModelInvokerAndAccumulatesCost() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("summary ok").inputTokens(100).outputTokens(50)
                        .events(List.of()).toolCalls(List.of()).build());
        ModelPricingRegistry pricing = mock(ModelPricingRegistry.class);
        when(pricing.lookup("p0-model")).thenReturn(new ModelPricing("p0-model", 0.001, 0.002));

        ContextManager cm = newCompactionContextManager(invoker, pricing);
        TokenBudget budget = mock(TokenBudget.class);

        AutoCompactResult result = cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-1", budget);

        assertTrue(result.isCompacted());
        verify(invoker).callWithStream(any(), anyList(), anyString(), eq("p0-model"));
        verify(budget).accumulateCost(eq(100), eq(50), any(ModelPricing.class));
    }

    @Test
    void summaryCooldownSkipsLlmCallAfterFailure() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), anyString()))
                .thenReturn(ModelInvoker.ModelCallResult.error("boom"));
        ContextManager cm = newCompactionContextManager(invoker, mock(ModelPricingRegistry.class));

        cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-2", mock(TokenBudget.class));
        assertTrue(cm.isInSummaryCooldown("s-2"));

        cm.autoCompactIfNeeded(manyMessages(25), "p0-model", "s-2", mock(TokenBudget.class));
        verify(invoker, times(1)).callWithStream(any(), anyList(), anyString(), anyString());
    }
```

- [ ] **Step 7: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ContextManagerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，新老测试全绿。

- [ ] **Step 8: 记录改动范围（不 commit）**

---

## Task 7（O7）: ChunkSummarizer + CompactionPipeline 纳入管线

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/context/compaction/ChunkSummarizer.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/context/compaction/CompactionPipeline.java`
- Create: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/context/compaction/ChunkSummarizerTest.java`

- [ ] **Step 1: `ChunkSummarizer` 注入 ModelInvoker + PricingRegistry，走 callWithStream**

字段新增：

```java
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private ModelPricingRegistry pricingRegistry;
```

将 `SUMMARIZE_PROMPT` 常量替换为 system 指令，并改方法签名：

```java
    private static final String SUMMARIZE_SYSTEM =
            "将以下对话片段压缩为简洁摘要。保留关键决策、重要结论、文件修改操作和未完成的任务。使用中文输出，不超过500字。";
```

```java
    public String summarize(List<TurnMessage> prefix, String modelName, TokenBudget tokenBudget) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        String formatted = formatMessages(prefix);
        List<String> chunks = splitIntoChunks(formatted);
        if (chunks.isEmpty()) {
            return "";
        }

        List<String> subSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size(), modelName, tokenBudget);
            if (summary != null && !summary.isBlank()) {
                subSummaries.add(summary.trim());
            }
        }

        if (subSummaries.isEmpty()) {
            return fallbackSummary(formatted);
        }

        String merged = String.join("\n\n---\n\n", subSummaries);

        if (merged.length() > MAX_MERGED_CHARS) {
            log.info("合并摘要长度 {} > {}，递归摘要", merged.length(), MAX_MERGED_CHARS);
            return callLlmWithFallback(merged, formatted, modelName, tokenBudget);
        }

        return merged;
    }

    private String summarizeChunk(String chunk, int index, int total,
                                  String modelName, TokenBudget tokenBudget) {
        log.debug("ChunkSummarizer: 摘要块 {}/{} ({} chars)", index, total, chunk.length());
        return callLlm(chunk, modelName, tokenBudget);
    }

    /**
     * O7: 经 ModelInvoker.callWithStream 调用 LLM；成功累计成本；失败返回 null。
     */
    private String callLlm(String chunk, String modelName, TokenBudget tokenBudget) {
        try {
            ModelInvoker.ModelCallResult result = modelInvoker.callWithStream(
                    chatModel,
                    List.of(new UserMessage(chunk)),
                    SUMMARIZE_SYSTEM,
                    modelName);
            if (result != null && !result.hasError()
                    && result.getFullText() != null && !result.getFullText().isBlank()) {
                if (tokenBudget != null && pricingRegistry != null) {
                    tokenBudget.accumulateCost(result.getInputTokens(), result.getOutputTokens(),
                            pricingRegistry.lookup(modelName));
                }
                return result.getFullText().trim();
            }
        } catch (Exception e) {
            log.warn("ChunkSummarizer: LLM 调用失败", e);
        }
        return null;
    }

    private String callLlmWithFallback(String chunk, String fallbackText,
                                       String modelName, TokenBudget tokenBudget) {
        String result = callLlm(chunk, modelName, tokenBudget);
        if (result != null && !result.isBlank()) {
            return result;
        }
        return fallbackSummary(fallbackText);
    }
```

> 保留 `formatMessages` / `splitIntoChunks` / `splitLongChunk` / `fallbackSummary` / `tokenEstimator` 字段不变（`tokenEstimator` 目前无调用点，不属本次改动）。

- [ ] **Step 2: `CompactionPipeline` 删除孤儿 ChatModel + 透传 TokenBudget**

```java
    private final CompactionTrigger compactionTrigger;
    private final SafeCutoffFinder safeCutoffFinder;
    private final ChunkSummarizer chunkSummarizer;
    private final MessageOffloader messageOffloader;
    private final TokenEstimator tokenEstimator;
    // 删除：@Resource @Lazy ChatModel chatModel（无调用点，D7 歧义注入源）
```

> **Import 清理（因删除字段而变为未使用，须一并移除）**：`import org.springframework.ai.chat.model.ChatModel;` 与 `import jakarta.annotation.Resource;`。

新增 import `cn.zcj.aether.domain.agent.service.context.TokenBudget`。`compactIfNeeded` 改为：

```java
    public CompactionResult compactIfNeeded(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn) {
        return compactIfNeeded(messages, modelName, sessionId, startTurn, null);
    }

    public CompactionResult compactIfNeeded(
            List<TurnMessage> messages, String modelName,
            String sessionId, int startTurn, TokenBudget tokenBudget) {

        if (messages == null || messages.isEmpty()) {
            return CompactionResult.notNeeded(messages);
        }

        int messageCount = messages.size();
        int tokenCount = estimateTokens(messages);

        if (!compactionTrigger.shouldCompact(messageCount, tokenCount)) {
            log.debug("CompactionPipeline: 未触发压缩 (messages={}, tokens={})",
                    messageCount, tokenCount);
            return CompactionResult.notNeeded(messages);
        }

        log.info("CompactionPipeline: 触发压缩 (messages={}, tokens={}, threshold_messages={}, threshold_tokens={})",
                messageCount, tokenCount,
                compactionTrigger.getTriggerMessages(), compactionTrigger.getTriggerTokens());

        int preCompactTokens = tokenCount;

        int cutoffIndex = safeCutoffFinder.findCutoff(messages, compactionTrigger.getKeepTokens());
        if (cutoffIndex <= 0) {
            log.debug("CompactionPipeline: 切点为0，无消息需要压缩");
            return CompactionResult.notNeeded(messages);
        }
        if (cutoffIndex >= messages.size()) {
            log.debug("CompactionPipeline: 切点超出范围，无消息保留");
            return CompactionResult.notNeeded(messages);
        }

        List<TurnMessage> prefix = new ArrayList<>(messages.subList(0, cutoffIndex));
        List<TurnMessage> suffix = new ArrayList<>(messages.subList(cutoffIndex, messages.size()));

        log.info("CompactionPipeline: 切点={} prefix={} suffix={}",
                cutoffIndex, prefix.size(), suffix.size());

        prefix = truncateToolArgs(prefix);

        messageOffloader.offload(sessionId, prefix, startTurn);

        String summary;
        try {
            summary = chunkSummarizer.summarize(prefix, modelName, tokenBudget);
        } catch (Exception e) {
            log.warn("CompactionPipeline: 摘要生成失败", e);
            summary = "[压缩摘要生成失败: " + e.getMessage() + "]";
        }

        List<TurnMessage> compacted = new ArrayList<>();
        compacted.add(TurnMessage.user(
                cn.zcj.aether.domain.agent.service.context.ContextManager.SUMMARY_PREFIX + summary));
        compacted.addAll(suffix);

        int postCompactTokens = estimateTokens(compacted);

        log.info("CompactionPipeline: 压缩完成 {} -> {} tokens ({} -> {} messages)",
                preCompactTokens, postCompactTokens,
                messages.size(), compacted.size());

        return CompactionResult.compacted(summary, compacted, preCompactTokens, postCompactTokens);
    }
```

- [ ] **Step 3: 新增 `ChunkSummarizerTest`**

```java
package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.ModelPricing;
import cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkSummarizerTest {

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void summarizeRoutesThroughModelInvokerAndAccumulatesCost() throws Exception {
        ModelInvoker invoker = mock(ModelInvoker.class);
        when(invoker.callWithStream(any(), anyList(), anyString(), eq("m")))
                .thenReturn(ModelInvoker.ModelCallResult.builder()
                        .fullText("sum").inputTokens(10).outputTokens(5)
                        .events(List.of()).toolCalls(List.of()).build());
        ModelPricingRegistry pricing = mock(ModelPricingRegistry.class);
        when(pricing.lookup("m")).thenReturn(new ModelPricing("m", 0.001, 0.002));

        ChunkSummarizer summarizer = new ChunkSummarizer();
        injectField(summarizer, "chatModel", mock(ChatModel.class));
        injectField(summarizer, "modelInvoker", invoker);
        injectField(summarizer, "pricingRegistry", pricing);

        TokenBudget budget = mock(TokenBudget.class);
        String out = summarizer.summarize(
                List.of(TurnMessage.user("hello"), TurnMessage.assistant("world")), "m", budget);

        assertEquals("sum", out);
        verify(invoker).callWithStream(any(), anyList(), anyString(), eq("m"));
        verify(budget).accumulateCost(eq(10), eq(5), any(ModelPricing.class));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ChunkSummarizerTest,ContextManagerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 5: 记录改动范围（不 commit）**

---

## Task 8（O7）: ReActAgent 传 tokenBudget + 更新测试 stub

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/ReActAgent.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/hook/ReActAgentApiHookTest.java`

- [ ] **Step 1: `ReActAgent` 两处压缩调用点传 `tokenBudget`**

L191：

```java
            var compactResult = contextManager.autoCompactIfNeeded(messages, config.getModelRef(), ctx.sessionId(), tokenBudget);
```

L209：

```java
                var pipeResult = contextManager.runCompactionPipeline(messages, config.getModelRef(), ctx.sessionId(), state.getCurrentTurn(), tokenBudget);
```

- [ ] **Step 2: 更新 `ReActAgentApiHookTest` stub 到新签名**

```java
        when(contextManager.autoCompactIfNeeded(anyList(), anyString(), anyString(), any(TokenBudget.class)))
                .thenReturn(cn.zcj.aether.domain.agent.service.context.AutoCompactResult.notNeeded());
        when(contextManager.runCompactionPipeline(anyList(), anyString(), anyString(), anyInt(), any(TokenBudget.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.List<cn.zcj.aether.domain.agent.service.runtime.TurnMessage> msgs =
                            (java.util.List<cn.zcj.aether.domain.agent.service.runtime.TurnMessage>) inv.getArgument(0);
                    return new cn.zcj.aether.domain.agent.service.context.compaction.CompactionPipeline
                            .CompactionResult(false, null, msgs, 0, 0);
                });
```

> `TokenBudget` import 已在测试中存在（L51 使用）。若 `import static org.mockito.ArgumentMatchers.any` 已存在则直接可用。

- [ ] **Step 3: 运行测试确认通过**

```bash
mvn -pl aether-domain -am test -Dtest=ReActAgentApiHookTest,ReActAgentTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 4: O7 全量回归（domain 相关测试）**

```bash
mvn -pl aether-domain -am test -Dtest=ContextManagerTest,ChunkSummarizerTest,ReActAgentTest,ReActAgentApiHookTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 5: 记录改动范围（不 commit）**

---

## Task 9（O16）: 配置 yml（base/dev/test/prod）

**Files:**
- Modify: `aether-app/src/main/resources/application.yml`
- Modify: `aether-app/src/main/resources/application-test.yml`
- Modify: `aether-app/src/main/resources/application-prod.yml`
- （`application-dev.yml` 已含 dev JWT 回退，无需改）

- [ ] **Step 1: `application.yml` 移除硬编码 JWT 默认密钥**

`aether.security.jwt.secret` 改为：

```yaml
  security:
    jwt:
      secret: ${JWT_SECRET}
      access-token-expiration: 900000
      refresh-token-expiration: 604800000
```

> dev 回退密钥已在 `application-dev.yml`（`${JWT_SECRET:aether-dev-jwt-secret-key-min-32-chars!!}`），profile 覆盖优先，dev 本地可用；prod 无回退 → 缺 `JWT_SECRET` 即 fail-fast。

- [ ] **Step 2: `application-test.yml` 补测试 JWT 密钥**

文件末尾追加：

```yaml
# ====== O16: 测试专用 JWT 密钥（基座无默认回退后必须显式提供）======
aether:
  security:
    jwt:
      secret: test-only-jwt-secret-key-min-32-chars!!
```

- [ ] **Step 3: `application-prod.yml` 补全生产配置**

整个文件替换为：

```yaml
server:
  port: 8091

spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      pool-name: Aether_HikariCP
      minimum-idle: 15
      idle-timeout: 180000
      maximum-pool-size: 25
      auto-commit: true
      max-lifetime: 1800000
      connection-timeout: 30000
      connection-test-query: SELECT 1
    type: com.zaxxer.hikari.HikariDataSource

aether:
  # ====== O16: 生产安全配置（无默认值 → 缺环境变量即 fail-fast）======
  security:
    jwt:
      secret: ${JWT_SECRET}
      access-token-expiration: 900000
      refresh-token-expiration: 604800000
  # ====== O16: 会话持久化（显式启用 Pg 仓储）======
  session:
    persistence: true
    store: postgres
  # ====== O16: SSRF 默认拒绝私有地址 ======
  ssrf:
    allow-private-urls: false
  delegation:
    persistence: true
    live-log-dir: ./cache/delegation/live
    stale-timeout: PT10M
    stale-scan-interval-ms: 60000

# 日志
logging:
  level:
    root: info
  config: classpath:logback-spring.xml

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
  metrics:
    tags:
      application: aether-agent

# ====== O16: OpenTelemetry（OTel Java Agent 读取）======
otel:
  traces:
    exporter: otlp
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
  service:
    name: aether-agent
```

- [ ] **Step 4: 校验 yml 语法**

```bash
python -c "import yaml,sys; [yaml.safe_load(open(f,encoding='utf-8')) for f in ['aether-app/src/main/resources/application.yml','aether-app/src/main/resources/application-prod.yml','aether-app/src/main/resources/application-test.yml']]; print('yml OK')"
```
Expected: `yml OK`

- [ ] **Step 5: 记录改动范围（不 commit）**

---

## Task 10（O16）: DataInitializer bootstrap-admin 门控

**Files:**
- Modify: `aether-app/src/main/java/cn/zcj/aether/config/DataInitializer.java`
- Create: `aether-app/src/test/java/cn/zcj/aether/config/DataInitializerTest.java`

- [ ] **Step 1: 重写 `DataInitializer`**

```java
package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import cn.zcj.aether.types.enums.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器 — 初始管理员创建（O16: 默认无后门账号）。
 *
 * <p>仅当 {@code aether.security.bootstrap-admin=true} 且环境变量
 * {@code AETHER_ADMIN_PASSWORD} 非空时才创建 admin，密码取自环境变量。
 * 否则 WARN 跳过，不内置默认凭据。</p>
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    /** 用户仓储（可选注入）：无数据源/未装配仓储时跳过初始化。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /** O16: 是否允许创建初始管理员（默认 false） */
    private final boolean bootstrapAdmin;

    /** O16: 初始管理员密码（来自环境变量 AETHER_ADMIN_PASSWORD，无默认值） */
    private final String adminPassword;

    public DataInitializer(PasswordEncoder passwordEncoder,
            @Value("${aether.security.bootstrap-admin:false}") boolean bootstrapAdmin,
            @Value("${AETHER_ADMIN_PASSWORD:}") String adminPassword) {
        this.passwordEncoder = passwordEncoder;
        this.bootstrapAdmin = bootstrapAdmin;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapAdmin) {
            log.warn("aether.security.bootstrap-admin=false，跳过初始管理员创建");
            return;
        }
        if (userRepository == null) {
            log.info("未装配 UserRepository（无数据源），跳过默认管理员初始化");
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("aether.security.bootstrap-admin=true 但环境变量 AETHER_ADMIN_PASSWORD 未设置，跳过管理员创建");
            return;
        }
        if (userRepository.findByUsername("admin").isEmpty()) {
            UserRepository.UserEntity admin = new UserRepository.UserEntity();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setEmail("admin@aether.local");
            admin.setRole(UserRole.ADMIN.getCode());
            admin.setEnabled(true);
            userRepository.save(admin);
            log.info("============================================");
            log.info("  初始管理员已创建（密码来自 AETHER_ADMIN_PASSWORD 环境变量）");
            log.info("============================================");
        } else {
            log.info("管理员用户已存在，跳过初始化");
        }
    }
}
```

- [ ] **Step 2: 新增 `DataInitializerTest`**

```java
package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    private void injectRepository(DataInitializer init, UserRepository repo) throws Exception {
        Field f = DataInitializer.class.getDeclaredField("userRepository");
        f.setAccessible(true);
        f.set(init, repo);
    }

    @Test
    void createsAdminWhenBootstrapEnabledAndPasswordProvided() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("env-pass-123")).thenReturn("encoded");

        DataInitializer init = new DataInitializer(encoder, true, "env-pass-123");
        injectRepository(init, repo);
        init.run();

        verify(repo).save(argThat(u ->
                "admin".equals(u.getUsername()) && "encoded".equals(u.getPassword())));
    }

    @Test
    void skipsWhenBootstrapDisabled() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        DataInitializer init = new DataInitializer(mock(PasswordEncoder.class), false, "env-pass-123");
        injectRepository(init, repo);
        init.run();
        verifyNoInteractions(repo);
    }

    @Test
    void skipsWhenPasswordMissing() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        DataInitializer init = new DataInitializer(mock(PasswordEncoder.class), true, "");
        injectRepository(init, repo);
        init.run();
        verifyNoInteractions(repo);
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

```bash
mvn -pl aether-app -am test -Dtest=DataInitializerTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，3 个测试全绿。

- [ ] **Step 4: 记录改动范围（不 commit）**

---

## Task 11（O16）: CorsConfig 默认收紧

**Files:**
- Modify: `aether-app/src/main/java/cn/zcj/aether/config/CorsConfig.java`
- Create: `aether-app/src/test/java/cn/zcj/aether/config/CorsConfigTest.java`

- [ ] **Step 1: 重写 `CorsConfig`（构造器注入 + buildConfiguration 可测）**

```java
package cn.zcj.aether.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * O16: CORS 配置 —— 默认空白名单（无匹配即拒绝跨域）。
 *
 * <ul>
 *   <li>未配置 {@code aether.cors.allowed-origins} → 拒绝所有跨域</li>
 *   <li>{@code *} → 允许任意来源但关闭 credentials（* + credentials 非法/危险）</li>
 *   <li>具体白名单 → 允许并启用 credentials</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CorsConfig {

    private final String allowedOrigins;

    public CorsConfig(@Value("${aether.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 构建 CORS 配置（独立方法便于单元测试）。
     */
    public CorsConfiguration buildConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        String origins = allowedOrigins == null ? "" : allowedOrigins.trim();

        if (origins.isEmpty()) {
            log.info("CORS: 未配置 aether.cors.allowed-origins，拒绝所有跨域请求");
        } else if ("*".equals(origins)) {
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(List.of(origins.split(",")));
            config.setAllowCredentials(true);
        }

        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setMaxAge(3600L);
        return config;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfiguration());
        return new CorsFilter(source);
    }
}
```

- [ ] **Step 2: 新增 `CorsConfigTest`**

```java
package cn.zcj.aether.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    @Test
    void emptyOriginsDeniesAllCrossOrigin() {
        CorsConfiguration cfg = new CorsConfig("").buildConfiguration();
        assertNull(cfg.getAllowedOrigins());
        assertNull(cfg.getAllowedOriginPatterns());
        assertFalse(cfg.getAllowCredentials());
    }

    @Test
    void wildcardOriginsDisablesCredentials() {
        CorsConfiguration cfg = new CorsConfig("*").buildConfiguration();
        assertNotNull(cfg.getAllowedOriginPatterns());
        assertFalse(cfg.getAllowCredentials());
    }

    @Test
    void specificOriginsEnablesCredentials() {
        CorsConfiguration cfg = new CorsConfig("https://a.example.com,https://b.example.com").buildConfiguration();
        assertEquals(2, cfg.getAllowedOrigins().size());
        assertTrue(cfg.getAllowCredentials());
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

```bash
mvn -pl aether-app -am test -Dtest=CorsConfigTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，3 个测试全绿。

- [ ] **Step 4: 记录改动范围（不 commit）**

---

## Task 12（O16 收尾）: 整体编译 + 受影响模块回归

**Files:** 无改动

- [ ] **Step 1: 全量编译**

```bash
mvn -q -pl aether-app -am test-compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 2: 受影响测试全量回归**

```bash
mvn -pl aether-domain -am test -Dtest=PermissionEngineBypassTest,ToolExecutorTest,ModelProviderTest,ContextManagerTest,ChunkSummarizerTest,ReActAgentTest,ReActAgentApiHookTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aether-app -am test -Dtest=DataInitializerTest,CorsConfigTest,AetherExecutorRegistryTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 3: 核对 P0 改动文件清单**

```bash
git status --short -- aether-app/src/main aether-app/src/test aether-domain/src/main aether-domain/src/test
```
Expected: 仅包含四项目涉文件 + 新增测试。确认无 P0 之外的改动（尤其未触碰 `hermes-agent-main/`）。

- [ ] **Step 4: 总结交付（不 commit）**

汇报四项 P0 各自的改动文件、新增测试与运行结果。hermes-agent-main/ 保持零修改。

---

## Self-Review 记录

- **Spec 覆盖**：O14（Task 1-3）、O12（Task 4-5）、O7（Task 6-8）、O16（Task 9-11）逐项对应设计文档 §3-§6。
- **占位符**：全部步骤含完整代码与命令，无 TBD/TODO。
- **类型一致性**：`autoCompactIfNeeded(messages, modelName, sessionId, TokenBudget)`、`runCompactionPipeline(messages, modelName, sessionId, startTurn, TokenBudget)`、`compactIfNeeded(messages, modelName, sessionId, startTurn, TokenBudget)`、`summarize(prefix, modelName, TokenBudget)` 在 Task 6/7/8 签名一致；`buildOpenAiApi(config)` / `buildOpenAiApi(config, int, int)` / `connectTimeoutMs()` / `readTimeoutMs()` 在 Task 4/5 一致；`isHardblocked(PermissionContext)` 在 Task 2/3 一致。
- **范围**：无 P0 之外条目；hermes-agent-main/ 零修改；不提交 git。
