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
}
