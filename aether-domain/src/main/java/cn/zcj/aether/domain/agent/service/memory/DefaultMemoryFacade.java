package cn.zcj.aether.domain.agent.service.memory;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryProperties;
import cn.zcj.aether.domain.agent.service.memory.lifecycle.MemoryConflictResolver;
import cn.zcj.aether.domain.agent.service.memory.lifecycle.MemoryWriteGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * MemoryFacade 默认实现。
 * 整合 EncodingFlow（LLM 编码）+ RecallFlow（自适应召回）+ VectorStore（向量存储）。
 *
 * 灵感来源：CrewAI Memory 门面 + MetaGPT LongTermMemory。
 */
@Slf4j
@Component
public class DefaultMemoryFacade implements MemoryFacade {

    private final EncodingFlow encodingFlow;
    private final RecallFlow recallFlow;
    private final VectorStore vectorStore;

    private final ExecutorService storeExecutor = Executors.newSingleThreadExecutor();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentEventPublisher eventPublisher;

    /** 可选：无 EmbeddingModel 时写入 null 向量，检索端降级 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("memoryEmbeddingModel")
    private EmbeddingModel embeddingModel;

    /** O11: pgvector 后端开关（启动自检用） */
    @org.springframework.beans.factory.annotation.Value("${aether.memory.pgvector.enabled:false}")
    private boolean pgvectorEnabled;

    /** P1(4.3): 写入门槛/冲突策略配置（未装配时用默认值：门槛开/concat） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryProperties memoryProperties;

    /** P1(4.3): 冲突合并器（未装配时回退原拼接逻辑） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryConflictResolver conflictResolver;

    /** P1(4.3): 写入门槛指标 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MeterRegistry meterRegistry;

    private volatile Counter writeRejectedCounter;
    private volatile Counter writeAcceptedCounter;
    private volatile Counter conflictCounter;

    public DefaultMemoryFacade(EncodingFlow encodingFlow, RecallFlow recallFlow, VectorStore vectorStore) {
        this.encodingFlow = encodingFlow;
        this.recallFlow = recallFlow;
        this.vectorStore = vectorStore;
    }

    /**
     * O11: 启动自检 — Embedding 装配缺失时显式告警（pgvector 启用但 embedding 缺失为 error 级，
     * 此时向量库语义检索不可用；文件后端缺失为 warn 级，检索降级为时间排序）。
     */
    @jakarta.annotation.PostConstruct
    void checkEmbeddingAssembly() {
        if (embeddingModel != null) {
            log.info("[O11自检] 记忆 Embedding 装配就绪: memoryEmbeddingModel");
            return;
        }
        if (pgvectorEnabled) {
            log.error("[O11自检] aether.memory.pgvector.enabled=true 但未装配 memoryEmbeddingModel"
                    + "（检查 aether.memory.embedding.base-url/api-key 与 ModelProvider）："
                    + "语义检索不可用，PgvectorVectorStore 将退化为时间排序");
        } else {
            log.warn("[O11自检] 未装配 memoryEmbeddingModel：记忆向量将存 null，检索降级为时间排序"
                    + "（如需语义检索请配置 aether.memory.embedding.*）");
        }
    }

    // =========================================================
    // 存储
    // =========================================================

    @Override
    public CompletableFuture<MemoryRecord> remember(String content, MemoryScope scope, StoreOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. LLM 编码
                EncodingFlow.EncodeResult encoded;
                if (options.useLLMEncoding()) {
                    encoded = encodingFlow.encode(content);
                } else {
                    encoded = EncodingFlow.EncodeResult.defaults();
                }

                // C2: 记录内部 LLM 调用（记忆编码）
                if (eventPublisher != null && encoded.llmCalled()) {
                    log.debug("记忆编码 LLM: success={}, {}ms", encoded.llmSuccess(), encoded.llmDurationMs());
                }

                // P1(4.3): 写入质量门槛 —— LLM 判定为琐碎（importance < minImportance）的记忆不入库
                var gate = memoryProperties != null
                        ? memoryProperties.getWriteGate()
                        : new MemoryProperties.WriteGate();
                if (!MemoryWriteGate
                        .shouldAccept(encoded, gate.getMinImportance(), gate.isEnabled())) {
                    incrementCounter("aether.memory.write.rejected.total");
                    log.debug("写入门槛拒绝低价值记忆: importance={} < {}", encoded.importance(), gate.getMinImportance());
                    return MemoryRecord.builder()
                        .id(UUID.randomUUID().toString())
                        .content(content)
                        .scope(scope)
                        .metadata(Map.of("writeGate", "rejected"))
                        .importance(encoded.importance())
                        .createdAt(Instant.now())
                        .lastAccessedAt(Instant.now())
                        .accessCount(0)
                        .isPrivate(scope.isPrivate())
                        .source("gate_rejected")
                        .build();
                }
                incrementCounter("aether.memory.write.accepted.total");

                // 2. 如果应合并，搜索相似记忆
                // 2.5 生成真实向量（未配置 EmbeddingModel → null，检索端降级）
                float[] vector = embedOrNull(content);
                if (encoded.shouldConsolidate() && options.consolidationThreshold() > 0) {
                    List<MemorySearchResult> similar = vectorStore.search(
                        vector, 3,
                        List.of(scope)).join();
                    for (var sim : similar) {
                        if (sim.getScore() >= options.consolidationThreshold()) {
                            log.debug("记忆合并: 相似度={}, targetId={}, strategy={}",
                                    sim.getScore(), sim.getRecord().getId(),
                                    conflictResolver != null ? memoryProperties.getConflict().getStrategy() : "concat");
                            incrementCounter("aether.memory.conflict.resolved.total");
                            // P1(4.3): 冲突合并策略化（concat / new-wins / llm），未装配时回退原拼接
                            var outcome = conflictResolver != null
                                    ? conflictResolver.resolve(sim.getRecord(), content, encoded.importance())
                                    : new MemoryConflictResolver.Outcome(
                                            "concat",
                                            sim.getRecord().getContent() + "\n\n" + content,
                                            (sim.getRecord().getImportance() + encoded.importance()) / 2);
                            String mergedContent = outcome.content();
                            float[] mergedVector = embedOrNull(mergedContent);
                            MemoryRecord merged = MemoryRecord.builder()
                                .id(sim.getRecord().getId())
                                .content(mergedContent)
                                .embedding(mergedVector)
                                .scope(sim.getRecord().getScope())
                                .categories(mergeCategories(sim.getRecord().getCategories(), encoded.categories()))
                                .importance(outcome.importance())
                                .createdAt(sim.getRecord().getCreatedAt())
                                .lastAccessedAt(Instant.now())
                                .accessCount(sim.getRecord().getAccessCount())
                                .isPrivate(sim.getRecord().isPrivate())
                                .source(sim.getRecord().getSource())
                                .archived(false)
                                .build();
                            vectorStore.upsert(merged.getId(), mergedVector, merged).join();
                            return merged;
                        }
                    }
                }

                // 3. 新建记忆记录
                String id = UUID.randomUUID().toString();
                MemoryRecord record = MemoryRecord.builder()
                    .id(id)
                    .content(content)
                    .embedding(vector)
                    .scope(scope)
                    .categories(encoded.categories())
                    .importance(encoded.importance())
                    .createdAt(Instant.now())
                    .lastAccessedAt(Instant.now())
                    .accessCount(1)
                    .isPrivate(scope.isPrivate())
                    .source("agent_extracted")
                    .build();

                vectorStore.upsert(id, vector, record).join();
                return record;
            } catch (Exception e) {
                log.warn("存储记忆失败: scope={}, error={}", scope.path(), e.getMessage());
                return MemoryRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .content(content)
                    .scope(scope)
                    .importance(0.5f)
                    .createdAt(Instant.now())
                    .lastAccessedAt(Instant.now())
                    .accessCount(1)
                    .isPrivate(scope.isPrivate())
                    .source("agent_extracted")
                    .build();
            }
        }, storeExecutor);
    }

    @Override
    public void rememberMany(List<MemoryEntry> entries, MemoryScope scope) {
        for (var entry : entries) {
            remember(entry.content(), scope, new StoreOptions());
        }
    }

    // =========================================================
    // 检索
    // =========================================================

    @Override
    public CompletableFuture<List<MemorySearchResult>> recall(String query, RecallOptions options) {
        if (options.depth() == RecallOptions.RecallDepth.DEEP) {
            return recallFlow.recallDeep(query, options);
        }
        return recallFlow.recallShallow(query, options);
    }

    //NOTE 在这个search方法里又去调用了recallFlow.recallShallow方法
    @Override
    public List<MemorySearchResult> search(String query, MemoryScope scope, int maxResults) {
        var options = new RecallOptions(
            RecallOptions.RecallDepth.SHALLOW,
            List.of(scope),
            maxResults,
            0.6f, 0.3f, 0.1f);
        try {
            return recallFlow.recallShallow(query, options).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("记忆搜索失败: query=[{}], error={}", query, e.getMessage());
            return List.of();
        }
    }

    // =========================================================
    // 维护
    // =========================================================

    @Override
    public void drain() {
        storeExecutor.shutdown();
        try {
            if (!storeExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                storeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            storeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public CompletableFuture<Void> clear(MemoryScope scope) {
        return vectorStore.deleteByScope(scope);
    }

    @Override
    public MemoryStats stats() {
        // 简化实现：VectorStore 不直接提供统计，返回估算值
        return new MemoryStats(0, 0, 0.0);
    }

    // =========================================================
    // 内部方法
    // =========================================================

    private List<String> mergeCategories(List<String> existing, List<String> incoming) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) merged.addAll(existing);
        if (incoming != null) merged.addAll(incoming);
        return new ArrayList<>(merged);
    }

    /** 生成记忆向量；EmbeddingModel 缺失或调用失败返回 null（检索端降级） */
    private float[] embedOrNull(String text) {
        if (embeddingModel == null) return null;
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.warn("记忆向量生成失败，存入 null: {}", e.getMessage());
            return null;
        }
    }

    /** P1(4.3): 生命周期计数（无 MeterRegistry 时 no-op）。 */
    private void incrementCounter(String name) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(name)
                    .description("Memory lifecycle counter")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception ignored) {
            // 指标注册失败不阻断记忆写入
        }
    }
}
