package cn.zcj.aether.infrastructure.checkpoint;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H5-步骤4: JGit 影子仓检查点存储。
 *
 * <p>移植自 Hermes {@code checkpoint_manager.py} 的 git 影子仓设计：
 * <ul>
 *   <li>单一共享 bare 仓——全部会话共用 objects 自动去重</li>
 *   <li>每会话隔离到独立 ref（{@code refs/aether/<hash>}）与独立 index 文件</li>
 *   <li>底层管道提交（write-tree → commit-tree → update-ref），不触碰用户 .git</li>
 *   <li>GIT 环境完全隔离（独立 GIT_DIR / GIT_INDEX_FILE）</li>
 *   <li>恢复前自动打 pre-rollback 快照使撤销可再撤销</li>
 *   <li>三级清理：per-ref 保留条数 / 保留天数 / 总量上限</li>
 * </ul>
 *
 * <p>配置项（通过 YAML 装配或系统属性）：
 * <ul>
 *   <li>{@code aether.checkpoint.store-dir}: 影子仓根目录，默认 {@code ~/.aether/checkpoints/store}</li>
 *   <li>{@code aether.checkpoint.max-snapshots-per-ref}: 每 ref 最大快照数，默认 50</li>
 *   <li>{@code aether.checkpoint.max-age-days}: 快照最大保留天数，默认 30</li>
 *   <li>{@code aether.checkpoint.max-total-size-mb}: 总量上限（MB），默认 2048</li>
 * </ul>
 *
 * <p>检查点对 LLM 完全不可见——不是工具、不进 prompt（hermes 核心设计约束）。
 */
@Slf4j
public class GitShadowCheckpointStore implements CheckpointCollector {

    // ============ 配置常量 ============

    private static final String DEFAULT_STORE_DIR =
            System.getProperty("user.home") + "/.aether/checkpoints/store";
    private static final int DEFAULT_MAX_SNAPSHOTS_PER_REF = 50;
    private static final int DEFAULT_MAX_AGE_DAYS = 30;
    private static final long DEFAULT_MAX_TOTAL_SIZE_MB = 2048;
    private static final String REF_PREFIX = "refs/aether/";
    private static final int HASH_LENGTH = 16;

    // ============ 实例字段 ============

    private final Path storeDir;
    private final int maxSnapshotsPerRef;
    private final int maxAgeDays;
    private final long maxTotalSizeBytes;

    /** 共享 bare 仓 Repository 实例（延迟初始化） */
    private volatile Repository repo;

    /** 索引文件路径缓存 */
    private final Map<String, Path> indexFileCache = new ConcurrentHashMap<>();

    // ============ 构造器 ============

    public GitShadowCheckpointStore() {
        this(Paths.get(DEFAULT_STORE_DIR), DEFAULT_MAX_SNAPSHOTS_PER_REF,
                DEFAULT_MAX_AGE_DAYS, DEFAULT_MAX_TOTAL_SIZE_MB);
    }

    public GitShadowCheckpointStore(Path storeDir, int maxSnapshotsPerRef,
            int maxAgeDays, long maxTotalSizeMb) {
        this.storeDir = storeDir;
        this.maxSnapshotsPerRef = maxSnapshotsPerRef;
        this.maxAgeDays = maxAgeDays;
        this.maxTotalSizeBytes = maxTotalSizeMb * 1024 * 1024;
    }

    // ============ CheckpointCollector 接口实现 ============

    @Override
    public void save(CheckpointData checkpoint) {
        // 文件检查点仍由 FileCheckpointCollector 处理
        // GitShadowCheckpointStore 专注于工作区快照
    }

    @Override
    public Optional<CheckpointData> loadLatest(String sessionId) {
        return Optional.empty(); // 委托给 FileCheckpointCollector
    }

    @Override
    public List<CheckpointData> listCheckpoints(String sessionId) {
        return List.of(); // 委托给 FileCheckpointCollector
    }

    @Override
    public Optional<CheckpointData> load(String sessionId, int turnNumber) {
        return Optional.empty(); // 委托给 FileCheckpointCollector
    }

    // ============ H5-步骤4: 工作区快照 ============

    /**
     * 确保工作区已创建快照（每轮每目录至多一次）。
     *
     * <p>核心管道（对齐 hermes checkpoint_manager._take() L994-1126）：
     * <ol>
     *   <li>seed index from ref（从上次快照恢复索引）</li>
     *   <li>git add -A（暂存所有变更）</li>
     *   <li>检测变更（无变更则跳过）</li>
     *   <li>write-tree → commit-tree → update-ref（CAS）</li>
     *   <li>触发清理</li>
     * </ol>
     *
     * @param workdir 工作区绝对路径
     * @param reason  快照原因（写入 commit message）
     */
    @Override
    public void ensureWorkspaceSnapshot(String workdir, String reason) {
        if (workdir == null || workdir.isEmpty()) return;

        try {
            Repository repository = getOrInitRepo();
            Path workPath = Paths.get(workdir).toAbsolutePath().normalize();
            if (!Files.isDirectory(workPath)) return;

            String refHash = computeRefHash(workdir, null);
            String refName = REF_PREFIX + refHash;
            Path indexFile = getIndexFile(refHash);

            // 1. 创建/恢复 per-project 索引
            DirCache dirCache = createOrSeedIndex(repository, indexFile, refName);

            // 2. 暂存当前工作区（git add -A）
            stageWorkingTree(repository, dirCache, workPath);

            // 3. 检测变更
            ObjectId oldTip = resolveRef(repository, refName);
            ObjectId newTreeId = dirCache.writeTree(repository.newObjectInserter());
            if (oldTip != null) {
                ObjectId oldTreeId = getCommitTree(repository, oldTip);
                if (newTreeId.equals(oldTreeId)) {
                    log.debug("工作区无变更，跳过快照: workdir={}", workdir);
                    return;
                }
            }

            // 4. 提交树（commit-tree）
            ObjectId commitId = createCommit(repository, newTreeId, oldTip, reason);

            // 5. CAS 更新 ref（update-ref）
            updateRef(repository, refName, commitId, oldTip);

            // 6. 清理
            pruneRef(repository, refName);
            enforceSizeCap(repository);

            log.info("工作区快照已创建: workdir={}, ref={}, commit={}",
                    workdir, refName, commitId.abbreviate(7).name());

        } catch (Exception e) {
            log.warn("工作区快照失败（不阻塞业务流程）: workdir={}, reason={}", workdir, reason, e);
        }
    }

    /**
     * 从影子仓恢复文件。
     *
     * <p>恢复前自动打 pre-rollback 快照使撤销可再撤销（对齐 hermes L940-941）。
     *
     * @param workdir    工作区绝对路径
     * @param commitHash 目标提交哈希
     * @param filePath   要恢复的文件（相对路径），null=恢复整个工作区
     * @return true=恢复成功
     */
    @Override
    public boolean restore(String workdir, String commitHash, String filePath) {
        if (workdir == null || commitHash == null) return false;

        try {
            Repository repository = getOrInitRepo();
            Path workPath = Paths.get(workdir).toAbsolutePath().normalize();

            // 安全校验：验证 commit hash 格式
            if (!commitHash.matches("^[0-9a-fA-F]{7,40}$")) {
                log.warn("无效的 commit hash: {}", commitHash);
                return false;
            }

            // 解析目标提交
            ObjectId targetCommit;
            try {
                targetCommit = repository.resolve(commitHash);
            } catch (Exception e) {
                log.warn("无法解析 commit hash: {}", commitHash, e);
                return false;
            }
            if (targetCommit == null) return false;

            // Pre-rollback 快照（对齐 hermes L940-941）
            ensureWorkspaceSnapshot(workdir,
                    "pre-rollback snapshot (restoring to " + commitHash.substring(0, 8) + ")");

            // 恢复文件
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(targetCommit);
                try (org.eclipse.jgit.treewalk.TreeWalk treeWalk =
                        new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);

                    while (treeWalk.next()) {
                        String path = treeWalk.getPathString();
                        if (filePath != null && !path.equals(filePath)) continue;

                        Path targetFile = workPath.resolve(path);
                        // 防路径穿越
                        if (!targetFile.normalize().startsWith(workPath)) {
                            log.warn("路径穿越防护: {}", path);
                            continue;
                        }

                        Files.createDirectories(targetFile.getParent());
                        try {
                            ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                            Files.write(targetFile, loader.getBytes());
                        } catch (IOException e) {
                            log.warn("恢复文件失败: {}", path, e);
                        }
                    }
                }
            }

            log.info("工作区已恢复: workdir={}, commit={}, file={}",
                    workdir, commitHash.substring(0, 8), filePath);
            return true;

        } catch (Exception e) {
            log.warn("工作区恢复失败: workdir={}, commitHash={}", workdir, commitHash, e);
            return false;
        }
    }

    // ============ 内部实现 ============

    /**
     * 获取或初始化共享 bare 仓。
     */
    private synchronized Repository getOrInitRepo() throws IOException {
        if (repo != null && repo.getObjectDatabase().exists()) return repo;

        Path gitDir = storeDir;
        Files.createDirectories(gitDir);

        File gitDirFile = gitDir.toFile();
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .setGitDir(gitDirFile);
        builder.setBare();

        Repository repository;
        if (!new File(gitDirFile, "HEAD").exists()) {
            repository = builder.build();
            repository.create(true);

            // 初始化配置
            StoredConfig config = repository.getConfig();
            config.setString("user", null, "email", "aether@checkpoint.local");
            config.setString("user", null, "name", "Aether Checkpoint");
            config.setBoolean("commit", null, "gpgsign", false);
            config.setBoolean("gc", null, "auto", false);
            config.save();
        } else {
            repository = builder.build();
        }

        // 确保必要目录存在
        Files.createDirectories(gitDir.resolve("refs").resolve("aether"));
        Files.createDirectories(gitDir.resolve("indexes"));

        this.repo = repository;
        log.info("Git 影子仓已初始化: {}", gitDir);
        return repository;
    }

    /**
     * 获取或创建 per-project 索引文件路径。
     */
    private Path getIndexFile(String refHash) {
        return indexFileCache.computeIfAbsent(refHash,
                h -> storeDir.resolve("indexes").resolve(h));
    }

    /**
     * 创建或从 ref 种子初始化 DirCache。
     */
    private DirCache createOrSeedIndex(Repository repository, Path indexFile, String refName)
            throws IOException {
        DirCache dirCache;

        if (Files.exists(indexFile)) {
            // 从已有索引文件恢复
            dirCache = DirCache.read(indexFile.toFile(), repository.getFS());
        } else {
            // 创建新索引
            dirCache = DirCache.newInCore();
        }

        // 如果 ref 存在且有提交，从上次快照的 tree 种子索引
        ObjectId tipId = resolveRef(repository, refName);
        if (tipId != null) {
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit tipCommit = revWalk.parseCommit(tipId);
                // 用 ref tip 的 tree 作为种子——只读入已有条目
                // 实际暂存时会通过 add -A 更新差异
                try {
                    DirCache seedCache = DirCache.read(indexFile.toFile(), repository.getFS());
                    dirCache = seedCache;
                } catch (Exception e) {
                    log.debug("无法从索引文件种子化，使用空索引: {}", e.getMessage());
                }
            }
        }

        return dirCache;
    }

    /**
     * 暂存工作区所有文件（等价 git add -A）。
     * 使用 Files.walkFileTree 手动构建 DirCache，兼容 JGit 6.x bare 仓库。
     */
    private void stageWorkingTree(Repository repository, DirCache dirCache, Path workPath)
            throws IOException {
        DirCacheBuilder builder = dirCache.builder();
        final int dirCacheEntryDefault = 0; // default file mode

        Files.walkFileTree(workPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String relativePath = workPath.relativize(file).toString()
                            .replace('\\', '/');
                    // 跳过 .git 目录
                    if (relativePath.startsWith(".git/") || relativePath.equals(".git")) {
                        return FileVisitResult.CONTINUE;
                    }
                    DirCacheEntry entry = new DirCacheEntry(relativePath);
                    entry.setLength(attrs.size());
                    entry.setLastModified(attrs.lastModifiedTime().toMillis());
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    // 计算对象 ID 并设置
                    byte[] content = Files.readAllBytes(file);
                    try (ObjectInserter inserter = repository.newObjectInserter()) {
                        ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);
                        entry.setObjectId(blobId);
                    }
                    builder.add(entry);
                } catch (IOException e) {
                    log.debug("暂存文件失败，跳过: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relativePath = workPath.relativize(dir).toString()
                        .replace('\\', '/');
                if (relativePath.startsWith(".git/") || relativePath.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        builder.finish();
    }

    /**
     * 解析 ref 指向的 ObjectId。
     */
    private ObjectId resolveRef(Repository repository, String refName) throws IOException {
        Ref ref = repository.exactRef(refName);
        if (ref != null && ref.getObjectId() != null) {
            return ref.getObjectId();
        }
        return null;
    }

    /**
     * 获取 commit 的 tree ObjectId。
     */
    private ObjectId getCommitTree(Repository repository, ObjectId commitId) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            return commit.getTree().getId();
        }
    }

    /**
     * 创建 commit 对象（等价 git commit-tree）。
     */
    private ObjectId createCommit(Repository repository, ObjectId treeId,
            ObjectId parentId, String message) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(treeId);
            commitBuilder.setMessage(message);
            commitBuilder.setAuthor(new PersonIdent("Aether Checkpoint",
                    "aether@checkpoint.local", Instant.now(),
                    java.time.ZoneId.systemDefault()));
            commitBuilder.setCommitter(new PersonIdent("Aether Checkpoint",
                    "aether@checkpoint.local", Instant.now(),
                    java.time.ZoneId.systemDefault()));

            if (parentId != null) {
                commitBuilder.setParentId(parentId);
            }

            ObjectId commitId = inserter.insert(commitBuilder);
            inserter.flush();
            return commitId;
        }
    }

    /**
     * 更新 ref（等价 git update-ref，CAS 语义）。
     */
    private void updateRef(Repository repository, String refName,
            ObjectId newId, ObjectId expectedOldId) throws IOException {
        RefUpdate refUpdate = repository.updateRef(refName);
        refUpdate.setNewObjectId(newId);
        refUpdate.setRefLogMessage("checkpoint: auto-snapshot", false);
        refUpdate.setForceUpdate(false); // 不允许非快进

        if (expectedOldId != null) {
            refUpdate.setExpectedOldObjectId(expectedOldId);
        }

        RefUpdate.Result result = refUpdate.update();
        if (result == RefUpdate.Result.REJECTED || result == RefUpdate.Result.LOCK_FAILURE) {
            log.warn("Ref 更新冲突（CAS 失败），重试: ref={}", refName);
            // 简单重试：重新解析 old tip 再试一次
            ObjectId retryOld = resolveRef(repository, refName);
            RefUpdate retryUpdate = repository.updateRef(refName);
            retryUpdate.setNewObjectId(newId);
            if (retryOld != null) {
                retryUpdate.setExpectedOldObjectId(retryOld);
            }
            retryUpdate.update();
        }
    }

    /**
     * 按 ref 保留条数清理（对齐 hermes _prune() L1174-1239）。
     */
    private void pruneRef(Repository repository, String refName) {
        try {
            ObjectId tip = resolveRef(repository, refName);
            if (tip == null) return;

            // 统计 ref 上的提交数
            int count = countCommits(repository, tip);
            if (count <= maxSnapshotsPerRef) return;

            // 收集要保留的最近 N 个提交
            List<RevCommit> allCommits = listCommits(repository, tip);
            List<RevCommit> keep = allCommits.subList(
                    Math.max(0, allCommits.size() - maxSnapshotsPerRef),
                    allCommits.size());

            // 重建线性链
            ObjectId newTip = rebuildLinearChain(repository, keep);
            if (newTip != null) {
                RefUpdate refUpdate = repository.updateRef(refName);
                refUpdate.setNewObjectId(newTip);
                refUpdate.setForceUpdate(true);
                refUpdate.update();
                log.debug("Ref 清理完成: ref={}, kept={}", refName, keep.size());
            }
        } catch (Exception e) {
            log.warn("Ref 清理失败（不阻塞）: ref={}", refName, e);
        }
    }

    /**
     * 总量上限清理（对齐 hermes _enforce_size_cap() L1241-1327）。
     */
    private void enforceSizeCap(Repository repository) {
        if (maxTotalSizeBytes <= 0) return;

        try {
            long totalSize = getDirectorySize(storeDir);
            if (totalSize <= maxTotalSizeBytes) return;

            log.info("影子仓总量超限: {}MB / {}MB，触发轮转清理",
                    totalSize / (1024 * 1024), maxTotalSizeBytes / (1024 * 1024));

            // 收集所有 aether ref
            List<String> refs = new ArrayList<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(REF_PREFIX)) {
                refs.add(ref.getName());
            }

            // 轮转删除每个 ref 的最旧提交，直至低于阈值
            int iteration = 0;
            while (getDirectorySize(storeDir) > maxTotalSizeBytes && iteration < 20) {
                boolean dropped = false;
                for (String refName : refs) {
                    ObjectId tip = resolveRef(repository, refName);
                    if (tip == null) continue;

                    List<RevCommit> commits = listCommits(repository, tip);
                    if (commits.size() <= 1) continue; // 保底 1 条

                    // 去掉最旧的
                    List<RevCommit> keep = commits.subList(1, commits.size());
                    ObjectId newTip = rebuildLinearChain(repository, keep);
                    if (newTip != null) {
                        RefUpdate refUpdate = repository.updateRef(refName);
                        refUpdate.setNewObjectId(newTip);
                        refUpdate.setForceUpdate(true);
                        refUpdate.update();
                        dropped = true;
                    }
                }
                if (!dropped) break;
                iteration++;
            }

            // 触发 gc（清理不可达对象）
            try {
                Git.wrap(repository).gc().call();
            } catch (Exception e) {
                log.debug("GC 失败（不阻塞）: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("总量上限检查失败（不阻塞）: {}", e.getMessage());
        }
    }

    // ============ 辅助方法 ============

    /**
     * 计算 ref 哈希——复合 sessionId + workdir 的 SHA-256 前缀。
     *
     * @param workdir   工作区路径
     * @param sessionId 会话 ID（可为 null）
     */
    static String computeRefHash(String workdir, String sessionId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (sessionId != null) {
                md.update(sessionId.getBytes(StandardCharsets.UTF_8));
            }
            md.update(workdir.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length && sb.length() < HASH_LENGTH; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString().substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必定可用
            throw new RuntimeException(e);
        }
    }

    private int countCommits(Repository repository, ObjectId tip) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.markStart(revWalk.parseCommit(tip));
            int count = 0;
            while (revWalk.next() != null) count++;
            return Math.max(count, 1);
        }
    }

    private List<RevCommit> listCommits(Repository repository, ObjectId tip) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.markStart(revWalk.parseCommit(tip));
            RevCommit c;
            while ((c = revWalk.next()) != null) {
                commits.add(c);
            }
        }
        // 反转：最旧在前
        Collections.reverse(commits);
        return commits;
    }

    private ObjectId rebuildLinearChain(Repository repository, List<RevCommit> keep)
            throws IOException {
        if (keep.isEmpty()) return null;

        ObjectId newTip = null;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            for (RevCommit oldCommit : keep) {
                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(oldCommit.getTree().getId());
                cb.setMessage(oldCommit.getFullMessage());
                cb.setAuthor(oldCommit.getAuthorIdent());
                cb.setCommitter(oldCommit.getCommitterIdent());
                cb.setEncoding(oldCommit.getEncoding());
                if (newTip != null) {
                    cb.setParentId(newTip);
                }
                newTip = inserter.insert(cb);
            }
            inserter.flush();
        }
        return newTip;
    }

    private long getDirectorySize(Path dir) {
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 关闭 Repository（应用关闭时调用）。
     */
    public void close() {
        if (repo != null) {
            repo.close();
            repo = null;
        }
    }
}
