package cn.zcj.aether.domain.agent.service.tool;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.tool.validation.SchemaHintBuilder;
import cn.zcj.aether.domain.agent.service.tool.validation.ToolInputValidator;
import cn.zcj.aether.domain.agent.service.tool.validation.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
 *   <li>executeOne() 插入两级校验关卡（JSON Schema 校验 + 工具自定义校验 + 权限检查）</li>
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

    public List<ToolResult> executeBatch(List<ToolCallRequest> requests, String userId, String sessionId) {
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

        ToolContext ctx = new ToolContext(userId, sessionId, "");
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
        List<CompletableFuture<ToolResult>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> executeOne(req, ctx), executor))
                .toList();

        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(60, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        // P0-1：超时使用 TIMEOUT 错误类型
                        return ToolResult.error("", "",
                                "Tool timeout: " + e.getMessage(),
                                ToolResult.ErrorType.TIMEOUT);
                    } catch (Exception e) {
                        return ToolResult.error("", "", "Tool execution failed: " + e.getMessage());
                    }
                })
                .toList();
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
                CompletableFuture.supplyAsync(() -> executeOne(request, ctx), executor);
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
     * P0-1：两级校验关卡 + 原调用逻辑。
     *
     * <p>数据结构变化：校验失败的 ToolResult 与执行失败的 ToolResult
     * 走同一通道回到 ReActAgent 的消息流，LLM 下一轮读到 Schema 提示自我修正——
     * 不新增任何协议，复用现有 {@link ToolResult# error} 回传路径。
     */
    private ToolResult executeOne(ToolCallRequest request, ToolContext ctx) {
        try {
            Tool tool = toolRegistry.get(request.toolName);
            if (tool == null) {
                return ToolResult.error(request.toolCallId, request.toolName,
                        "Tool not found: " + request.toolName,
                        ToolResult.ErrorType.EXECUTION);
            }

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

            if (!tool.checkPermissions(request.input())) {
                return ToolResult.error(
                        request.toolCallId,
                        request.toolName,
                        "Permission denied for tool '" + request.toolName + "'",
                        ToolResult.ErrorType.PERMISSION);
            }

            // ====== H5-步骤5: 写操作前自动快照 ======
            triggerWriteSnapshot(tool, request);

            // ====== 原有调用逻辑不变 ======
            ToolContext perCallCtx = new ToolContext(
                    ctx.userId(), ctx.sessionId(), request.toolCallId);
            return tool.call(request.input, perCallCtx);

        } catch (Exception e) {
            log.error("Tool execution error: {}", request.toolName, e);
            return ToolResult.error(
                    request.toolCallId, request.toolName,
                    e.getMessage(),
                    ToolResult.ErrorType.EXECUTION);
        }
    }

    public record ToolCallRequest(String toolCallId, String toolName, Map<String, Object> input) {}

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
