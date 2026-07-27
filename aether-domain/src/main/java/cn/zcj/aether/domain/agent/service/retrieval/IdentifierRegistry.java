package cn.zcj.aether.domain.agent.service.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 项目标识符注册表。
 * <p>
 * 扫描项目源文件和文档索引，构建轻量级项目上下文文本。
 * Agent 主体不携带完整文件内容，仅在推理步执行前通过此注册表获取文件路径和文档标题。
 * </p>
 */
@Slf4j
@Component
public final class IdentifierRegistry {

    private static final int MAX_FILE_PATHS = 200;
    private static final int DOC_WALK_DEPTH = 3;

    private static final List<String> EXCLUDED_DIRS = List.of("target", "node_modules", ".git", ".aether");

    private final Path projectRoot;

    public IdentifierRegistry() {
        this.projectRoot = Paths.get(System.getProperty("user.dir"));
    }

    IdentifierRegistry(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * 构建完整的项目标识符上下文文本。
     *
     * @return 形如 {@code <project-context>...\n</project-context>} 的文本块
     */
    public String buildIdentifierContext() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<project-context>\n");

        List<String> javaPaths = scanSourceFiles();
        sb.append("项目源文件 (").append(javaPaths.size()).append(" 个):\n");
        for (String path : javaPaths) {
            sb.append("  ").append(path).append('\n');
        }

        Map<String, String> docIndex = scanDocHeadings();
        if (!docIndex.isEmpty()) {
            sb.append("文档索引:\n");
            for (Map.Entry<String, String> entry : docIndex.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    sb.append("  ").append(entry.getKey()).append(" — ").append(entry.getValue()).append('\n');
                } else {
                    sb.append("  ").append(entry.getKey()).append('\n');
                }
            }
        }

        sb.append("</project-context>");
        return sb.toString();
    }

    /**
     * 扫描项目源文件（.java），按最后修改时间降序排列，最多 200 个。
     */
    List<String> scanSourceFiles() {
        PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
        List<PathEntry> entries = new ArrayList<>();

        Path srcDir = projectRoot.resolve("src");
        if (!Files.isDirectory(srcDir)) {
            // 退而求其次：从项目根扫描
            try (Stream<Path> stream = Files.walk(projectRoot, Integer.MAX_VALUE)) {
                stream.filter(p -> shouldInclude(p))
                        .filter(javaMatcher::matches)
                        .forEach(p -> addEntry(entries, p));
            } catch (IOException e) {
                log.warn("扫描源文件失败: {}", e.getMessage());
            }
        } else {
            try (Stream<Path> stream = Files.walk(srcDir, Integer.MAX_VALUE)) {
                stream.filter(Files::isRegularFile)
                        .filter(javaMatcher::matches)
                        .forEach(p -> addEntry(entries, p));
            } catch (IOException e) {
                log.warn("扫描源文件失败: {}", e.getMessage());
            }
        }

        entries.sort(Comparator.comparingLong(PathEntry::lastModified).reversed());

        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(entries.size(), MAX_FILE_PATHS); i++) {
            result.add(projectRoot.relativize(entries.get(i).path).toString().replace('\\', '/'));
        }
        return result;
    }

    /**
     * 扫描 docs/ 目录下的 markdown 文件（最多 3 层），提取一级标题。
     *
     * @return map: 相对路径 -> 标题（无 '#' 前缀），未找到标题时值为空字符串
     */
    Map<String, String> scanDocHeadings() {
        Map<String, String> index = new LinkedHashMap<>();
        Path docsDir = projectRoot.resolve("docs");
        if (!Files.isDirectory(docsDir)) {
            return index;
        }

        PathMatcher mdMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.md");
        try (Stream<Path> stream = Files.walk(docsDir, DOC_WALK_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(mdMatcher::matches)
                    .forEach(p -> {
                        String relPath = projectRoot.relativize(p).toString().replace('\\', '/');
                        String heading = extractFirstHeading(p);
                        index.put(relPath, heading);
                    });
        } catch (IOException e) {
            log.warn("扫描文档索引失败: {}", e.getMessage());
        }
        return index;
    }

    /**
     * 提取文件首行标题（去除前导 '#' 和空白）。
     */
    private String extractFirstHeading(Path file) {
        try {
            return Files.lines(file)
                    .map(String::trim)
                    .filter(line -> line.startsWith("#"))
                    .findFirst()
                    .map(line -> line.replaceFirst("^#+\\s*", ""))
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 判断路径是否应被包含（过滤无关目录）。
     */
    private boolean shouldInclude(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            String name = path.getName(i).toString();
            if (EXCLUDED_DIRS.contains(name)) {
                return false;
            }
        }
        return Files.isRegularFile(path);
    }

    private void addEntry(List<PathEntry> entries, Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            entries.add(new PathEntry(path, attrs.lastModifiedTime().toMillis()));
        } catch (IOException ignored) {
            entries.add(new PathEntry(path, 0L));
        }
    }

    private record PathEntry(Path path, long lastModified) {}
}
