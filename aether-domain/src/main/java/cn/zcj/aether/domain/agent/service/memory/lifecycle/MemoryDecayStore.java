package cn.zcj.aether.domain.agent.service.memory.lifecycle;

/**
 * P1(4.3): 记忆生命周期端口 —— 衰减归档的存储侧操作。
 *
 * <p>PG 实现（PgvectorVectorStore 同库）按保留分批量软删；
 * 文件后端（MemoryStore）不实现——衰减仅作用于 pgvector 生产后端。</p>
 */
public interface MemoryDecayStore {

    /**
     * 将保留分低于阈值的记忆软删（archived=true）。
     *
     * <p>保留分 = 0.5×新近度（半衰线性，参数 halfLifeDays）+ 0.3×频次（access_count/10 封顶）
     * + 0.2×importance，全部在 SQL 内计算，单批最多 batchSize 条。</p>
     *
     * @return 本批实际归档条数
     */
    int archiveStale(double halfLifeDays, double minRetentionScore, int batchSize);

    /** 活跃（未归档）记忆数 */
    long countActive();

    /** 已归档记忆数 */
    long countArchived();
}
