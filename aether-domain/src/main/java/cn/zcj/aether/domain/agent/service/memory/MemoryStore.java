package cn.zcj.aether.domain.agent.service.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
@ConditionalOnProperty(name = "aether.memory.pgvector.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryStore implements VectorStore {

    private static final String MEMORY_INDEX = "MEMORY.md";

    @Value("${ai.agent.config.memory-dir:}")
    private String configuredMemoryDir;

    /** 向量维度（pgvector 对齐；文件后端仅用于一致性） */
    @Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = 1024;

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
            float[] queryVector, int maxResults, List<MemoryScope> scopes) {

        return CompletableFuture.supplyAsync(() -> {
            List<MemorySearchResult> results = new ArrayList<>();
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) {
                return results;
            }

            File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return results;

            // 无效查询向量（未配置 EmbeddingModel / 全零）→ 回退按文件修改时间排序
            if (isInvalidQueryVector(queryVector)) {
                List<MemorySearchResult> fallback = new ArrayList<>();
                for (File file : files) {
                    try {
                        MemoryRecord record = parseMemoryRecord(file, Files.readString(file.toPath()));
                        if (record != null) {
                            fallback.add(MemorySearchResult.of(record, recencyScore(file.lastModified())));
                        }
                    } catch (IOException ignored) {
                        // 单文件读取失败跳过
                    }
                }
                fallback.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
                return fallback.subList(0, Math.min(fallback.size(), maxResults));
            }

            for (File file : files) {
                try {
                    String content = Files.readString(file.toPath());
                    MemoryRecord record = parseMemoryRecord(file, content);
                    if (record == null) continue;

                    float[] stored = parseStoredEmbedding(content);
                    if (stored == null) continue; // "null" 或缺失 → 跳过

                    double similarity = cosineSimilarity(queryVector, stored);
                    results.add(MemorySearchResult.of(record, similarity));
                } catch (Exception e) {
                    log.debug("读取记忆文件失败: {}", file.getName());
                }
            }

            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return results.subList(0, Math.min(results.size(), maxResults));
        });
    }

    /** 判断查询向量是否无效（null/空/全零） */
    private boolean isInvalidQueryVector(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float f : v) {
            if (f != 0f) return false;
        }
        return true;
    }

    /** 从 JSON 内容解析存储的 Base64 向量；缺失或 "null" 返回 null */
    private float[] parseStoredEmbedding(String jsonContent) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(jsonContent);
            if (!node.has("embedding")) return null;
            String enc = node.get("embedding").asText();
            if (enc == null || enc.isEmpty() || "null".equals(enc)) return null;
            return bytesToFloatArray(Base64.getDecoder().decode(enc));
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度（0.0~1.0；零向量返回 0.0） */
    private double cosineSimilarity(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 文件修改时间衰减分（降级排序用） */
    private double recencyScore(long lastModifiedMillis) {
        long daysAgo = java.time.Duration.between(
                java.time.Instant.ofEpochMilli(lastModifiedMillis), java.time.Instant.now()).toDays();
        if (daysAgo <= 1) return 1.0;
        if (daysAgo <= 7) return 0.8;
        if (daysAgo <= 30) return 0.5;
        return 0.2;
    }

    private MemoryRecord parseMemoryRecord(File file, String content) {
        try {
            if (file.getName().endsWith(".md")) {
                // 解析 YAML frontmatter
                return MemoryRecord.builder()
                        .id(file.getName().replace(".md", ""))
                        .content(content)
                        .scope(MemoryScope.global())
                        .importance(0.5f)
                        .createdAt(java.time.Instant.ofEpochMilli(file.lastModified()))
                        .lastAccessedAt(java.time.Instant.now())
                        .accessCount(1)
                        .isPrivate(false)
                        .source("memory_store")
                        .build();
            }
            // JSON files
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(content);
            return MemoryRecord.builder()
                    .id(node.has("id") ? node.get("id").asText() : file.getName().replace(".json", ""))
                    .content(node.has("content") ? node.get("content").asText() : content)
                    .scope(MemoryScope.global())
                    .importance(node.has("importance") ? (float) node.get("importance").asDouble() : 0.5f)
                    .createdAt(node.has("createdAt") ? java.time.Instant.parse(node.get("createdAt").asText())
                            : java.time.Instant.ofEpochMilli(file.lastModified()))
                    .lastAccessedAt(java.time.Instant.now())
                    .accessCount(1)
                    .isPrivate(false)
                    .source("memory_store")
                    .build();
        } catch (Exception e) {
            log.debug("解析记忆记录失败: {}", file.getName());
            return null;
        }
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
    public CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<MemoryRecord> missing = new ArrayList<>();
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) return missing;

            File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return missing;

            for (File file : files) {
                if (missing.size() >= limit) break;
                try {
                    String content = Files.readString(file.toPath());
                    if (parseStoredEmbedding(content) == null) {
                        MemoryRecord record = parseMemoryRecord(file, content);
                        if (record != null) missing.add(record);
                    }
                } catch (Exception e) {
                    log.debug("回填扫描读取失败: {}", file.getName());
                }
            }
            return missing;
        });
    }

    @Override
    public CompletableFuture<Void> updateEmbedding(String id, float[] vector) {
        return CompletableFuture.runAsync(() -> {
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) return;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                File[] files = memoryDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
                if (files == null) return;
                for (File file : files) {
                    String content = Files.readString(file.toPath());
                    var node = mapper.readTree(content);
                    String fileId = file.getName().replace(".json", "");
                    String jsonId = node.has("id") ? node.get("id").asText() : fileId;
                    if (!jsonId.equals(id)) continue;

                    String embeddingStr = vector != null
                        ? Base64.getEncoder().encodeToString(floatArrayToBytes(vector)) : "null";
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("embedding", embeddingStr);
                    Files.writeString(file.toPath(), mapper.writeValueAsString(node));
                    log.debug("回填向量更新: id={}", id);
                    return;
                }
                log.warn("回填更新未找到记录: id={}", id);
            } catch (Exception e) {
                log.warn("回填向量更新失败: id={}", id, e);
            }
        });
    }

    @Override
    public int dimension() {
        return vectorDimension;
    }

    private byte[] floatArrayToBytes(float[] floats) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private float[] bytesToFloatArray(byte[] bytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        int n = bytes.length / 4;
        float[] floats = new float[n];
        for (int i = 0; i < n; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}
