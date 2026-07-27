package cn.zcj.aether.domain.agent.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 最小工具集策略 —— 硬限制每个 Agent 最多 15 个工具，超限时按优先级裁剪。
 */
@Slf4j
@Component
public class MinimalToolSet {

    public static final int MAX_TOOLS_PER_AGENT = 15;

    private static final Set<String> CORE_READ_TOOLS = Set.of(
            "Read", "Glob", "Grep", "code_search", "file_read", "doc_read",
            "session_search", "todo_write", "note_write");

    private static final Set<String> WRITE_TOOLS = Set.of(
            "Edit", "Write", "Bash", "FileEdit", "FileWrite",
            "BashTool", "FileEditTool", "FileWriteTool");

    public List<Tool> enforceLimit(List<Tool> tools) {
        if (tools.size() <= MAX_TOOLS_PER_AGENT) {
            return new ArrayList<>(tools);
        }

        List<Tool> essential = new ArrayList<>();
        List<Tool> auxiliary = new ArrayList<>();
        List<Tool> mcpSkills = new ArrayList<>();

        for (Tool tool : tools) {
            String name = tool.name();
            if (WRITE_TOOLS.contains(name) || CORE_READ_TOOLS.contains(name)) {
                essential.add(tool);
            } else if (name.startsWith("mcp_") || name.startsWith("skill_")) {
                mcpSkills.add(tool);
            } else {
                auxiliary.add(tool);
            }
        }

        List<Tool> result = new ArrayList<>(essential);
        int remaining = MAX_TOOLS_PER_AGENT - result.size();

        if (remaining > 0) {
            int aux = Math.min(auxiliary.size(), remaining);
            result.addAll(auxiliary.subList(0, aux));
            remaining -= aux;
        }

        if (remaining > 0) {
            int mcp = Math.min(mcpSkills.size(), remaining);
            result.addAll(mcpSkills.subList(0, mcp));
        }

        int dropped = tools.size() - result.size();
        if (dropped > 0) {
            log.warn("MinimalToolSet: {}→{} (丢弃 {} 个)", tools.size(), result.size(), dropped);
        }

        return result;
    }
}
