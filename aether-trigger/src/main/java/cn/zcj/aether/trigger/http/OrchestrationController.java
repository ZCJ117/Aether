package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.subagent.DelegationRecord;
import cn.zcj.aether.domain.agent.service.subagent.ExecutionControlService;
import cn.zcj.aether.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 编排实时控制端点 — 对齐 hermes delegate_tool.py TUI 能力，复用现有 JWT 过滤链。
 * <pre>
 * GET  /api/orchestration/active-subagents          → 活跃子Agent列表
 * POST /api/orchestration/subagents/{id}/interrupt  → 中断子Agent
 * POST /api/orchestration/spawn/pause               → 暂停/恢复新 spawn（body: {"paused": bool}）
 * GET  /api/orchestration/delegations?sessionId=    → 委派查询
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestration/")
public class OrchestrationController {

    @Resource
    private ExecutionControlService executionControlService;

    @GetMapping("active-subagents")
    public Response<List<ExecutionControlService.ActiveSubAgentView>> activeSubAgents() {
        try {
            List<ExecutionControlService.ActiveSubAgentView> data = executionControlService.listActiveSubAgents();
            return Response.<List<ExecutionControlService.ActiveSubAgentView>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询活跃子Agent失败", e);
            return Response.<List<ExecutionControlService.ActiveSubAgentView>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("subagents/{id}/interrupt")
    public Response<Boolean> interrupt(@PathVariable("id") String id) {
        try {
            boolean hit = executionControlService.interruptSubAgent(id);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(hit)
                    .build();
        } catch (Exception e) {
            log.error("中断子Agent失败 id={}", id, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("spawn/pause")
    public Response<Boolean> setSpawnPaused(@RequestBody(required = false) Map<String, Object> body) {
        try {
            boolean paused = body != null && Boolean.TRUE.equals(body.get("paused"));
            boolean newState = executionControlService.setSpawnPaused(paused);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(newState)
                    .build();
        } catch (Exception e) {
            log.error("设置 spawn 暂停失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("delegations")
    public Response<List<DelegationRecord>> delegations(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            List<DelegationRecord> data = executionControlService.listDelegations(sessionId);
            return Response.<List<DelegationRecord>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询委派列表失败 sessionId={}", sessionId, e);
            return Response.<List<DelegationRecord>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
