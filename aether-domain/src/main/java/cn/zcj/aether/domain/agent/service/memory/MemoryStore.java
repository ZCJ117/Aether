package cn.zcj.aether.domain.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
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
 *
 * <p>O11: scope 分片存储 — 记录按 scope 路径落盘到分片目录
 * （{@code global/}、{@code user/<name>/}、{@code agent/<id>/}），
 * search 只遍历请求 scope 的祖先链目录 + 自身子树（隔离兄弟/跨用户分片）；
 * 旧版平铺文件（memoryDir 根下 {@code <scope>-<id>.json}）启动时按 JSON 内
 * scope 字段迁移进分片目录，读取侧保留平铺兼容分支。</p>
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "aether.memory.pgvector.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryStore implements VectorStore {

    private static final String MEMORY_INDEX = "MEMORY.md";
    /** O11: 全局作用域分片目录名 */
    private static final String GLOBAL_SHARD = "global";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${ai.agent.config.memory-dir:}")
    private String configuredMemoryDir;

    /** 向量维度（pgvector 对齐；文件后端仅用于一致性） */
    @Value("${aether.memory.recall.vector-dimension:1024}")
    private int vectorDimension = 1024;

    // 参考项目B memdir/memdir.ts:34-35
    private static final int MAX_ENTRYPOINT_LINES = 200;
    private static final int MAX_ENTRYPOINT_BYTES = 25_000;

    /**
     * O11: 启动迁移 — 将旧版平铺在 memoryDir 根下的 *.json 按 JSON 内 scope 字段
     * 移入分片目录（scope 缺失视为 global）。迁移失败仅告警，读取侧保留平铺兼容分支。
     */
    @PostConstruct
    void migrateLegacyFlatFiles() {
        try {
            Path memoryDir = resolveMemoryDir();
            if (memoryDir == null || !Files.exists(memoryDir)) {
                return;
            }
            File[] files = memoryDir.toFile().listFiles((d, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) {
                return;
            }
            int moved = 0;
            for (File file : files) {
                try {
                    var node = MAPPER.readTree(Files.readString(file.toPath()));
                    String scopePath = node.has("scope") && !node.get("scope").asText().isBlank()
                            ? node.get("scope").asText() : GLOBAL_SHARD;
                    Path targetDir = memoryDir.resolve(sanitizeScopePath(scopePath));
                    Files.createDirectories(targetDir);
                    Files.move(file.toPath(), targetDir.resolve(file.getName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (Exception e) {
                    log.debug("O11 迁移跳过不可解析文件: {}", file.getName());
                }
            }
            if (moved > 0) {
                log.info("O11 记忆分片迁移: {} 个旧平铺文件已按 scope 移入分片目录 (root={})",
                        moved, memoryDir.toAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("O11 记忆分片迁移失败（保留平铺兼容读取）: {}", e.getMessage());
        }
    }

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
    // O11: scope 分片路径 helpers
    // =========================================================

    /** scope → 分片目录（"" / null → global/；"user/alice" → user/alice/） */
    private Path shardDir(Path memoryDir, MemoryScope scope) {
        String path = scope == null || scope.path() == null || scope.path().isBlank()
                ? GLOBAL_SHARD : sanitizeScopePath(scope.path());
        return memoryDir.resolve(path);
    }

    /** scope path 规范化：正斜杠、逐段 sanitize、拒绝空段与 ".."，空结果回退 global */
    private String sanitizeScopePath(String scopePath) {
        String[] parts = scopePath.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank() || "..".equals(part)) continue;
            if (!sb.isEmpty()) sb.append("/");
            sb.append(sanitizeFileName(part));
        }
        return sb.isEmpty() ? GLOBAL_SHARD : sb.toString();
    }

    /** scope path → 段列表（"" → 空列表表示全局） */
    private List<String> scopeSegments(String scopePath) {
        if (scopePath == null || scopePath.isBlank()) return List.of();
        return Arrays.stream(scopePath.replace('\\', '/').split("/"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** prefix 是否为 whole 的段级前缀（避免字符串 startsWith 的 "user/al" ⊂ "user/alice" 误判） */
    private boolean isSegmentPrefix(List<String> prefix, List<String> whole) {
        if (prefix.size() > whole.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(whole.get(i))) return false;
        }
        return true;
    }

    /**
     * O11: 记录 scope 对请求 scope 的可见性 —
     * 请求 scope 的祖先链（含 global）或自身子树内可见；兄弟/跨用户分片隔离。
     */
    private boolean isScopeVisible(String recordScopePath, String requestedScopePath) {
        List<String> rec = scopeSegments(recordScopePath);
        List<String> req = scopeSegments(requestedScopePath);
        return isSegmentPrefix(req, rec) || isSegmentPrefix(rec, req);
    }

    /** 从文件相对 memoryDir 的路径推导 scope path（分片父目录）；根目录平铺文件返回 ""（解析时再读 JSON scope） */
    private String scopePathFromRelative(Path relative) {
        Path parent = relative.getParent();
        if (parent == null) return "";
        String rel = parent.toString().replace('\\', '/');
        return GLOBAL_SHARD.equals(rel) ? "" : rel;
    }

    /** 递归列出目录下全部 *.json（含分片子树与旧平铺） */
    private List<Path> listJsonRecursive(Path dir) {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.exists(dir)) return files;
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("遍历记忆目录失败: {}", dir, e);
        }
        return files;
    }

    /** 非递归列出一层目录下的 *.json（祖先链分片目录用） */
    private List<Path> listJsonFlat(Path dir) {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return files;
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) files.add(p);
            }
        } catch (IOException e) {
            log.debug("列目录失败: {}", dir);
        }
        return files;
    }

    /**
     * O11: 计算搜索候选文件 — 只遍历目标分片。
     *
     * <p>scopes 为空 → 全量（兼容旧调用方语义）；否则对每个请求 scope 收集：</p>
     * <ul>
     *   <li>祖先链目录（memoryDir 根平铺兼容 + global + 各级父分片）一层的 *.json；</li>
     *   <li>自身分片子树（含后代 scope 分片）递归的 *.json；</li>
     *   <li>请求 scope 为空串（global）→ 全量遍历（global 对全部后代可见）。</li>
     * </ul>
     * 解析后仍按 {@link #isScopeVisible} 复核（根目录平铺文件的 scope 以 JSON 内字段为准）。
     */
    private List<Path> candidateFiles(Path memoryDir, List<MemoryScope> scopes) {
        if (scopes == null || scopes.isEmpty()
                || scopes.stream().anyMatch(s -> s == null || s.path() == null || s.path().isBlank())) {
            return listJsonRecursive(memoryDir);
        }

        Set<Path> ancestorDirs = new LinkedHashSet<>();
        Set<Path> shardSubtrees = new LinkedHashSet<>();
        for (MemoryScope scope : scopes) {
            ancestorDirs.add(memoryDir);                     // 根目录：旧平铺兼容文件
            ancestorDirs.add(memoryDir.resolve(GLOBAL_SHARD)); // global 分片
            Path current = memoryDir;
            for (String seg : scopeSegments(sanitizeScopePath(scope.path()))) {
                current = current.resolve(seg);
                ancestorDirs.add(current);                   // 各级父分片（含自身分片目录一层）
            }
            shardSubtrees.add(shardDir(memoryDir, scope));   // 自身分片子树（后代 scope）
        }

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        for (Path dir : ancestorDirs) {
            candidates.addAll(listJsonFlat(dir));
        }
        for (Path dir : shardSubtrees) {
            candidates.addAll(listJsonRecursive(dir));
        }
        return new ArrayList<>(candidates);
    }

    // =========================================================
    // P1-4: VectorStore 接口实现（向量存储后端之一）
    // =========================================================

    @Override
    public CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record) {
        return CompletableFuture.runAsync(() -> {
            // O11: 文件存储后端 — 按 scope 分片目录落盘，ObjectMapper 序列化（修复手写 JSON 转义缺陷）
            try {
                Path memoryDir = resolveMemoryDir();
                if (memoryDir == null) return;
                MemoryScope scope = record.getScope() != null ? record.getScope() : MemoryScope.global();
                Path dir = shardDir(memoryDir, scope);
                Files.createDirectories(dir);

                Path memFile = dir.resolve(sanitizeFileName(id) + ".json");

                String embeddingStr = vector != null
                        ? Base64.getEncoder().encodeToString(floatArrayToBytes(vector)) : "null";

                ObjectNode node = MAPPER.createObjectNode();
                node.put("id", id);
                node.put("content", record.getContent() != null ? record.getContent() : "");
                node.put("embedding", embeddingStr);
                node.put("scope", scope.path());
                node.put("scopePrivate", scope.isPrivate());
                node.put("importance", record.getImportance());
                node.put("createdAt", record.getCreatedAt() != null
                        ? record.getCreatedAt().toString() : java.time.Instant.now().toString());
                node.put("source", record.getSource() != null ? record.getSource() : "memory_store");
                Files.writeString(memFile, MAPPER.writeValueAsString(node));
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

            // O11: 只遍历目标分片（scopes 为空 → 全量兼容）
            List<Path> candidates = candidateFiles(memoryDir, scopes);

            // 无效查询向量（未配置 EmbeddingModel / 全零）→ 回退按文件修改时间排序
            if (isInvalidQueryVector(queryVector)) {
                List<MemorySearchResult> fallback = new ArrayList<>();
                for (Path file : candidates) {
                    try {
                        MemoryRecord record = parseMemoryRecord(file.toFile(),
                                Files.readString(file), memoryDir);
                        if (record != null && visibleForAny(record, scopes)) {
                            fallback.add(MemorySearchResult.of(record, recencyScore(file.toFile().lastModified())));
                        }
                    } catch (IOException ignored) {
                        // 单文件读取失败跳过
                    }
                }
                fallback.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
                return fallback.subList(0, Math.min(fallback.size(), maxResults));
            }

            for (Path file : candidates) {
                try {
                    String content = Files.readString(file);
                    MemoryRecord record = parseMemoryRecord(file.toFile(), content, memoryDir);
                    if (record == null) continue;
                    if (!visibleForAny(record, scopes)) continue;

                    float[] stored = parseStoredEmbedding(content);
                    if (stored == null) continue; // "null" 或缺失 → 跳过

                    double similarity = cosineSimilarity(queryVector, stored);
                    results.add(MemorySearchResult.of(record, similarity));
                } catch (Exception e) {
                    log.debug("读取记忆文件失败: {}", file.getFileName());
                }
            }

            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return results.subList(0, Math.min(results.size(), maxResults));
        });
    }

    /** O11: 记录 scope 是否对请求 scope 列表可见（任一请求 scope 可见即可） */
    private boolean visibleForAny(MemoryRecord record, List<MemoryScope> scopes) {
        if (scopes == null || scopes.isEmpty()) return true;
        String recordPath = record.getScope() != null ? record.getScope().path() : "";
        for (MemoryScope req : scopes) {
            if (isScopeVisible(recordPath, req == null ? "" : req.path())) return true;
        }
        return false;
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
            var node = MAPPER.readTree(jsonContent);
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

    /**
     * O11: 解析记忆记录 — JSON 分支从文件内容 "scope" 字段读取 scope（缺失时按分片父目录推导），
     * 不再恒返回 global；.md 分支保持 global。
     */
    private MemoryRecord parseMemoryRecord(File file, String content, Path memoryDir) {
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
            var node = MAPPER.readTree(content);
            String scopePath = node.has("scope") && !node.get("scope").asText().isBlank()
                    ? node.get("scope").asText()
                    : scopePathFromRelative(memoryDir.relativize(file.toPath()));
            boolean scopePrivate = node.has("scopePrivate") && node.get("scopePrivate").asBoolean();
            return MemoryRecord.builder()
                    .id(node.has("id") ? node.get("id").asText() : file.getName().replace(".json", ""))
                    .content(node.has("content") ? node.get("content").asText() : content)
                    .scope(new MemoryScope(scopePath, scopePrivate))
                    .importance(node.has("importance") ? (float) node.get("importance").asDouble() : 0.5f)
                    .createdAt(node.has("createdAt") ? java.time.Instant.parse(node.get("createdAt").asText())
                            : java.time.Instant.ofEpochMilli(file.lastModified()))
                    .lastAccessedAt(java.time.Instant.now())
                    .accessCount(1)
                    .isPrivate(scopePrivate)
                    .source(node.has("source") ? node.get("source").asText() : "memory_store")
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
                // 分片存储：按 JSON 内 id 全量匹配删除
                for (Path file : listJsonRecursive(memoryDir)) {
                    try {
                        var node = MAPPER.readTree(Files.readString(file));
                        String fileId = node.has("id") ? node.get("id").asText()
                                : file.getFileName().toString().replace(".json", "");
                        if (id.equals(fileId) || file.getFileName().toString().contains(sanitizeFileName(id))) {
                            Files.deleteIfExists(file);
                        }
                    } catch (Exception ignored) {
                        // 单文件解析失败跳过
                    }
                }
                // 旧平铺命名兼容：<scope>-<id>.json
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
                // O11: scope 删除落到分片子树路径
                Path shard = shardDir(memoryDir, scope);
                if (Files.exists(shard)) {
                    try (var walk = Files.walk(shard)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                        // 单文件删除失败跳过
                                    }
                                });
                    }
                }
                // 旧平铺命名兼容：<scopePath>-<id>.json
                String scopePrefix = sanitizeFileName(scope.path());
                if (!scopePrefix.isBlank()) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir,
                            scopePrefix + "*.json")) {
                        for (Path file : stream) {
                            Files.deleteIfExists(file);
                        }
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

            // O11: 遍历全部分片（含子目录）
            for (Path file : listJsonRecursive(memoryDir)) {
                if (missing.size() >= limit) break;
                try {
                    String content = Files.readString(file);
                    if (parseStoredEmbedding(content) == null) {
                        MemoryRecord record = parseMemoryRecord(file.toFile(), content, memoryDir);
                        if (record != null) missing.add(record);
                    }
                } catch (Exception e) {
                    log.debug("回填扫描读取失败: {}", file.getFileName());
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
                // O11: 遍历全部分片（含子目录）定位记录
                for (Path file : listJsonRecursive(memoryDir)) {
                    String content = Files.readString(file);
                    var node = MAPPER.readTree(content);
                    String fileId = node.has("id") ? node.get("id").asText()
                            : file.getFileName().toString().replace(".json", "");
                    if (!fileId.equals(id)) continue;

                    String embeddingStr = vector != null
                        ? Base64.getEncoder().encodeToString(floatArrayToBytes(vector)) : "null";
                    ((ObjectNode) node).put("embedding", embeddingStr);
                    Files.writeString(file, MAPPER.writeValueAsString(node));
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
