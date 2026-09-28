package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentTracer;
import cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import cn.zcj.aether.domain.agent.service.tool.validation.SchemaHintBuilder;
import cn.zcj.aether.domain.agent.service.tool.validation.ToolInputValidator;
import cn.zcj.aether.domain.agent.service.tool.validation.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 工具编排执行器。
 *
 * <p>参考项目B toolOrchestration.ts:91-115 的分区算法:
 * <ul>
 *   <li>并发安全工具 → 并行执行</li>
 *   <li>非并发安全工具 → 串行执行</li>
 * </ul>
 *
 * <p>P0-1 升级：
 * <ul>
 *   <li>executeOne() 插入四道校验关卡（JSON Schema 校验 → 工具自定义校验 → 危险工具硬封锁 → 工具级权限）</li>
 *   <li>executor 从 newCachedThreadPool 替换为有界线程池，消除线程膨胀风险</li>
 *   <li>isConcurrencySafe() 判定异常时保守降级为 unsafe（对齐 cc-haha 的安全设计）</li>
 *   <li>校验失败的 ToolResult 携带 Schema 提示回喂 LLM（对齐 crewAI build_schema_hint）</li>
 * </ul>
 *
 * <p>H5-步骤5 升级：写操作前自动触发工作区快照。
 * <ul>
 *   <li>写类工具（isReadOnly()==false）首次调用前触发快照</li>
 *   <li>每轮每目录至多一次（snapshottedThisTurn 去重）</li>
 *   <li>异常静默不阻塞工具执行（对齐 hermes tool_executor.py L1246-1247）</li>
 * </ul>
 */
@Slf4j
@Service
public class ToolExecutor {

    @Resource
    private ToolRegistry toolRegistry;

    /**
     * O14: 危险工具硬封锁规则（关卡 2 二次校验，工具执行前无条件执行）。
     * 字段注入（可空）：测试直 new 时不注入则跳过该关卡。
     */
    @Resource
    private DangerousToolRule dangerousToolRule;

    /**
     * D2/F1-3: 权限引擎（关卡④ 第二级 —— 通道兜底）。
     * 字段注入（可空）：测试直 new 时不注入则关卡④ 退化为仅 {@code tool.checkPermissions()}，
     * 与改造前行为完全一致。
     */
    @Resource
    private PermissionEngine permissionEngine;

    /**
     * P0-1：有界线程池，替代原 {@code Executors.newCachedThreadPool()}。
     * <ul>
     *   <li>核心线程 4，最大线程 16</li>
     *   <li>有界队列 200</li>
     *   <li>CallerRunsPolicy：队列满时由调用线程执行，提供反压</li>
     *   <li>命名线程工厂 {@code aether-tool-%d}，便于排查</li>
     * </ul>
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "aether-tool-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 串行（非并发安全）工具超时，防止单个工具挂起阻塞整个 SSE 流。 */
    private static final long SERIAL_TOOL_TIMEOUT_SECONDS = 120;

    /**
     * P0-1 新增：JSON Schema 校验器。
     * 无状态，可在构造时手动创建或由 Spring 注入。
     */
    private final ToolInputValidator toolInputValidator;

    // ============ H5-步骤5: 工作区快照支持 ============

    /**
     * H5-步骤5: 检查点收集器（可选注入，无 Bean 时为 null）。
     * 用于写操作前自动触发工作区快照。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CheckpointCollector checkpointCollector;

    /**
     * H5-步骤5: 本轮已快照目录去重集合（运行期，不序列化）。
     * 对齐 hermes {@code _checkpointed_dirs}（checkpoint_manager.py L737-739）。
     * 每轮开始由 ReActAgent 通过 {@link #clearSnapshotTracking()} 清空。
     */
    private final Set<String> snapshottedThisTurn = ConcurrentHashMap.newKeySet();

    public ToolExecutor() {
        this.toolInputValidator = new ToolInputValidator();
    }

    /**
     * Spring 注入构造器（可选，用于测试时替换 validator）。
     */
    public ToolExecutor(ToolInputValidator toolInputValidator) {
        this.toolInputValidator = toolInputValidator != null
                ? toolInputValidator
                : new ToolInputValidator();
    }

    // ============ H5-步骤5: 快照跟踪生命周期 ============

    /**
     * 清除本轮快照去重集合。
     * 由 ReActAgent 在每轮开始时调用（对齐 hermes {@code new_turn()} L737-739）。
     */
    public void clearSnapshotTracking() {
        snapshottedThisTurn.clear();
    }

    /**
     * 兼容重载：默认 {@code DEFAULT} 模式 + 未预检（由关卡④ 兜底）。
     */
    public List<ToolResult> executeBatch(List<ToolCallRequest> requests, String userId, String sessionId) {
        return executeBatch(requests, new ToolContext(userId, sessionId, ""));
    }

    /**
     * 带显式上下文的批量执行。
     *
     * @param requests 工具调用请求（非空校验由调用方负责）
     * @param ctx      工具执行上下文（含权限模式与预检标记）
     * @return 与 requests 顺序对齐的执行结果
     */
    public List<ToolResult> executeBatch(List<ToolCallRequest> requests, ToolContext ctx) {
        // P0-1：按 isConcurrencySafe 分区，异常时保守降级为 unsafe
        List<ToolCallRequest> safe = new ArrayList<>();
        List<ToolCallRequest> unsafe = new ArrayList<>();

        for (ToolCallRequest req : requests) {
            Tool tool = toolRegistry.get(req.toolName);
            if (tool != null) {
                try {
                    if (tool.isConcurrencySafe()) {
                        safe.add(req);
                    } else {
                        unsafe.add(req);
                    }
                } catch (Exception e) {
                    // P0-1：判定函数抛异常时保守按并发不安全处理（对齐 cc-haha 安全设计）
                    log.warn("工具 [{}] isConcurrencySafe() 判定异常，降级为串行执行: {}",
                            req.toolName, e.getMessage());
                    unsafe.add(req);
                }
            } else {
                unsafe.add(req);
            }
        }

        List<ToolResult> results = new ArrayList<>();

        // 并发安全组 → 并行执行
        if (!safe.isEmpty()) {
            results.addAll(executeConcurrently(safe, ctx));
        }

        // 非并发安全组 → 串行执行
        if (!unsafe.isEmpty()) {
            results.addAll(executeSerially(unsafe, ctx));
        }

        return results;
    }

    private List<ToolResult> executeConcurrently(List<ToolCallRequest> requests, ToolContext ctx) {
        return executeConcurrently(requests, ctx, 60);
    }

    /** O10: 超时可注入的重载（生产固定 60s；测试可缩短以验证超时配对）。 */
    List<ToolResult> executeConcurrently(List<ToolCallRequest> requests, ToolContext ctx, long timeoutSeconds) {
        List<CompletableFuture<ToolResult>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(
                        withCurrentOtelContext(() -> executeOne(req, ctx)), executor))
                .toList();

        // O10: 超时/异常分支必须携带原 toolCallId/toolName——空 ID 回注后无法与
        // assistant 的 tool_call 配对，会造成模型侧上下文污染
        List<ToolResult> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            ToolCallRequest req = requests.get(i);
            CompletableFuture<ToolResult> f = futures.get(i);
            try {
                results.add(f.get(timeoutSeconds, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                // P0-1：超时使用 TIMEOUT 错误类型；O10：携带原 toolCallId/toolName
                results.add(ToolResult.error(req.toolCallId(), req.toolName(),
                        "Tool timeout: " + e.getMessage(),
                        ToolResult.ErrorType.TIMEOUT));
            } catch (Exception e) {
                results.add(ToolResult.error(req.toolCallId(), req.toolName(),
                        "Tool execution failed: " + e.getMessage()));
            }
        }
        return results;
    }

    private List<ToolResult> executeSerially(List<ToolCallRequest> requests, ToolContext ctx) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCallRequest req : requests) {
            results.add(executeWithTimeout(req, ctx));
        }
        return results;
    }

    /**
     * 串行工具同样有界执行，避免单个工具挂起导致 SSE 流永久阻塞（前端表现为"一直在生成中"）。
     * 串行组多为慢操作（文档生成/代码执行），超时放宽到 120s；并行组保持 60s。
     */
    private ToolResult executeWithTimeout(ToolCallRequest request, ToolContext ctx) {
        CompletableFuture<ToolResult> future =
                CompletableFuture.supplyAsync(
                        withCurrentOtelContext(() -> executeOne(request, ctx)), executor);
        try {
            return future.get(SERIAL_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.error(request.toolCallId(), request.toolName(),
                    "Tool timeout: " + e.getMessage(), ToolResult.ErrorType.TIMEOUT);
        } catch (Exception e) {
            return ToolResult.error(request.toolCallId(), request.toolName(),
                    "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * D4/F2-6: 把当前 OTel 上下文带过线程边界。
     *
     * <p>OTel 的 current span 存在 ThreadLocal 里，而本类的工具执行统一提交到
     * {@code executor} 线程池。若不显式传递，工作线程看到的是"无父 span"的根上下文，
     * {@code agent.tool.call} 会变成独立 trace 的根 span，而不是当轮 {@code agent.turn} 的子 span——
     * 那样"按 trace 定位工具调用失败"（JD 职责4）就无从谈起。</p>
     *
     * <p>故在<b>提交前</b>捕获调用线程的上下文，在工作线程内 {@code makeCurrent} 后再建 span。
     * 只做上下文透传，不改变任务的执行线程与语义。</p>
     */
    private static <T> java.util.function.Supplier<T> withCurrentOtelContext(
            java.util.function.Supplier<T> task) {
        io.opentelemetry.context.Context parent = io.opentelemetry.context.Context.current();
        return () -> {
            try (io.opentelemetry.context.Scope ignored = parent.makeCurrent()) {
                return task.get();
            }
        };
    }

    /**
     * D4/F2-6: 工具调用埋点入口 —— 开启 {@code agent.tool.call} span 后委派 {@link #executeAuthorized}。
     *
     * <p>把埋点收在单一出口：无论工具不存在、四道关卡拒绝、执行成功或执行失败，
     * span 只在返回值/异常离开本方法时结束一次。异常路径覆盖 {@link Error}（如 OOM），
     * 避免它们逃逸时留下永不结束的悬空 span。</p>
     *
     * <p>span 属性 {@code tool.success} / {@code tool.error} 使"工具调用失败"可按 trace 过滤
     * （对应 JD 职责4 的定位场景）。工具失败时 error 文本取自
     * {@link ToolResult#getContent()}——{@code ToolResult} 无 {@code errorMessage} 字段，
     * 错误消息统一存在 {@code content}（见 {@link ToolResult#error} 各重载）。</p>
     */
    private ToolResult executeOne(ToolCallRequest request, ToolContext ctx) {
        io.opentelemetry.api.trace.Span span =
                AgentTracer.startToolCall(request.toolName, request.toolCallId);
        try {
            ToolResult result = executeAuthorized(request, ctx);
            AgentTracer.endToolCall(span, !result.isError(),
                    result.isError() ? result.getContent() : null);
            return result;
        } catch (RuntimeException | Error e) {
            // executeAuthorized 已自行收口 Exception；此处兜底未预期逃逸，保证 span 必被结束
            AgentTracer.endSpanWithError(span, e.getMessage());
            throw e;
        }
    }

    /**
     * P0-1：四道校验关卡 + 原调用逻辑。
     *
     * <p>关卡顺序：① JSON Schema 校验（失败时回填 {@link SchemaHintBuilder} 提示）→
     * ② 工具自定义校验 {@code tool.validate()} → ③ 危险工具硬封锁（O14，BYPASS 亦不可绕过）→
     * ④ 工具级权限（两级判定：{@code tool.checkPermissions()} 硬约束 + 未预检通道的
     * {@link PermissionEngine} 兜底）。</p>
     *
     * <p>注：策略层 {@code PermissionEngine} 的 deny-first 分组短路链主要在
     * {@code PermissionMiddleware} 中执行（挂起/确认/恢复）；本方法仅在**未预检通道**上
     * 补做兜底评估（fail-closed），覆盖绕过 {@code ReActAgent} 直调 {@link ToolExecutor} 的路径。
     * 危险工具硬封锁与关卡 ③ 构成互不依赖的双重校验。</p>
     *
     * <p><b>已知取舍</b>：预检通道（{@code ctx.preAuthorized() == true}）跳过第二级评估——
     * 若某调用已被中间件 DENY 却因竞态到达此处，将被直接放行。该取舍以"不与中间件重复挂起"换取。</p>
     *
     * <p>数据结构变化：校验失败的 ToolResult 与执行失败的 ToolResult
     * 走同一通道回到 ReActAgent 的消息流，LLM 下一轮读到 Schema 提示自我修正——
     * 不新增任何协议，复用现有 {@link ToolResult# error} 回传路径。
     */
    private ToolResult executeAuthorized(ToolCallRequest request, ToolContext ctx) {
        try {
            Tool tool = toolRegistry.get(request.toolName);
            if (tool == null) {
                return ToolResult.error(request.toolCallId, request.toolName,
                        "Tool not found: " + request.toolName,
                        ToolResult.ErrorType.EXECUTION);
            }

            // ====== O9: schema 暴露面可见 ======
            logEmptySchemaIfNeeded(tool, request.toolName);

            // ====== P0-1 关卡 1：JSON Schema 校验 ======
            ValidationResult schemaResult = toolInputValidator.validate(
                    tool.inputSchema(), request.input());
            if (!schemaResult.valid()) {
                String hint = SchemaHintBuilder.buildHint(tool.inputSchema());
                return ToolResult.error(
                        request.toolCallId,
                        request.toolName,
                        "Tool '" + request.toolName + "' arguments validation failed: "
                                + schemaResult.errorMessage() + hint,
                        ToolResult.ErrorType.VALIDATION);
            }

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

            // ====== D2/F1-3 关卡④：两级判定 ======
            // 第一级 —— 工具自身不可委托给权限引擎的硬约束（**不是安全边界**，
            //   安全边界由 PermissionEngine 承担，见 Tool#checkPermissions Javadoc）。
            if (!tool.checkPermissions(request.input())) {
                log.warn("关卡④ 工具自检拒绝: tool={}, userId={}, mode={}, channel={}",
                        request.toolName, ctx.userId(), ctx.permissionMode(), channelOf(ctx));
                return ToolResult.error(
                        request.toolCallId,
                        request.toolName,
                        "Tool '" + request.toolName + "' self-check denied",
                        ToolResult.ErrorType.PERMISSION);
            }

            // 第二级 —— 通道兜底：仅对**未预检**的调用生效，避免与 PermissionMiddleware 重复评估
            //   （预检通道的 ASK_USER 已由中间件挂起，此处若再评估会造成重复挂起）。
            if (!ctx.preAuthorized() && permissionEngine != null) {
                ToolResult denial = evaluateByPermissionEngine(tool, request, ctx);
                if (denial != null) {
                    return denial;
                }
            }

            // ====== H5-步骤5: 写操作前自动快照 ======
            triggerWriteSnapshot(tool, request);

            // ====== 原有调用逻辑不变 ======
            ToolContext perCallCtx = new ToolContext(
                    ctx.userId(), ctx.sessionId(), request.toolCallId,
                    ctx.permissionMode(), ctx.preAuthorized());
            return tool.call(request.input, perCallCtx);

        } catch (Exception e) {
            log.error("Tool execution error: {}", request.toolName, e);
            return ToolResult.error(
                    request.toolCallId, request.toolName,
                    e.getMessage(),
                    ToolResult.ErrorType.EXECUTION);
        }
    }

    /**
     * D2/F1-3 关卡④ 第二级：未预检通道的权限兜底评估。
     *
     * <p>{@code PermissionEngine} 内部规则异常已 fail-closed 返回 DENY；本方法不吞异常——
     * 构造 {@link PermissionContext} 或评估抛出的异常由 {@link #executeOne} 外层
     * {@code catch (Exception e)} 转为 {@code EXECUTION} 错误，**不放行**。
     *
     * @return 拒绝时的 {@link ToolResult}；放行返回 {@code null}
     */
    private ToolResult evaluateByPermissionEngine(Tool tool, ToolCallRequest request, ToolContext ctx) {
        PermissionContext permCtx = PermissionContext.builder()
                .toolName(request.toolName)
                .toolCallId(request.toolCallId)
                .toolInput(request.input)
                .userId(ctx.userId())
                .sessionId(ctx.sessionId())
                .isReadOnly(tool.isReadOnly())
                .mode(ctx.permissionMode())
                .build();

        PermissionDecision decision = permissionEngine.check(permCtx, ctx.permissionMode());
        String denialMessage = switch (decision) {
            case DENY -> "Permission denied for tool '" + request.toolName + "' (channel: direct)";
            // 直调通道无挂起/恢复能力 → 按 fail-closed 拒绝，绝不静默放行
            case ASK_USER -> "Tool '" + request.toolName + "' requires user confirmation, but this call"
                    + " channel does not support suspension; rejected (fail-closed)";
            case ALLOW -> null;
        };
        if (denialMessage == null) {
            return null;
        }

        log.warn("关卡④ 通道兜底拒绝: tool={}, userId={}, mode={}, channel={}, decision={}",
                request.toolName, ctx.userId(), ctx.permissionMode(), channelOf(ctx), decision);
        return ToolResult.error(request.toolCallId, request.toolName,
                denialMessage, ToolResult.ErrorType.PERMISSION);
    }

    /** D2: 关卡④ 拒绝日志的通道标识 —— 预检通道 vs 绕过中间件的直调通道。 */
    private static String channelOf(ToolContext ctx) {
        return ctx.preAuthorized() ? "pre-authorized" : "direct";
    }

    public record ToolCallRequest(String toolCallId, String toolName, Map<String, Object> input) {}

    /**
     * O9: inputSchema 为空（无 properties）的工具打 debug 日志——
     * 使"校验/Schema 提示不生效"的暴露面在日志中可见，便于发现 schema 缺失的适配器。
     */
    private void logEmptySchemaIfNeeded(Tool tool, String toolName) {
        try {
            Map<String, Object> schema = tool.inputSchema();
            boolean hasProperties = schema != null
                    && schema.get("properties") instanceof Map<?, ?> props
                    && !props.isEmpty();
            if (!hasProperties) {
                log.debug("工具 [{}] 未提供有效 inputSchema（O9 暴露面可见）: "
                        + "JSON Schema 校验与 SchemaHintBuilder 对其不生效", toolName);
            }
        } catch (Exception ignored) {
            // 日志仅为可见性辅助，不影响工具执行
        }
    }

    // ============ H5-步骤5: 写操作前快照触发 ============

    /**
     * 写类工具首次执行前触发工作区快照。
     *
     * <p>判定规则：
     * <ul>
     *   <li>{@code isReadOnly()==false} 的工具视为写操作</li>
     *   <li>每轮每目录至多一次（snapshottedThisTurn 去重）</li>
     *   <li>异常静默不阻塞工具执行（对齐 hermes tool_executor.py L1246-1247）</li>
     * </ul>
     *
     * <p>检查点对 LLM 完全不可见——不是工具、不进 prompt（hermes 核心设计约束）。
     */
    private void triggerWriteSnapshot(Tool tool, ToolCallRequest request) {
        if (tool.isReadOnly() || checkpointCollector == null) {
            return;
        }

        try {
            String workdir = extractWorkdir(request.input());
            if (workdir != null && !workdir.isEmpty()
                    && snapshottedThisTurn.add(workdir)) {
                checkpointCollector.ensureWorkspaceSnapshot(workdir,
                        "before " + request.toolName());
                log.debug("工作区快照已触发: workdir={}, tool={}", workdir, request.toolName());
            }
        } catch (Exception ignored) {
            // 快照异常静默——绝不阻塞工具执行（hermes 核心设计约束）
            log.debug("工作区快照触发失败（不阻塞工具执行）: tool={}", request.toolName(), ignored);
        }
    }

    /**
     * 从工具输入参数中提取工作区路径。
     * 依次尝试常见键名：path, workdir, directory, working_dir, filePath, cwd。
     */
    private String extractWorkdir(Map<String, Object> input) {
        if (input == null) return null;

        // 依次尝试常见路径键名
        for (String key : new String[]{"path", "workdir", "directory", "working_dir", "filePath", "cwd"}) {
            Object val = input.get(key);
            if (val instanceof String s && !s.isEmpty()) {
                // 如果是文件路径，取其父目录
                java.nio.file.Path p = java.nio.file.Path.of(s);
                if (!java.nio.file.Files.isDirectory(p)) {
                    p = p.getParent();
                }
                if (p != null) {
                    return p.toAbsolutePath().normalize().toString();
                }
                return java.nio.file.Path.of(s).toAbsolutePath().normalize().toString();
            }
        }

        // 回退到当前工作目录
        return System.getProperty("user.dir");
    }
}
