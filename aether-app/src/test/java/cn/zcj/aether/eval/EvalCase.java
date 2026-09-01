package cn.zcj.aether.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * P0(4.1) Eval 用例模型（cases.jsonl 每行一个 case，Jackson 反序列化）。
 * 字段按 category 取用：tool_selection / multi_step / context_retention / permission。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    public String id;
    public String category;
    public String instruction;

    // ── tool_selection / multi_step / permission(flow) ──
    public String userMessage;
    public List<ToolDef> tools;
    public String expectedTool;                       // tool_selection：断言首轮选中工具
    public List<String> expectedToolSequence;         // multi_step：断言工具调用顺序
    public List<String> finalTextContains;            // 最终文本须包含的片段

    // ── multi_step：逐轮脚本（toolCall 或 纯文本收尾）──
    public List<StepDef> steps;

    // ── context_retention ──
    public List<HistoryMsg> history;                  // 长对话历史（user/assistant 交替）
    public String keyFact;                            // 须在压缩后保留的关键信息
    public String summaryText;                        // 脚本化摘要内容（含 keyFact 视为"信息未丢失"）
    public String tailMessage;                        // 尾部保护消息（须原文保留）

    // ── permission ──
    public String toolName;
    public Map<String, Object> toolInput;
    public Boolean readOnly;
    public String expectedDecision;                   // ALLOW / ASK_USER / DENY（engine 级断言）
    public boolean flow;                              // true = ReActAgent 挂起-拒绝/批准-恢复全流程

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolDef {
        public String name;
        public String description;
        public boolean readOnly;
        public Map<String, Object> inputSchema;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepDef {
        public ToolCallDef toolCall;
        public String text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallDef {
        public String name;
        public Map<String, Object> input;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryMsg {
        public String role;
        public String content;
    }

    @Override
    public String toString() {
        return id + " (" + category + ")";
    }
}
