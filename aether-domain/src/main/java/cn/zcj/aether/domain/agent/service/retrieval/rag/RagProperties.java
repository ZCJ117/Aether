package cn.zcj.aether.domain.agent.service.retrieval.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P1(4.2): RAG 三级检索管道配置（键前缀 {@code aether.rag}）。
 *
 * <p>三级均带独立开关 + 延迟预算，任一级失败/超时自动降级到上一级输出
 * （切换条件见 RetrievalPipeline）；{@code enabled=false} 时整条管道旁路，
 * 记忆召回走原路径（默认，零行为变化）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aether.rag")
public class RagProperties {

    /** 管道总开关（false = 完全旁路，RecallFlow 走原检索路径） */
    private boolean enabled = false;

    /** 一级：查询改写 */
    private Rewrite rewrite = new Rewrite();

    /** 二级：混合召回（语义 + 词法 RRF） */
    private Hybrid hybrid = new Hybrid();

    /** 三级：重排 */
    private Rerank rerank = new Rerank();

    @Data
    public static class Rewrite {
        /** 改写开关（需 ChatModel；关闭则用原始 query） */
        private boolean enabled = false;
        /** 改写超时预算（ms），超时降级原 query */
        private long timeoutMs = 800;
        /** 生成同义变体个数（变体并入词法查询扩展召回） */
        private int variants = 2;
    }

    @Data
    public static class Hybrid {
        /** 混合召回开关（关闭/端口缺失时退化为纯向量） */
        private boolean enabled = true;
        /** 语义路召回量 */
        private int vectorTopK = 30;
        /** 词法路召回量 */
        private int lexicalTopK = 30;
        /** 融合候选池上限（送入重排的规模） */
        private int candidateLimit = 50;
        /** RRF 常数 k（越大越平滑，标准值 60） */
        private int rrfK = 60;
    }

    @Data
    public static class Rerank {
        /** 重排开关（关闭/服务不可用时保留 RRF 序） */
        private boolean enabled = false;
        /** 重排超时预算（ms），超时降级 RRF 序 */
        private long timeoutMs = 800;
        /** 重排后保留条数 */
        private int topN = 10;
    }
}
