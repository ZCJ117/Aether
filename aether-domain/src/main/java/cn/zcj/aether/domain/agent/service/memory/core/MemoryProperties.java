package cn.zcj.aether.domain.agent.service.memory.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统配置 —— 对齐 hermes cli-config.yaml 的 memory 段。
 *
 * <p>键前缀：{@code aether.memory}；默认值与 hermes 保持一致：</p>
 * <ul>
 *   <li>{@code enabled} ↔ hermes {@code memory.memory_enabled}（true）</li>
 *   <li>{@code user-profile-enabled} ↔ hermes {@code memory.user_profile_enabled}（true）</li>
 *   <li>{@code memory-char-limit} ↔ hermes {@code memory.memory_char_limit}（2200，≈800 token）</li>
 *   <li>{@code user-char-limit} ↔ hermes {@code memory.user_char_limit}（1375，≈500 token）</li>
 *   <li>{@code nudge-interval} ↔ hermes {@code memory.nudge_interval}（10，0=禁用）</li>
 *   <li>{@code flush-min-turns} ↔ hermes {@code memory.flush_min_turns}（6，0=禁用）</li>
 *   <li>{@code recall.max-results} ↔ hermes supermemory {@code max_recall_results}（10）</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "aether.memory", ignoreInvalidFields = true)
public class MemoryProperties {

    /** 记忆系统总开关 */
    private boolean enabled = true;

    /** 用户画像记忆开关 */
    private boolean userProfileEnabled = true;

    /** 注入记忆上下文预算（字符，≈800 token） */
    private int memoryCharLimit = 2200;

    /** 用户画像上下文预算（字符，≈500 token） */
    private int userCharLimit = 1375;

    /** nudge 轮次间隔，0=禁用 */
    private int nudgeInterval = 10;

    /** flush 最小轮次，0=禁用 */
    private int flushMinTurns = 6;

    /** 检索参数 */
    private Recall recall = new Recall();

    /** Embedding 模型配置（记忆向量生成） */
    private Embedding embedding = new Embedding();

    /** 存量向量回填 */
    private Backfill backfill = new Backfill();

    /** 检索参数子配置 */
    @Data
    public static class Recall {
        /** prefetch 检索 Top-K */
        private int maxResults = 10;
        /** 语义相似度权重 */
        private float semanticWeight = 0.6f;
        /** 时间衰减权重 */
        private float recencyWeight = 0.3f;
        /** 重要性权重 */
        private float importanceWeight = 0.1f;
        /** 相似度合并阈值 */
        private float consolidationThreshold = 0.85f;
        /** 向量维度（pgvector，需与 embedding 模型一致） */
        private int vectorDimension = 1024;
    }

    /** Embedding 模型配置子段 */
    @Data
    public static class Embedding {
        /** API Base URL；空则不装配 EmbeddingModel，语义检索降级 */
        private String baseUrl = "";
        /** API Key */
        private String apiKey = "";
        /** Embeddings 路径 */
        private String path = "v1/embeddings";
        /** 模型名（v1/embeddings 接受的 model 参数） */
        private String model = "";
        /** 向量维度 */
        private int dimension = 1024;
    }

    /** 存量回填配置子段 */
    @Data
    public static class Backfill {
        /** 回填开关 */
        private boolean enabled = true;
        /** 每批扫描量 */
        private int batchSize = 50;
        /** 单次启动回填上限 */
        private int maxPerRun = 500;
    }
}
