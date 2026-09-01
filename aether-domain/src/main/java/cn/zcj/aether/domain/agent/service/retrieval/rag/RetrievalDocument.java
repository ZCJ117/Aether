package cn.zcj.aether.domain.agent.service.retrieval.rag;

/**
 * P1(4.2): 检索文档 —— RAG 管道统一流转单元。
 *
 * @param id      文档/记忆 id
 * @param content 文本内容
 * @param score   当前阶段得分（语义相似度 / RRF 融合分 / 重排分）
 * @param source  来源标注（rrf-hybrid / rerank / pure-vector 等）
 */
public record RetrievalDocument(String id, String content, double score, String source) {
}
