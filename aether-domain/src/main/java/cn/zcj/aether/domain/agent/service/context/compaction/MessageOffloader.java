package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * JSONL 对话泄流器（AgentScope 模式）。
 * <p>
 * 将对话消息追加写入 JSONL 文件，支持后续审计、调试和检索。
 * 大工具结果（超过 5000 字符）的内容单独写入文件，JSONL 行中仅保留文件引用。
 * </p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>文件路径：{@code .aether/sessions/{sessionId}.jsonl}（相对于 user.dir）</li>
 *   <li>追加写入（O(1)，不重写整个文件）</li>
 *   <li>每行为一个 JSON 对象：{@code {ts, turn, role, len, text}} 或
 *       {@code {ts, turn, role, len, tool, file}}（工具结果）</li>
 *   <li>大工具结果（&gt;5000 字符）内容保存至独立文件，JSONL 行引用之</li>
 *   <li>异步安全：捕获 IOException，记录警告，永不抛出</li>
 * </ul>
 */
@Slf4j
@Component
public class MessageOffloader {

    private static final int LARGE_TOOL_RESULT_THRESHOLD = 5_000;
    private static final String SESSIONS_DIR = ".aether/sessions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将对话消息追加写入 JSONL 文件。
     *
     * @param sessionId 会话 ID
     * @param messages  待泄流的消息列表
     * @param startTurn 起始回合号（messages 中第一条消息对应的 turn 编号）
     */
    public void offload(String sessionId, List<TurnMessage> messages, int startTurn) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("MessageOffloader: sessionId 为空，跳过泄流");
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        try {
            Path sessionsDir = Path.of(System.getProperty("user.dir"), SESSIONS_DIR);
            Files.createDirectories(sessionsDir);

            Path jsonlFile = sessionsDir.resolve(sanitizeFileName(sessionId) + ".jsonl");
            StringBuilder batch = new StringBuilder();

            int turn = startTurn;
            for (TurnMessage msg : messages) {
                String line = formatLine(msg, turn, sessionId, sessionsDir);
                batch.append(line).append('\n');
                turn++;
            }

            Files.writeString(jsonlFile, batch.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("MessageOffloader: 泄流 {} 条消息到 {}", messages.size(), jsonlFile);
        } catch (IOException e) {
            log.warn("MessageOffloader: 泄流失败 sessionId={}, 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * O8: 将超长工具结果写盘，返回相对于会话目录的文件引用路径。
     *
     * <p>写入 {@code .aether/sessions/tool-overflow/<toolName>_<内容hash>.txt}；
     * 同一内容（hash 相同）复用已有文件，避免重复落盘。</p>
     *
     * @param toolCallId 工具调用 ID（仅用于日志）
     * @param toolName   工具名（用于文件命名）
     * @param content    完整工具输出内容
     * @return 可回读的文件路径引用（相对路径字符串）；写盘失败返回 null（调用方降级为截断）
     */
    public String offloadToolResult(String toolCallId, String toolName, String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            Path sessionsDir = Path.of(System.getProperty("user.dir"), SESSIONS_DIR);
            Path overflowDir = sessionsDir.resolve("tool-overflow");
            Files.createDirectories(overflowDir);

            String safeTool = sanitizeFileName(toolName != null ? toolName : "unknown");
            String contentHash = Integer.toHexString(content.hashCode());
            Path toolFile = overflowDir.resolve(safeTool + "_" + contentHash + ".txt");

            if (!Files.exists(toolFile)) {
                Files.writeString(toolFile, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            String reference = sessionsDir.relativize(toolFile).toString().replace('\\', '/');
            log.debug("MessageOffloader: 超长工具结果已存盘 toolCallId={}, tool={}, file={}",
                    toolCallId, toolName, reference);
            return reference;
        } catch (IOException e) {
            log.warn("MessageOffloader: 超长工具结果写盘失败 toolCallId={}, tool={}, 降级为截断: {}",
                    toolCallId, toolName, e.getMessage());
            return null;
        }
    }

    /**
     * 将单条 TurnMessage 格式化为一行 JSON。
     */
    private String formatLine(TurnMessage msg, int turn, String sessionId, Path sessionsDir) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("turn", turn);
        node.put("role", msg.role());

        if (msg.isToolResult()) {
            return formatToolResultLine(msg, turn, sessionId, sessionsDir);
        }

        String content = msg.content() != null ? msg.content() : "";
        node.put("len", content.length());
        node.put("text", content);
        return node.toString();
    }

    /**
     * 格式化工具结果行。
     * 若内容超过阈值，将内容写入独立文件，JSONL 行只记录元数据和文件路径。
     */
    private String formatToolResultLine(TurnMessage msg, int turn, String sessionId, Path sessionsDir) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("turn", turn);
        node.put("role", "tool_result");

        String content = msg.content() != null ? msg.content() : "";
        String toolName = msg.toolName() != null ? msg.toolName() : "unknown";

        node.put("tool", toolName);
        node.put("len", content.length());

        if (content.length() > LARGE_TOOL_RESULT_THRESHOLD) {
            // 大工具结果 → 写入独立文件
            try {
                String safeSid = sanitizeFileName(sessionId);
                Path toolResultsDir = sessionsDir.resolve(safeSid + "_tool_results");
                Files.createDirectories(toolResultsDir);

                String fileName = String.format("turn_%04d_%s.json", turn, sanitizeFileName(toolName));
                Path toolFile = toolResultsDir.resolve(fileName);
                Files.writeString(toolFile, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                node.put("file", sessionsDir.relativize(toolFile).toString());
                // 不包含 text 字段，避免 JSONL 行过大
            } catch (IOException e) {
                log.warn("MessageOffloader: 大工具结果文件写入失败 turn={} tool={}, 降级为内联",
                        turn, toolName, e);
                node.put("text", content);
            }
        } else {
            node.put("text", content);
        }

        return node.toString();
    }

    /**
     * 清理文件名中的非法字符，防止路径遍历。
     */
    private String sanitizeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
