package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务提醒中间件。
 * 在 onSystemPrompt 拦截点注入任务背景信息，帮助 Agent 更好理解任务上下文。
 *
 * 使用场景：
 * - 在系统提示词中注入截止时间、优先级等任务元信息
 * - 从 RuntimeContext.metadata 中提取 reminder/taskInfo 等内容
 */
@Slf4j
public class TaskReminderMiddleware implements AgentMiddleware {

    @Override
    public String name() {
        return "task-reminder";
    }

    @Override
    public int priority() {
        return 90; // 在 RateLimit(10) 和 Permission(20) 之后执行
    }

    @Override
    public String onSystemPrompt(String systemPrompt, Agent agent, RuntimeContext ctx) {
        if (ctx.metadata() == null) return systemPrompt;

        // 从 metadata 中提取提醒信息
        String reminder = (String) ctx.metadata().get("reminder");
        String taskInfo = (String) ctx.metadata().get("taskInfo");

        StringBuilder enriched = new StringBuilder(systemPrompt != null ? systemPrompt : "");

        // 注入提醒信息
        if (reminder != null && !reminder.isBlank()) {
            enriched.append("\n\n<task-reminder>\n");
            enriched.append(reminder);
            enriched.append("\n</task-reminder>");
        }

        // 注入任务背景信息
        if (taskInfo != null && !taskInfo.isBlank()) {
            enriched.append("\n\n<task-context>\n");
            enriched.append(taskInfo);
            enriched.append("\n</task-context>");
        }

        if (reminder != null || taskInfo != null) {
            log.debug("任务提醒中间件已注入: agentId={}, hasReminder={}, hasTaskInfo={}",
                agent.getId(), reminder != null, taskInfo != null);
        }

        return enriched.toString();
    }
}
