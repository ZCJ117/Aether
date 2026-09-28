package cn.zcj.aether.domain.agent.service.retrieval.rag;

import java.util.List;

/**
 * P1(4.2): 重排端口 —— 候选 Top-50 → 精排 → Top-N。
 * infrastructure 实现（PythonReranker）经既有 PythonServicePort 通道调用
 * document-service {@code /rerank}，架构零改动。
 */
public interface RerankPort {

    /**
     * @param query      原始（或改写后）查询
     * @param candidates 待重排候选（按融合分降序）
     * @param topN       重排后保留条数
     * @return 重排结果；实现方在服务不可用/超时时应抛出异常，由调用方
     *         （RetrievalPipeline）捕获并降级为保留原序（降级安全）
     */
    List<RetrievalDocument> rerank(String query, List<RetrievalDocument> candidates, int topN);
}
