package cn.zcj.aether.domain.agent.service.retrieval.rag;

import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1(4.2): 检索质量确定性评测 —— 纯向量 vs 混合(RRF) vs 混合+重排 的 Recall@5 / MRR 对比。
 *
 * <p>无网络依赖：语料 15 篇中文/混合文档，向量 = bigram token 的哈希投影（确定性伪语义），
 * 词法 = bigram 集合 Jaccard；标注 query 集（30 条，含口语化改写样本）来自
 * {@code src/test/resources/rag/queries.jsonl}（roadmap 4.2 第 4 步要求的 30 条标注集，
 * 此前未接线——现作为常驻 CI 回归的数据源）。断言为质量不变量：
 * hybrid ≥ pure-vector 且重排后端到端不劣，防止检索质量静默劣化。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalTest {

    private static final int DIM = 512;
    private static final MemoryScope SCOPE = new MemoryScope("eval", false);

    private record CorpusDoc(String id, String content, float[] vector, Set<String> tokens) {}

    /** 标注 query（queries.jsonl 一行）：query 文本 + 期望 Top1 文档 + 相关文档集（按内容引用语料）。 */
    private record EvalQuery(String id, String query, String expectedTop1, Set<String> relevant) {}

    private final List<CorpusDoc> corpus = new ArrayList<>();
    private final Map<String, String> idByContent = new HashMap<>();
    private List<EvalQuery> queries;

    /** 语料：3 组主题，每组 5 篇（1 篇与 query 强相关，4 篇噪声）；噪声词故意制造向量路混淆。 */
    private static final String[][] DOCS = {
            // 主题 A：分布式限流
            {"d-a1", "分布式限流采用 Redis 令牌桶算法原子扣减"},
            {"d-a2", "限流中间件按客户端 IP 计数固定窗口"},
            {"d-a3", "集群限流阈值配置与降级策略说明"},
            {"d-a4", "会话存储使用 PostgreSQL JSONB 序列化"},
            {"d-a5", "线程池统一注册管理有界队列"},
            // 主题 B：RAG 检索
            {"d-b1", "RAG 三级检索管道重排模型召回融合"},
            {"d-b2", "pgvector HNSW 索引余弦相似度检索"},
            {"d-b3", "查询改写生成同义变体扩展召回"},
            {"d-b4", "记忆衰减遗忘曲线保留分归档"},
            {"d-b5", "审计日志批量写入削峰解耦"},
            // 主题 C：消息队列
            {"d-c1", "Kafka 消费者手动确认幂等去重死信队列"},
            {"d-c2", "消息按会话 ID 分区保证有序"},
            {"d-c3", "统计聚合消费端增量更新仪表盘"},
            {"d-c4", "生产端发送失败降级直写数据库"},
            {"d-c5", "监控告警规则指标抓取面板"},
    };

    @BeforeAll
    void buildCorpus() throws IOException {
        for (String[] d : DOCS) {
            Set<String> tokens = Set.of(CjkBigram.bigram(d[1]).split(" "));
            corpus.add(new CorpusDoc(d[0], d[1], hashVector(d[1]), tokens));
            idByContent.put(d[1], d[0]);
        }
        queries = loadQueries();
    }

    /** 加载 30 条标注 query（relevantDocs 以语料内容字符串引用，映射回文档 id）。 */
    private List<EvalQuery> loadQueries() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<EvalQuery> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/rag/queries.jsonl")) {
            assertNotNull(in, "评测标注集缺失: classpath:rag/queries.jsonl");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                Set<String> relevant = new HashSet<>();
                for (JsonNode doc : n.get("relevantDocs")) {
                    String id = idByContent.get(doc.asText());
                    assertNotNull(id, "标注引用了语料中不存在的文档: " + doc.asText());
                    relevant.add(id);
                }
                out.add(new EvalQuery(n.get("id").asText(), n.get("query").asText(),
                        idByContent.get(n.get("relevantDocs").get(0).asText()), relevant));
            }
        }
        return out;
    }

    // ====== 确定性伪语义：bigram token 哈希投影 + L2 归一化 ======

    private static float[] hashVector(String text) {
        float[] v = new float[DIM];
        for (String token : CjkBigram.bigram(text).split(" ")) {
            int h = Math.abs(token.hashCode() * 31 + token.length()) % DIM;
            v[h] += 1f;
        }
        float norm = 0f;
        for (float x : v) {
            norm += x * x;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return inter.size() / (double) union.size();
    }

    // ====== 三种检索策略 ======

    private List<String> pureVector(String query, int k) {
        float[] qv = hashVector(query);
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (CorpusDoc d : corpus) {
            scored.add(Map.entry(d.id(), cosine(qv, d.vector())));
        }
        return scored.stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k).map(Map.Entry::getKey).toList();
    }

    private List<String> hybridRrf(String query, int k) {
        float[] qv = hashVector(query);
        Set<String> qTokens = Set.of(CjkBigram.bigram(query).split(" "));
        // 语义路 rank
        List<String> semanticRank = corpus.stream()
                .sorted((a, b) -> Double.compare(cosine(qv, b.vector()), cosine(qv, a.vector())))
                .map(d -> d.id()).toList();
        // 词法路 rank —— 仅含词法命中（jaccard>0）的文档，对齐 SQL `@@ plainto_tsquery` 过滤语义：
        // 零重叠文档不进入词法路，避免并列 0 分被稳定排序放大成虚假 rank 贡献
        List<String> lexicalRank = corpus.stream()
                .filter(d -> jaccard(qTokens, d.tokens()) > 0)
                .sorted((a, b) -> Double.compare(
                        jaccard(qTokens, tokensOf(a.id())), jaccard(qTokens, tokensOf(b.id()))))
                .map(d -> d.id()).toList();
        // RRF k=60
        Map<String, Double> rrf = new HashMap<>();
        rrf(semanticRank, rrf);
        rrf(lexicalRank, rrf);
        return rrf.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k).map(Map.Entry::getKey).toList();
    }

    private void rrf(List<String> ranked, Map<String, Double> rrf) {
        for (int i = 0; i < ranked.size(); i++) {
            rrf.merge(ranked.get(i), 1.0 / (60 + i + 1), Double::sum);
        }
    }

    private List<String> hybridPlusRerank(String query, int k) {
        List<String> candidates = hybridRrf(query, 10);
        Set<String> qTokens = Set.of(CjkBigram.bigram(query).split(" "));
        // 重排：bigram F1 精排（与 Python 启发式同口径）
        return candidates.stream()
                .sorted((a, b) -> Double.compare(
                        f1(qTokens, tokensOf(b)), f1(qTokens, tokensOf(a))))
                .limit(k).toList();
    }

    private double f1(Set<String> q, Set<String> d) {
        if (q.isEmpty() || d.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String t : q) {
            if (d.contains(t)) {
                inter++;
            }
        }
        if (inter == 0) {
            return 0;
        }
        double p = inter / (double) d.size();
        double r = inter / (double) q.size();
        return 2 * p * r / (p + r);
    }

    private Set<String> tokensOf(String id) {
        return corpus.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow().tokens();
    }

    // ====== 指标 ======

    private double recallAt5(List<String> ranked, Set<String> relevant) {
        Set<String> top5 = new HashSet<>(ranked.subList(0, Math.min(5, ranked.size())));
        top5.retainAll(relevant);
        return relevant.isEmpty() ? 0 : top5.size() / (double) relevant.size();
    }

    private double mrr(List<String> ranked, String expectedTop1) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).equals(expectedTop1)) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    @Test
    void hybridBeatsOrMatchesPureVectorAndPipelineIsStable() {
        double vecRecall = 0, vecMrr = 0, hybRecall = 0, hybMrr = 0, rrRecall = 0, rrMrr = 0;
        List<String[]> report = new ArrayList<>();
        for (EvalQuery q : queries) {
            String query = q.query();
            String top1 = q.expectedTop1();
            Set<String> relevant = q.relevant();

            List<String> v = pureVector(query, 10);
            List<String> h = hybridRrf(query, 10);
            List<String> r = hybridPlusRerank(query, 5);

            vecRecall += recallAt5(v, relevant);
            vecMrr += mrr(v, top1);
            hybRecall += recallAt5(h, relevant);
            hybMrr += mrr(h, top1);
            rrRecall += recallAt5(r, relevant);
            rrMrr += mrr(r, top1);
            report.add(new String[]{q.id() + " " + query, top1, v.get(0), h.get(0), r.get(0)});
        }
        int n = queries.size();
        vecRecall /= n; vecMrr /= n; hybRecall /= n; hybMrr /= n; rrRecall /= n; rrMrr /= n;

        System.out.printf(Locale.ROOT,
                "%n=== 检索评测（%d queries, Recall@5 / MRR）===%n", n);
        System.out.printf(Locale.ROOT, "pure-vector      : %.3f / %.3f%n", vecRecall, vecMrr);
        System.out.printf(Locale.ROOT, "hybrid (RRF)     : %.3f / %.3f%n", hybRecall, hybMrr);
        System.out.printf(Locale.ROOT, "hybrid + rerank  : %.3f / %.3f%n", rrRecall, rrMrr);
        for (String[] row : report) {
            System.out.printf(Locale.ROOT, "q=[%s] expect=%s vec=%s hybrid=%s rerank=%s%n",
                    (Object[]) row);
        }

        // 质量不变量（数字随标注集实测校准）：
        // ① 混合召回提升 Recall@5（词法路补回语义混淆的文档）
        assertTrue(hybRecall >= vecRecall,
                "混合 Recall@5 应不低于纯向量: " + hybRecall + " vs " + vecRecall);
        // ② 重排恢复/保持 MRR（最终管道端到端不劣于纯向量）
        assertTrue(rrMrr >= vecMrr,
                "重排后 MRR 应不低于纯向量: " + rrMrr + " vs " + vecMrr);
        // ③ 管道端到端稳定性：Recall@5 ≥ 0.6
        assertTrue(rrRecall >= 0.6, "重排后 Recall@5 应≥0.6: " + rrRecall);
    }
}
