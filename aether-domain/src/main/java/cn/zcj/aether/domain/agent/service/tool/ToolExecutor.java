package cn.zcj.aether.domain.agent.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具编排执行器
 *
 * 参考项目B toolOrchestration.ts:91-115 的分区算法:
 *   - 并发安全工具 → 并行执行
 *   - 非并发安全工具 → 串行执行
 */
@Slf4j
@Service
public class ToolExecutor {

    @Resource
    private ToolRegistry toolRegistry;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public List<ToolResult> executeBatch(List<ToolCallRequest> requests, String userId, String sessionId) {
        // 按 isConcurrencySafe 分区
        List<ToolCallRequest> safe = new ArrayList<>();
        List<ToolCallRequest> unsafe = new ArrayList<>();

        for (ToolCallRequest req : requests) {
            Tool tool = toolRegistry.get(req.toolName);
            if (tool != null && tool.isConcurrencySafe()) {
                safe.add(req);
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
                    try { return f.get(60, TimeUnit.SECONDS); }
                    catch (Exception e) { return ToolResult.error("", "", "Tool timeout: " + e.getMessage()); }
                })
                .toList();
    }

    private List<ToolResult> executeSerially(List<ToolCallRequest> requests, ToolContext ctx) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCallRequest req : requests) {
            results.add(executeOne(req, ctx));
        }
        return results;
    }

    private ToolResult executeOne(ToolCallRequest request, ToolContext ctx) {
        try {
            Tool tool = toolRegistry.get(request.toolName);
            if (tool == null) {
                return ToolResult.error(request.toolCallId, request.toolName,
                        "Tool not found: " + request.toolName);
            }
            ToolContext perCallCtx = new ToolContext(
                    ctx.userId(), ctx.sessionId(), request.toolCallId);
            return tool.call(request.input, perCallCtx);
        } catch (Exception e) {
            log.error("Tool execution error: {}", request.toolName, e);
            return ToolResult.error(request.toolCallId, request.toolName, e.getMessage());
        }
    }

    public record ToolCallRequest(String toolCallId, String toolName, Map<String, Object> input) {}
}
