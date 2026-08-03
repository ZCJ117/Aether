package cn.zcj.aether.domain.agent.service.executor;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.List;
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

    // ========== M1 新增：消息路由邮箱 ==========

    /**
     * 订阅者私有邮箱映射（agentName → 有界阻塞队列）。
     * 容量 100，防内存泄漏。
     */
    /** M1: 订阅者私有邮箱映射 (agentName -> 有界阻塞队列). 容量100. */
    final Map<String, java.util.concurrent.BlockingQueue<MessageEnvelope>> agentMailboxes
            = new ConcurrentHashMap<>();

    /**
     * 获取或创建 Agent 的私有邮箱。
     *
     * @param agentName Agent 名称
     * @return 该 Agent 的阻塞队列邮箱（有界，容量 100）
     */
    public java.util.concurrent.BlockingQueue<MessageEnvelope> getOrCreateMailbox(String agentName) {
        return agentMailboxes.computeIfAbsent(agentName,
                k -> new java.util.concurrent.LinkedBlockingQueue<>(100));
    }

    /**
     * 将消息路由到指定 Agent 的邮箱。
     *
     * @param agentName 目标 Agent 名称
     * @param message   消息信封
     * @return true 投递成功，false 邮箱满
     */
    public boolean routeToMailbox(String agentName, MessageEnvelope message) {
        java.util.concurrent.BlockingQueue<MessageEnvelope> mailbox = getOrCreateMailbox(agentName);
        boolean offered = mailbox.offer(message);
        if (!offered) {
            log.warn("Agent [{}] 邮箱已满，消息丢弃: topic={}", agentName, message.topic());
        }
        return offered;
    }

    /**
     * 从 Agent 邮箱中排出所有消息并清空。
     *
     * @param agentName Agent 名称
     * @param timeoutMs 等待超时毫秒（0 = 不等待，仅排空已有消息）
     * @return 消息列表（按接收顺序）
     */
    public List<MessageEnvelope> drainMailbox(String agentName, long timeoutMs) {
        java.util.concurrent.BlockingQueue<MessageEnvelope> mailbox = agentMailboxes.get(agentName);
        if (mailbox == null) return List.of();
        return new SubscriptionRouter().drain(mailbox, timeoutMs);
    }
}
