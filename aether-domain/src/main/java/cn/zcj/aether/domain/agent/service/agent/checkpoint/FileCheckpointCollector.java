package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P0-#8: 文件系统检查点收集器。
 * 检查点存储路径: .claude/checkpoints/{sessionId}/
 * 文件命名: ckpt-{turnNumber:04d}.json
 *
 * <p>借鉴 cc-haha 的 agent-*.jsonl WAL 模式——文件即日志。
 * 每个检查点一个独立 JSON 文件，人类可读，cat 即可查看。
 *
 * <p>H5-步骤3 升级：写入原子化——先写 .tmp 再 ATOMIC_MOVE，
 * 消除"崩溃留下半个 JSON 文件"的损坏窗口。
 * 移植自 AgentScope {@code JsonFileAgentStateStore.atomicWriteString}（L319-323）。
 */
@Slf4j
@Component
public class FileCheckpointCollector implements CheckpointCollector {

    private static final String BASE_DIR = ".claude/checkpoints";
    private static final String CKPT_PREFIX = "ckpt-";
    private static final String CKPT_SUFFIX = ".json";

    /** 检查点根目录（默认相对进程 CWD；包级构造器供测试注入临时目录） */
    private final Path baseDir;

    public FileCheckpointCollector() {
        this(Paths.get(BASE_DIR));
    }

    FileCheckpointCollector(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void save(CheckpointData checkpoint) {
        Path dir = getSessionDir(checkpoint.getSessionId());
        try {
            Files.createDirectories(dir);
            String filename = String.format("%s%04d%s",
                    CKPT_PREFIX, checkpoint.getTurnNumber(), CKPT_SUFFIX);
            Path file = dir.resolve(filename);

            // H5-步骤3: 原子写入——先写 .tmp 再 ATOMIC_MOVE（移植 AgentScope L319-323）
            Path tmp = dir.resolve(filename + ".tmp");
            Files.writeString(tmp, checkpoint.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            log.debug("检查点已保存: sessionId={}, turn={}, file={}",
                    checkpoint.getSessionId(), checkpoint.getTurnNumber(), file);
        } catch (IOException e) {
            log.error("检查点保存失败: sessionId={}, turn={}",
                    checkpoint.getSessionId(), checkpoint.getTurnNumber(), e);
        }
    }

    @Override
    public Optional<CheckpointData> loadLatest(String sessionId) {
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return Optional.empty();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(CKPT_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .map(this::readCheckpoint);
        } catch (IOException e) {
            log.warn("加载最新检查点失败: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<CheckpointData> listCheckpoints(String sessionId) {
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return List.of();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(CKPT_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .map(this::readCheckpoint)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("列出检查点失败: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    @Override
    public Optional<CheckpointData> load(String sessionId, int turnNumber) {
        Path dir = getSessionDir(sessionId);
        String filename = String.format("%s%04d%s",
                CKPT_PREFIX, turnNumber, CKPT_SUFFIX);
        Path file = dir.resolve(filename);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.ofNullable(readCheckpoint(file));
    }

    // ====== 内部方法 ======

    private Path getSessionDir(String sessionId) {
        return baseDir.resolve(sessionId);
    }

    private CheckpointData readCheckpoint(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return CheckpointData.fromJson(json);
        } catch (IOException | RuntimeException e) {
            // 解析失败（损坏文件）返回 null，由调用方过滤——契约即"坏文件不阻断加载"
            log.warn("读取检查点文件失败: {}", file, e);
            return null;
        }
    }
}
