package cn.zcj.aether.domain.agent.service.retrieval.rag;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;

import java.util.List;

/**
 * P1(4.2): 混合召回端口 —— pgvector 语义 Top-K + PG 全文（bigram tsvector）Top-K，
 * RRF（Reciprocal Rank Fusion, k=60）单 SQL 融合。infrastructure 实现（PgHybridSearchRepository）。
 */
public interface HybridSearchPort {

    /**
     * @param queryVector 语义查询向量（null/全零时退化为纯词法召回）
     * @param lexicalQuery 已 bigram化的词法查询（{@link CjkBigram#bigram}）
     * @param topK         融合后返回条数
     * @param scopes       作用域过滤（可空）
     */
    List<RetrievalDocument> search(float[] queryVector, String lexicalQuery, int topK,
                                   List<MemoryScope> scopes);
}
