package cn.zcj.aether.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆存储系统
 *
 * 记忆目录定位优先级：
 *   1. YAML 配置 ai.agent.config.memory-dir（支持绝对路径）
 *   2. 自动探测项目根目录（向上遍历找 pom.xml + CLAUDE.md）→ memory/
 *   3. 兜底：user.dir/memory
 */
@Slf4j
@Service
public class MemoryStore {

    private static final String MEMORY_INDEX = "MEMORY.md";

    @Value("${ai.agent.config.memory-dir:}")
    private String configuredMemoryDir;

    // 参考项目B memdir/memdir.ts:34-35
    private static final int MAX_ENTRYPOINT_LINES = 200;
    private static final int MAX_ENTRYPOINT_BYTES = 25_000;

    /**
     * 加载相关记忆，注入到Agent系统提示词
     *
     * @param userMessage 用户当前消息，用于匹配相关记忆
     * @return 记忆提示文本，或空字符串
     */
    public String loadMemoryPrompt(String userMessage) {
        Path memoryDir = resolveMemoryDir();
        if (memoryDir == null || !Files.exists(memoryDir)) {
            return "";
        }

        Path indexPath = memoryDir.resolve(MEMORY_INDEX);
        if (!Files.exists(indexPath)) {
            return "";
        }

        try {
            String rawIndex = Files.readString(indexPath);
            String indexContent = truncateEntrypoint(rawIndex);

            // 关键词匹配找出相关记忆文件
            List<String> relevantFiles = findRelevantFiles(indexContent, userMessage, memoryDir);

            StringBuilder prompt = new StringBuilder();
            prompt.append("<auto-memory>\n");
            prompt.append(indexContent).append("\n\n");

            for (String fileName : relevantFiles) {
                Path memFile = memoryDir.resolve(fileName);
                if (Files.exists(memFile) && Files.isReadable(memFile)) {
                    String content = Files.readString(memFile);
                    prompt.append(content).append("\n");
                }
            }
            prompt.append("</auto-memory>");

            return prompt.toString();
        } catch (IOException e) {
            log.warn("Failed to load memory prompt", e);
            return "";
        }
    }

    /**
     * 保存新记忆
     */
    public void saveMemory(String type, String name, String description, String content) {
        Path memoryDir = resolveMemoryDir();
        if (memoryDir == null) return;

        try {
            Files.createDirectories(memoryDir);

            String fileName = sanitizeFileName(name) + ".md";
            Path memFile = memoryDir.resolve(fileName);

            String frontmatter = String.format("""
                    ---
                    name: %s
                    description: %s
                    type: %s
                    ---

                    %s
                    """, name, description, type, content);

            Files.writeString(memFile, frontmatter);
            updateMemoryIndex(memoryDir, fileName);
            log.info("Memory saved: {}", name);
        } catch (IOException e) {
            log.error("Failed to save memory: {}", name, e);
        }
    }

    private Path resolveMemoryDir() {
        // 1) YAML 显式配置 → 直接使用
        if (configuredMemoryDir != null && !configuredMemoryDir.isBlank()) {
            Path path = Path.of(configuredMemoryDir);
            log.info("使用配置的记忆目录: {}", path.toAbsolutePath());
            return path;
        }

        // 2) 自动探测项目根目录: 从 user.dir 向上找包含 pom.xml + CLAUDE.md 的目录
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            Path current = Path.of(userDir).toAbsolutePath();
            while (current != null) {
                if (Files.exists(current.resolve("pom.xml"))
                        && Files.exists(current.resolve("CLAUDE.md"))) {
                    Path memDir = current.resolve("memory");
                    log.info("自动探测记忆目录: {}", memDir.toAbsolutePath());
                    return memDir;
                }
                Path parent = current.getParent();
                if (parent == null || parent.equals(current)) break;
                current = parent;
            }
        }

        // 3) 兜底: user.dir/memory
        if (userDir != null) {
            log.warn("无法定位项目根目录，使用兜底路径: {}/memory", userDir);
            return Path.of(userDir, "memory");
        }
        return null;
    }

    /**
     * 截断 MEMORY.md 索引文件
     * 参考项目B memdir/memdir.ts:57-80
     */
    private String truncateEntrypoint(String raw) {
        String trimmed = raw.trim();
        String[] lines = trimmed.split("\n");

        if (lines.length > MAX_ENTRYPOINT_LINES) {
            String[] truncated = Arrays.copyOf(lines, MAX_ENTRYPOINT_LINES);
            return String.join("\n", truncated) +
                    "\n... (截断: 超过 " + MAX_ENTRYPOINT_LINES + " 行)";
        }

        if (trimmed.length() > MAX_ENTRYPOINT_BYTES) {
            return trimmed.substring(0, MAX_ENTRYPOINT_BYTES) +
                    "... (截断: 超过 " + MAX_ENTRYPOINT_BYTES + " 字节)";
        }

        return trimmed;
    }

    /**
     * 基于用户消息的关键词匹配找出相关记忆文件
     */
    private List<String> findRelevantFiles(String indexContent, String userMessage, Path memoryDir) {
        Set<String> relevant = new LinkedHashSet<>();

        // 提取用户消息中的关键词
        Set<String> keywords = extractKeywords(userMessage);

        for (String line : indexContent.split("\n")) {
            for (String keyword : keywords) {
                if (line.toLowerCase().contains(keyword.toLowerCase())) {
                    // 从 Markdown 链接中提取文件名: - [Title](file.md)
                    int start = line.indexOf("](");
                    int end = line.indexOf(")", start);
                    if (start > 0 && end > start) {
                        String fileName = line.substring(start + 2, end).trim();
                        if (fileName.endsWith(".md")) {
                            relevant.add(fileName);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(relevant);
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[\\s，。！？,.!?]+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
    }

    private void updateMemoryIndex(Path memoryDir, String fileName) throws IOException {
        Path indexPath = memoryDir.resolve(MEMORY_INDEX);
        String entry = "- [" + fileName + "](" + fileName + ")\n";

        if (Files.exists(indexPath)) {
            String current = Files.readString(indexPath);
            if (!current.contains(fileName)) {
                Files.writeString(indexPath, current + entry);
            }
        } else {
            Files.writeString(indexPath, "# Memory Index\n\n" + entry);
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_\\-]", "_").toLowerCase();
    }
}
