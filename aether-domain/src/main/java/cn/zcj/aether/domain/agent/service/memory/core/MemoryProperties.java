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

    /** flush 最小轮次；&lt;=0 表示不设门槛（每会话结束都触发），非禁用。注意与 nudge-interval 的 0 语义相反 */
    private int flushMinTurns = 6;

    /** 检索参数 */
    private Recall recall = new Recall();

    /** Embedding 模型配置（记忆向量生成） */
    private Embedding embedding = new Embedding();

    /** 存量向量回填 */
    private Backfill backfill = new Backfill();

    /** P1(4.3): 遗忘曲线（衰减归档） */
    private Decay decay = new Decay();

    /** P1(4.3): 写入质量门槛 */
    private WriteGate writeGate = new WriteGate();

    /** P1(4.3): 冲突合并策略 */
    private Conflict conflict = new Conflict();

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

    /** P1(4.3): 遗忘曲线子段 —— 保留分 = 0.5×新近度 + 0.3×频次 + 0.2×重要性，低于阈值软删 */
    @Data
    public static class Decay {
        /** 衰减开关（需 pgvector 后端；文件后端不执行） */
        private boolean enabled = true;
        /** 扫描间隔（分钟），<=0 禁用 */
        private int intervalMinutes = 60;
        /** 新近度半衰期（天）—— last_accessed_at 距今每过 half-life-days，新近分减 1 */
        private double halfLifeDays = 30.0;
        /** 保留分阈值，低于则归档（archived=true） */
        private double minRetentionScore = 0.2;
        /** 单轮归档上限（批处理保护） */
        private int maxArchivePerRun = 1000;
        /** 批大小 */
        private int batchSize = 200;
    }

    /** P1(4.3): 写入门槛子段 —— LLM 打分 importance < minImportance 的候选记忆不入库 */
    @Data
    public static class WriteGate {
        /** 门槛开关（仅在 LLM 成功打分时生效，无 LLM 时不拦） */
        private boolean enabled = true;
        /** 最低重要性（0-1；0.6 ≈ 3/5，对齐路线图"≥3 才入库"） */
        private float minImportance = 0.6f;
    }

    /** P1(4.3): 冲突合并子段 —— 同主题相似度超阈值时的处理策略 */
    @Data
    public static class Conflict {
        /** concat=拼接保留新旧（原行为） | new-wins=新胜旧 | llm=LLM 合并/替换（失败回退 concat） */
        private String strategy = "concat";
    }
}
