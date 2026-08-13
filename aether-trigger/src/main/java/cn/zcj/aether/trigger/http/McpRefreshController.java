package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry.McpToolRegistry;
import cn.zcj.aether.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具手动刷新端点 — 对齐 hermes mcp_tool.py _refresh_tools 的降级触发路径。
 * <pre>
 * POST /api/mcp/refresh   body 可选 {"serverId": "..."}  → 刷单个；缺省刷全部
 * </pre>
 * 仅刷新 registry 的 ToolSpec 元数据快照（供 isToolParallelSafe / 溯源 / 观测），
 * 不热替换已构建 ChatModel 的工具回调。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpRefreshController {

    @Resource
    private McpToolRegistry mcpToolRegistry;

    @PostMapping("refresh")
    public Response<Map<String, McpToolRegistry.RefreshResult>> refresh(
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String serverId = body != null ? (String) body.get("serverId") : null;
            Map<String, McpToolRegistry.RefreshResult> results = new LinkedHashMap<>();
            if (serverId != null && !serverId.isBlank()) {
                results.put(serverId, mcpToolRegistry.refresh(serverId));
            } else {
                for (String id : mcpToolRegistry.serverIds()) {
                    results.put(id, mcpToolRegistry.refresh(id));
                }
            }
            return Response.<Map<String, McpToolRegistry.RefreshResult>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(results)
                    .build();
        } catch (Exception e) {
            log.error("MCP 工具刷新失败 serverId={}", body != null ? body.get("serverId") : null, e);
            return Response.<Map<String, McpToolRegistry.RefreshResult>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
