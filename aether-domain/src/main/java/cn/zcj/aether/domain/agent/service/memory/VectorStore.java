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
}
