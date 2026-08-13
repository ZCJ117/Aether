package cn.zcj.aether.domain.agent.service.subagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 编排实时控制面 — 对齐 hermes delegate_tool.py TUI 能力（interrupt / list / spawn pause / delegation 查询）。
 * <p>纯委托：复用 D1 的 SubagentLifecycleService.cancel / SpawnGate / AsyncDelegationService.listBySession。</p>
 */
@Slf4j
@Service
public class ExecutionControlService {

    /** 活跃子Agent视图（供控制面/前端展示）。parentAgentId 恒 null（ToolContext 无当前 agentId）。 */
    public record ActiveSubAgentView(String id, String status, String sessionId,
                                     String goal, String parentAgentId) {}

    private final SubagentLifecycleService lifecycle;
    private final SpawnGate spawnGate;
    private final AsyncDelegationService asyncDelegation;

    public ExecutionControlService(SubagentLifecycleService lifecycle,
                                   SpawnGate spawnGate,
                                   AsyncDelegationService asyncDelegation) {
        this.lifecycle = lifecycle;
        this.spawnGate = spawnGate;
        this.asyncDelegation = asyncDelegation;
    }

    /** 当前活跃子Agent视图列表。 */
    public List<ActiveSubAgentView> listActiveSubAgents() {
        List<ActiveSubAgentView> views = new ArrayList<>();
        for (String id : lifecycle.activeIds()) {
            String status = lifecycle.status(id).map(Object::toString).orElse("?");
            Optional<DelegationTask> task = lifecycle.taskOf(id);
            String sessionId = task.map(t -> t.parentSessionId()).orElse(null);
            String goal = task.map(t -> t.task()).orElse(null);
            views.add(new ActiveSubAgentView(id, status, sessionId, goal, null));
        }
        return views;
    }

    /** 中断单个子Agent；返回是否命中（对齐 hermes interrupt_subagent 返回布尔）。 */
    public boolean interruptSubAgent(String id) {
        boolean accepted = lifecycle.cancel(id);
        if (!accepted) {
            log.warn("ExecutionControlService: interrupt 未命中 id={}", id);
        }
        return accepted;
    }

    /** 全局暂停/恢复新 spawn（对齐 hermes set_spawn_paused）。 */
    public boolean setSpawnPaused(boolean paused) {
        spawnGate.setSpawnPaused(paused);
        return spawnGate.isSpawnPaused();
    }

    /** 查询委派列表；sessionId 为空时返回空列表（运行时视图由 listActiveSubAgents 提供）。 */
    public List<DelegationRecord> listDelegations(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return asyncDelegation.listBySession(sessionId);
    }
}
