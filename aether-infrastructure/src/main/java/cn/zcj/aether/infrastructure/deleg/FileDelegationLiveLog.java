package cn.zcj.aether.infrastructure.deleg;

import cn.zcj.aether.domain.agent.service.subagent.DelegationLiveLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每子Agent直播日志文件实现 — 对齐 hermes delegation_live_log.py LiveTranscriptWriter。
 * <p>纪律（逐条对齐 hermes）：① append-mode 每次写入即落盘，无长句柄（子Agent崩溃不丢行）；
 * ② 任何写失败首次触发即禁用该 writer（内部 ok map），降级 debug log，绝不上抛；
 * ③ 单行折叠 + 截断（assistant 600 / 其他 400）；④ open 时机会性 prune 超 7 天目录；
 * ⑤ close 幂等。</p>
 * <p>文件布局：&lt;base&gt;/&lt;delegationId&gt;/task-0.log（base 默认 ./cache/delegation/live）。</p>
 */
@Slf4j
@Component
public class FileDelegationLiveLog implements DelegationLiveLog {

    public static final int LIVE_RETENTION_DAYS = 7;
    static final int ASSISTANT_MAX = 600;
    static final int RESULT_MAX = 400;
    static final int KICKOFF_MAX = 500;

    private final Path baseDir;
    /** delegationId -> 该 writer 是否仍可用（首错即 false）。 */
    private final Map<String, Boolean> ok = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    @Autowired
    public FileDelegationLiveLog(
            @Value("${aether.delegation.live-log-dir:./cache/delegation/live}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    /** 测试注入显式路径。 */
    public FileDelegationLiveLog(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    private Path file(String delegationId) {
        return baseDir.resolve(delegationId).resolve("task-0.log");
    }

    private boolean enabled(String delegationId) {
        return ok.getOrDefault(delegationId, true);
    }

    @Override
    public void open(String delegationId, String goal) {
        try {
            pruneStaleLives();
            Path dir = baseDir.resolve(delegationId);
            Files.createDirectories(dir);
            List<String> header = new ArrayList<>();
            header.add("=== Aether subagent live transcript ===");
            header.add("delegation: " + delegationId + "   task: 0");
            header.add("goal: " + oneLine(goal, KICKOFF_MAX));
            header.add("started: " + Instant.now());
            header.add("(append-only; streams while the subagent runs - tail -f me)");
            header.add("====");
            Files.write(file(delegationId),
                    (String.join("\n", header) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ok.put(delegationId, true);
            closed.remove(delegationId);
        } catch (Exception e) {
            ok.put(delegationId, false);
            log.debug("FileDelegationLiveLog: open 失败 delegation={}", delegationId, e);
        }
    }

    @Override
    public void append(String delegationId, String role, String line) {
        if (!enabled(delegationId)) {
            return;
        }
        int limit = "assistant".equals(role) ? ASSISTANT_MAX : RESULT_MAX;
        String text = "[" + LocalTime.now().withNano(0) + "] " + role + " | "
                + oneLine(line, limit) + "\n";
        try {
            Files.write(file(delegationId), text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception e) {
            ok.put(delegationId, false);
            log.debug("FileDelegationLiveLog: append 失败 path={}", file(delegationId), e);
        }
    }

    @Override
    public void flush(String delegationId) {
        // append-mode 已同步落盘；flush 为语义占位
    }

    @Override
    public void close(String delegationId, String summary) {
        if (!closed.add(delegationId)) {
            return; // 幂等：只写一次终态行
        }
        append(delegationId, "final", summary);
    }

    @Override
    public List<String> tail(String delegationId, int n) {
        try {
            Path f = file(delegationId);
            if (!Files.exists(f)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - n);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException e) {
            log.debug("FileDelegationLiveLog: tail 失败 delegation={}", delegationId, e);
            return List.of();
        }
    }

    /** 机会性清理超过保留窗口的 delegation 目录；返回清理数（best-effort）。 */
    public int pruneStaleLives() {
        return prune(LIVE_RETENTION_DAYS);
    }

    int prune(int maxAgeDays) {
        int removed = 0;
        if (!Files.isDirectory(baseDir)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeDays));
        try (var stream = Files.list(baseDir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                try {
                    if (Files.isDirectory(child) && Files.getLastModifiedTime(child).toInstant().isBefore(cutoff)) {
                        deleteRecursively(child);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // best-effort：单个目录清理失败不影响其他
                }
            }
        } catch (IOException e) {
            log.debug("FileDelegationLiveLog: prune 失败", e);
        }
        return removed;
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    static String oneLine(String s, int limit) {
        if (s == null) {
            return "";
        }
        String collapsed = String.join(" ", s.trim().split("\\s+"));
        if (collapsed.length() > limit) {
            return collapsed.substring(0, limit) + " …(+" + (collapsed.length() - limit) + " chars)";
        }
        return collapsed;
    }
}
