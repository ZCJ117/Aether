package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * P0-#8: 检查点收集器接口。
 * 保存和加载 Agent 执行过程中的检查点快照。
 */
public interface CheckpointCollector {

    /**
     * 保存检查点
     * @param checkpoint 检查点数据
     */
    void save(CheckpointData checkpoint);

    /**
     * 加载指定会话的最新检查点
     * @param sessionId 会话 ID
     * @return 最新检查点（如存在）
     */
    Optional<CheckpointData> loadLatest(String sessionId);

    /**
     * 列出指定会话的所有检查点
     * @param sessionId 会话 ID
     * @return 检查点列表（按时间倒序）
     */
    List<CheckpointData> listCheckpoints(String sessionId);

    /**
     * 加载指定检查点
     * @param sessionId 会话 ID
     * @param turnNumber turn 编号
     * @return 检查点（如存在）
     */
    Optional<CheckpointData> load(String sessionId, int turnNumber);

    // ============ H5-步骤4: 端口扩展——工作区快照（默认方法保证旧实现兼容） ============

    /**
     * 确保工作区已创建快照（每轮每目录至多一次）。
     * 在写类工具首次执行前由 {@code ToolExecutor} 自动触发。
     *
     * <p>默认空实现——文件系统收集器不管理工作区快照，
     * 由 {@code GitShadowCheckpointStore} 提供 JGit 实现。
     *
     * @param workdir 工作区绝对路径
     * @param reason  快照原因（如 "before write_file"）
     */
    default void ensureWorkspaceSnapshot(String workdir, String reason) {
        // 默认空实现
    }

    /**
     * 从影子仓恢复指定文件到指定提交。
     *
     * <p>恢复前应自动打 pre-rollback 快照使撤销可再撤销（对齐 hermes L940-941）。
     *
     * @param workdir    工作区绝对路径
     * @param commitHash 目标提交哈希
     * @param filePath   要恢复的文件路径（相对于工作区根），null 表示恢复整个工作区
     * @return true=恢复成功
     */
    default boolean restore(String workdir, String commitHash, String filePath) {
        return false;
    }
}
