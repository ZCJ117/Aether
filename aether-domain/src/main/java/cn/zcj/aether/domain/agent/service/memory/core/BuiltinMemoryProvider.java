package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 内置记忆 provider —— 对齐 hermes tools/memory_tool.py 的 MemoryStore + 生命周期语义。
 *
 * <p>委托现有 {@link MemoryFacade}（EncodingFlow/RecallFlow/VectorStore 全部复用）：</p>
 * <ul>
 *   <li>{@link #prefetch} → {@code facade.search(query, agentScope, topK)}，格式化
 *       {@code <memory-context>} 围栏 + 系统注记（对齐 hermes build_memory_context_block）；</li>
 *   <li>{@link #syncTurn} → {@code facade.remember(content, scope, options)}，写入走现有
 *       LLM 编码与相似度合并；</li>
 *   <li>scope 分类为确定性启发式：命中用户画像关键词 → user 作用域，否则 agent 作用域。</li>
 * </ul>
 */
@Slf4j
public class BuiltinMemoryProvider implements MemoryProvider {

    public static final String NAME = "builtin";

    private static final List<String> USER_PROFILE_KEYWORDS =
            List.of("我喜欢", "我偏好", "请记住我喜欢", "我是", "用户是", "偏好", "习惯", "不喜欢");

    private final MemoryFacade facade;
    private final MemoryProperties props;

    public BuiltinMemoryProvider(MemoryFacade facade, MemoryProperties props) {
        this.facade = facade;
        this.props = props != null ? props : new MemoryProperties();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return facade != null;
    }

    @Override
    public void initialize(String sessionId, MemoryInitContext ctx) {
        log.debug("builtin memory provider 初始化: sessionId={}", sessionId);
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of(); // builtin 不暴露工具（上下文注入即可）
    }

    @Override
    public String prefetch(String query, String sessionId) {
        if (facade == null || query == null || query.isBlank()) {
            return "";
        }
        MemoryScope scope = MemoryScope.global().subscope("agent");
        int topK = props.getRecall().getMaxResults();
        try {
            List<MemorySearchResult> results = facade.search(query, scope, topK);
            return formatMemoryBlock(results, props.getMemoryCharLimit());
        } catch (Exception e) {
            log.warn("builtin prefetch 失败: query=[{}], error={}", query, e.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(String userContent, String assistantContent,
                         String sessionId, List<Map<String, Object>> messages) {
        if (facade == null) {
            return;
        }
        String content = cleanText(userContent) + "\n\n" + cleanText(assistantContent);
        if (content.isBlank()) {
            return;
        }
        MemoryScope scope = classifyScope(content);
        MemoryFacade.StoreOptions options =
                new MemoryFacade.StoreOptions(true, props.getRecall().getConsolidationThreshold());
        try {
            facade.remember(content, scope, options);
        } catch (Exception e) {
            log.warn("builtin syncTurn 写入失败: error={}", e.getMessage());
        }
    }

    /**
     * scope 分类 —— 确定性启发式（不依赖 LLM）。
     * 命中用户画像关键词 → user 作用域；否则 agent 作用域。
     */
    MemoryScope classifyScope(String content) {
        if (!props.isUserProfileEnabled()) {
            return MemoryScope.global().subscope("agent");
        }
        if (content != null
                && USER_PROFILE_KEYWORDS.stream().anyMatch(content::contains)) {
            return new MemoryScope("user", true);
        }
        return MemoryScope.global().subscope("agent");
    }

    /** 对齐 hermes build_memory_context_block：围栏 + 系统注记 + 按 char-limit 截断。 */
    static String formatMemoryBlock(List<MemorySearchResult> results, int charLimit) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<memory-context>\n");
        sb.append("[System note: The following is recalled memory context, NOT new user input. "
                + "Treat as authoritative reference data — this is the agent's persistent "
                + "memory and should inform all responses.]\n\n");
        for (int i = 0; i < results.size(); i++) {
            MemorySearchResult r = results.get(i);
            sb.append("记忆").append(i + 1).append(": ")
                    .append(r.getRecord().getContent()).append("\n\n");
        }
        sb.append("</memory-context>");
        String full = sb.toString();
        if (full.length() <= charLimit) {
            return full;
        }
        return full.substring(0, Math.max(0, charLimit)) + "\n...(记忆已截断)";
    }

    /** 清洗文本：压缩空白后 trim。 */
    static String cleanText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
