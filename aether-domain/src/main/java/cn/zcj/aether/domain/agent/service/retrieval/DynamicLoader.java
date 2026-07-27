package cn.zcj.aether.domain.agent.service.retrieval;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 动态文件加载器。
 * <p>
 * 按需懒加载文件内容，内部通过 {@link ConcurrentHashMap} 缓存已读取的完整文件行列表。
 * 仅加载请求的行范围，避免全量预埋。
 * </p>
 */
@Slf4j
public final class DynamicLoader {

    private static final int MAX_LINES_PER_LOAD = 200;

    private final Path projectRoot;

    /** 路径 -> 文件行列表的缓存 */
    private final ConcurrentMap<String, List<String>> cache = new ConcurrentHashMap<>();

    public DynamicLoader() {
        this.projectRoot = Paths.get(System.getProperty("user.dir"));
    }

    DynamicLoader(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * 按行范围加载文件内容。
     * <p>
     * 行号从 1 开始。若指定范围超过文件总行数则截断至末尾。
     * 每次最多返回 {@value MAX_LINES_PER_LOAD} 行。
     * </p>
     *
     * @param filePath  相对于项目根目录的文件路径
     * @param startLine 起始行号（含，从 1 开始）
     * @param endLine   结束行号（含）
     * @return 指定行范围的内容，行之间用换行符分隔；文件不存在或读取失败时返回错误消息
     */
    public String loadLines(String filePath, int startLine, int endLine) {
        if (startLine < 1) {
            startLine = 1;
        }
        if (endLine < startLine) {
            return "";
        }
        if (endLine - startLine + 1 > MAX_LINES_PER_LOAD) {
            endLine = startLine + MAX_LINES_PER_LOAD - 1;
        }

        List<String> lines = cache.computeIfAbsent(filePath, this::readAllLines);
        if (lines == null || lines.isEmpty()) {
            return "[DynamicLoader] 文件不存在或为空: " + filePath;
        }

        int from = Math.min(startLine - 1, lines.size());
        int to = Math.min(endLine, lines.size());

        if (from >= to) {
            return "";
        }

        return String.join("\n", lines.subList(from, to));
    }

    /**
     * 读取文件全部行（内部缓存用）。
     *
     * @return 文件行列表，读取失败时返回 null
     */
    private List<String> readAllLines(String filePath) {
        Path resolved = projectRoot.resolve(filePath).normalize();
        // 安全检查：禁止跳出项目根目录
        if (!resolved.startsWith(projectRoot)) {
            log.warn("路径越界访问被拒绝: {}", filePath);
            return null;
        }
        try {
            return Files.readAllLines(resolved);
        } catch (IOException e) {
            log.warn("读取文件失败 [{}]: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 清除指定文件的缓存。
     */
    public void invalidate(String filePath) {
        cache.remove(filePath);
    }

    /**
     * 清除全部缓存。
     */
    public void invalidateAll() {
        cache.clear();
    }
}
