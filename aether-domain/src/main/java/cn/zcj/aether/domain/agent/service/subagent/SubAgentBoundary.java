package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 子Agent隔离边界。
 * 为每个子任务创建隔离的 AgentConfig：超时 60s（CancelToken 墙钟约束），
 * 禁用检查点和缓存，确保子Agent在受控环境中执行。
 *
 * <p>注：本项目未实现子Agent专属的轮数上限——{@link AgentConfig} 无轮数字段，
 * 轮数统一由 {@code ReActAgent.MAX_TURNS}（全局常量 100）约束。</p>
 */
@Slf4j
@Component
public class SubAgentBoundary {
    private static final long SUB_AGENT_TIMEOUT_SECONDS = 60;

    /**
     * 创建隔离的子Agent配置（向后兼容）。
     */
    public AgentConfig createIsolatedConfig(String parentAgentName, String taskDescription,
            List<String> allowedToolNames, String modelRef) {
        return createIsolatedConfig(parentAgentName, taskDescription, allowedToolNames,
                modelRef, null, null);
    }

    /**
     * 创建隔离的子Agent配置（M3增强版）。
     *
     * @param parentAgentName 父Agent名称（用于生成子Agent名）
     * @param taskDescription 子任务描述
     * @param allowedToolNames 允许使用的工具名称列表（null 或空 = 无工具）
     * @param modelRef 模型引用
     * @param taskId 任务ID（M3新增，用于命名空间隔离）
     * @param toolDenylist 工具黑名单（M3新增，子Agent被禁止使用的工具）
     * @return 隔离的 AgentConfig
     */
    public AgentConfig createIsolatedConfig(String parentAgentName, String taskDescription,
            List<String> allowedToolNames, String modelRef,
            String taskId, List<String> toolDenylist) {
        // M3: taskId 命名空间隔离
        String subName;
        if (taskId != null && !taskId.isBlank()) {
            subName = parentAgentName + "-" + taskId + "-" + UUID.randomUUID().toString().substring(0, 6);
        } else {
            subName = parentAgentName + "-sub-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String instruction = """
            你是子任务执行Agent。仅执行以下任务，完成后立即返回结果。
            不进行额外探索，不调用任务范围外的工具。
            返回格式：先陈述结论，再列出关键发现。

            任务：%s
            """.formatted(taskDescription);
        log.debug("SubAgentBoundary: 创建隔离配置 subName={} taskId={} tools={} denylist={}",
                subName, taskId, allowedToolNames, toolDenylist);
        return AgentConfig.builder()
                .name(subName).instruction(instruction)
                .toolNames(allowedToolNames != null ? allowedToolNames : List.of())
                .toolDenylist(toolDenylist)
                .modelRef(modelRef).agentType("react")
                .checkpointEnabled(false).cacheEnabled(false)
                .cancelToken(new CancelToken(Instant.now().plusSeconds(SUB_AGENT_TIMEOUT_SECONDS)))
                .build();
    }
}
