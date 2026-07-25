package cn.zcj.aether.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import cn.zcj.aether.domain.agent.service.memory.RecallFlow;

/**
 * 记忆存储系统 — P1-4 扩展：实现 VectorStore 接口。
 *
 * 记忆目录定位优先级：
 *   1. YAML 配置 ai.agent.config.memory-dir（支持绝对路径）
 *   2. 自动探测项目根目录（向上遍历找 pom.xml + CLAUDE.md）→ memory/
 *   3. 兜底：user.dir/memory
 */
@Slf4j
@Service
@Primary
public class MemoryStore implements VectorStore {

    private static final String MEMORY_INDEX = "MEMORY.md";

    @Value("${ai.agent.config.memory-dir:}")
    private String configuredMemoryDir;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RecallFlow recallFlow;

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

    // =========================================================
    // P1-4: VectorStore 接口实现（向量存储后端之一）
    // =========================================================

    @Override
    public CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record) {
        return CompletableFuture.runAsync(() -> {
            // 文件存储后端：保存为 JSON 格式文件 + 更新索引
            try {
                Path memoryDir = resolveMemoryDir();
                if (memoryDir == null) return;
                Files.createDirectories(memoryDir);

                String fileName = sanitizeFileName(record.getScope().path() + "-" + id) + ".json";
                Path memFile = memoryDir.resolve(fileName);

                // 序列化 embedding 为 Base64 或其他格式存储
                String embeddingStr = vector != null
                    ? Base64.getEncoder().encodeToString(floatArrayToBytes(vector)) : "null";

                String json = String.format(
                    "{\"id\":\"%s\",\"content\":\"%s\",\"embedding\":\"%s\",\"scope\":\"%s\",\"importance\":%f}",
                    id, record.getContent().replace("\"", "\\\""),
                    embeddingStr, record.getScope().path(), record.getImportance());
                Files.writeString(memFile, json);
            } catch (IOException e) {
                log.warn("记忆 upsert 写入失败: id={}", id, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<MemorySearchResult>> search(
            float[] queryVector, int topK, List<MemoryScope> scopes) {

        // C3: 委托 RecallFlow 进行真正的语义搜索
        if (recallFlow != null) {
            var options = new MemoryFacade.RecallOptions(
                MemoryFacade.RecallOptions.RecallDepth.SHALLOW,
                scopes,
                topK,
                0.6f, 0.3f, 0.1f);

            // 从 queryVector 尝试还原查询文本（文件存储模式下无法还原，传空字符串走关键词匹配）
            String queryText = extractQueryFromVector(queryVector);
            return recallFlow.recallShallow(queryText != null ? queryText : "", options);
        }

        // 回退: 无 RecallFlow 时返回空结果
        log.warn("RecallFlow 未注入，MemoryStore 语义搜索返回空结果");
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * 从查询向量中提取文本 query（文件存储模式下向量为伪向量，无法还原文本）
     */
    private String extractQueryFromVector(float[] queryVector) {
        if (queryVector == null) return null;
        // 检查是否为默认维度的零向量（伪向量信号）
        boolean allZero = true;
        for (float v : queryVector) {
            if (v != 0.0f) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return null;
        }
        return null; // 文件存储模式下不支持向量→文本还原
    }

    @Override
    public CompletableFuture<Void> upsertBatch(List<MemoryRecord> records) {
        CompletableFuture<?>[] futures = records.stream()
            .map(r -> upsert(r.getId(), r.getEmbedding(), r))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path memoryDir = resolveMemoryDir();
                if (memoryDir == null) return;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir,
                        "*" + sanitizeFileName(id) + "*.json")) {
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException e) {
                log.warn("记忆删除失败: id={}", id, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteByScope(MemoryScope scope) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path memoryDir = resolveMemoryDir();
                if (memoryDir == null) return;
                String scopePrefix = sanitizeFileName(scope.path());
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir,
                        scopePrefix + "*.json")) {
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException e) {
                log.warn("按作用域删除记忆失败: scope={}", scope.path(), e);
            }
        });
    }

    @Override
    public int dimension() {
        return 1280; // 默认 1280 维（如 text-embedding-3-small）
    }

    private byte[] floatArrayToBytes(float[] floats) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
}
