package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import java.util.List;

/**
 * H5-步骤3: 列表哈希工具——前缀采样哈希比对，用于判断增量追加 vs 全量重写。
 *
 * <p>移植自 AgentScope {@code ListHashUtil}（agentscope-core/state/ListHashUtil.java L147-170）。
 * 核心思路：对列表前缀（已持久化部分）采样5个固定位置计算哈希，
 * 与已存哈希比对——前缀未变只 append、变了或收缩则全量重写。
 *
 * <p>搭配 {@code FileCheckpointCollector} 的 JSONL WAL 流使用。
 */
public final class ListHashUtil {

    private ListHashUtil() {}

    /** 空列表哨兵哈希值 */
    private static final String EMPTY_HASH = "empty:0";

    /**
     * 计算列表的采样哈希。
     *
     * <p>采样策略：
     * <ul>
     *   <li>size ≤ 5：全量采样</li>
     *   <li>size > 5：固定5点采样 [0, size/4, size/2, size*3/4, size-1]</li>
     * </ul>
     *
     * @param values 列表（可为 null 或空）
     * @return 十六进制哈希字符串
     */
    public static String computeHash(List<?> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }

        int size = values.size();
        StringBuilder sb = new StringBuilder();
        sb.append("size:").append(size).append(';');

        if (size <= 5) {
            for (int i = 0; i < size; i++) {
                sb.append(i).append(':').append(System.identityHashCode(values.get(i))).append(',');
            }
        } else {
            int[] positions = {0, size / 4, size / 2, size * 3 / 4, size - 1};
            for (int pos : positions) {
                sb.append(pos).append(':').append(System.identityHashCode(values.get(pos))).append(',');
            }
        }

        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * 判断是否需要全量重写。
     *
     * @param currentValues 当前完整列表
     * @param storedHash    已存储的哈希（可为 null）
     * @param existingCount 已持久化的条目数
     * @return true=需要全量重写，false=仅需追加尾部增量
     */
    public static boolean needsFullRewrite(List<?> currentValues, String storedHash, int existingCount) {
        int currentSize = currentValues != null ? currentValues.size() : 0;

        // 列表收缩 → 全量重写
        if (currentSize < existingCount) {
            return true;
        }

        // 缺失哈希但有已持久化数据（版本升级/损坏）→ 全量重写
        if (storedHash == null && existingCount > 0) {
            return true;
        }

        // 无已持久化数据 → 仅追加
        if (existingCount == 0 || storedHash == null) {
            return false;
        }

        // 前缀哈希比对：只对前 existingCount 个元素采样
        List<?> prefix = currentValues.subList(0, existingCount);
        String prefixHash = computeHash(prefix);

        return !prefixHash.equals(storedHash);
    }
}
