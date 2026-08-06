package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.List;
import java.util.Map;

/**
 * 可插拔记忆提供者 SPI —— 对齐 hermes agent/memory_provider.py 的 MemoryProvider ABC。
 *
 * <p>方法名按 Java 惯例 camelCase，语义与 hermes 完全一致：</p>
 * <pre>
 *   initialize()          连接/建资源/预热
 *   systemPromptBlock()   注入系统提示的静态说明
 *   prefetch(query)       turn 前后台召回（返回待注入上下文文本）
 *   queuePrefetch(query)  turn 后为下一轮入队后台召回
 *   syncTurn(user, asst)  turn 后异步持久化
 *   getToolSchemas()      暴露给模型的 tool schema（OpenAI function-calling 格式）
 *   handleToolCall()      分发一次 tool 调用，返回 JSON 字符串
 *   shutdown()            干净退出
 * </pre>
 * <p>可选钩子（默认 no-op，按需覆写）：{@code onTurnStart} / {@code onSessionEnd} /
 * {@code onSessionSwitch} / {@code onPreCompress} / {@code onMemoryWrite}。</p>
 */
public interface MemoryProvider {

    /** 提供者短标识，如 "builtin" */
    String name();

    /** 是否已配置且可激活；不应发起网络调用 */
    boolean isAvailable();

    /** 会话级初始化，启动时调用一次 */
    void initialize(String sessionId, MemoryInitContext ctx);

    /** 系统提示中的静态说明块（默认空串） */
    default String systemPromptBlock() {
        return "";
    }

    /** turn 前召回，返回待注入上下文文本（空串=无相关） */
    default String prefetch(String query, String sessionId) {
        return "";
    }

    /** turn 后为下一轮入队后台召回（默认 no-op） */
    default void queuePrefetch(String query, String sessionId) {
    }

    /** turn 后持久化（应非阻塞/异步） */
    default void syncTurn(String userContent, String assistantContent,
                          String sessionId, List<Map<String, Object>> messages) {
    }

    /** 对外暴露的 tool schema；无则返回空表 */
    List<Map<String, Object>> getToolSchemas();

    /** 处理本 provider 的 tool 调用，返回 JSON 字符串 */
    default String handleToolCall(String toolName, Map<String, Object> args) {
        throw new UnsupportedOperationException(
                "Provider " + name() + " 不处理工具 " + toolName);
    }

    /** 干净退出：flush 队列、关闭连接 */
    default void shutdown() {
    }

    // ===================== 可选钩子 =====================

    /** 每轮开始回调（kwargs 可含 remainingTokens/model/platform/toolCount） */
    default void onTurnStart(int turnNumber, String message, Map<String, Object> kwargs) {
    }

    /** 会话结束时回调（CLI 退出 / /reset / 网关会话过期），非每轮触发 */
    default void onSessionEnd(List<Map<String, Object>> messages) {
    }

    /** 会话切换时回调（/resume /branch /new /压缩重续） */
    default void onSessionSwitch(String newSessionId, String parentSessionId,
                                 boolean reset, boolean rewound, Map<String, Object> kwargs) {
    }

    /** 上下文压缩前回调，返回需并入压缩摘要的文本（默认空串） */
    default String onPreCompress(List<Map<String, Object>> messages) {
        return "";
    }

    /** 内置记忆工具写入时镜像（action: add/replace/remove，target: memory/user） */
    default void onMemoryWrite(String action, String target, String content,
                               Map<String, Object> metadata) {
    }
}
