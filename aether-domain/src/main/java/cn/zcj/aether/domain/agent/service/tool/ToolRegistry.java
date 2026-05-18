package cn.zcj.aether.domain.agent.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 */
@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("Tool registered: {}", tool.name());
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> getToolsForNames(List<String> names) {
        return names.stream()
                .map(tools::get)
                .filter(t -> t != null)
                .toList();
    }

    public List<Tool> getAll() {
        return List.copyOf(tools.values());
    }
}
