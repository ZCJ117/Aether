package cn.zcj.aether.domain.agent.service.memory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 向量存储抽象。
 * 支持 Pgvector、Milvus、Qdrant 等后端。
 */
public interface VectorStore {

    /** 存储向量 */
    CompletableFuture<Void> upsert(String id, float[] vector, MemoryRecord record);

    /** 语义相似度搜索 */
    CompletableFuture<List<MemorySearchResult>> search(
        float[] queryVector, int topK, List<MemoryScope> scopes);

    /** 批量存储 */
    CompletableFuture<Void> upsertBatch(List<MemoryRecord> records);

    /** 删除 */
    CompletableFuture<Void> delete(String id);

    /** 清空作用域 */
    CompletableFuture<Void> deleteByScope(MemoryScope scope);

    /** 向量维度 */
    int dimension();

    /** 查找 embedding 缺失的记录（回填用），最多 limit 条 */
    CompletableFuture<List<MemoryRecord>> findMissingEmbeddings(int limit);

    /** 回填更新单条向量 */
    CompletableFuture<Void> updateEmbedding(String id, float[] vector);
}
