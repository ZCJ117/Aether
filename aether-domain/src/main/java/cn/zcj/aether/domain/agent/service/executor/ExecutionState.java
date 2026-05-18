package cn.zcj.aether.domain.agent.service.executor;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图执行状态 — 跨Agent输出传递
 *
 * 支持 {outputKey} 模板变量解析
 * 原 Google ADK 的 outputKey 机制通过此状态对象实现
 */
@Slf4j
public class ExecutionState {

    private final Map<String, StringBuilder> outputs = new ConcurrentHashMap<>();
    private final Map<String, String> finalOutputs = new ConcurrentHashMap<>();
    private String lastAgentName;

    public void appendOutput(String key, String text) {
        if (key != null) {
            outputs.computeIfAbsent(key, k -> new StringBuilder()).append(text);
        }
    }

    public void setFinalOutput(String key, String output) {
        if (key != null) {
            finalOutputs.put(key, output);
        }
    }

    public String getLastOutput() {
        if (lastAgentName != null) {
            return finalOutputs.getOrDefault(lastAgentName,
                    outputs.getOrDefault(lastAgentName, new StringBuilder()).toString());
        }
        return "";
    }

    public void setLastAgentName(String name) {
        this.lastAgentName = name;
    }

    /**
     * 解析模板变量 — 将 {outputKey} 替换为对应Agent的输出
     */
    public String resolveTemplate(String instruction) {
        if (instruction == null) return "";
        String result = instruction;

        for (Map.Entry<String, String> entry : finalOutputs.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        for (Map.Entry<String, StringBuilder> entry : outputs.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue().toString());
        }

        return result;
    }

    public String getOutput(String key) {
        return finalOutputs.getOrDefault(key,
                outputs.containsKey(key) ? outputs.get(key).toString() : "");
    }

    /**
     * 创建当前状态的浅拷贝（并行执行时使用）
     */
    public ExecutionState fork() {
        ExecutionState forked = new ExecutionState();
        forked.finalOutputs.putAll(this.finalOutputs);
        // StringBuilder 不拷贝 — 并行执行各自持有独立的输出收集器
        return forked;
    }

    /** 合并并行执行结果 */
    public void merge(ExecutionState other, String outputKey) {
        if (outputKey != null && other.finalOutputs.containsKey(outputKey)) {
            this.finalOutputs.put(outputKey, other.finalOutputs.get(outputKey));
        }
        this.outputs.putAll(other.outputs);
    }

    /**
     * 创建执行源（并行执行专用）— 独立的输出收集器，但继承 parent 的 finalOutputs
     */
    public ExecutionState forkSource() {
        ExecutionState forked = new ExecutionState();
        forked.finalOutputs.putAll(this.finalOutputs);
        return forked;
    }

    /**
     * 获取指定 key 的文本输出
     */
    public String getText(String key) {
        if (key == null) return "";
        return outputs.getOrDefault(key, new StringBuilder()).toString();
    }

    /**
     * 标记完成 — 将 text buffer 中的内容固化到 finalOutputs
     */
    public void markComplete(String key, String text) {
        if (key != null && text != null && !text.isBlank()) {
            finalOutputs.put(key, text);
        }
    }
}
