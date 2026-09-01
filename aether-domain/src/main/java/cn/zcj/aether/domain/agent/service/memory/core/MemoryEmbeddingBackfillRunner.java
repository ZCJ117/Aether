package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 存量记忆向量回填编排。
 *
 * <p>应用就绪后异步扫描 embedding 缺失的记录，调用 EmbeddingModel 补向量。
 * 受 {@code aether.memory.backfill.*} 配置门控；未装配 EmbeddingModel 时静默跳过。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aether.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryEmbeddingBackfillRunner {

    private final VectorStore vectorStore;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties props;

    private final ExecutorService executor;

    /** 测试构造：自建单线程兜底池（不启动 Spring）。 */
    public MemoryEmbeddingBackfillRunner(VectorStore vectorStore,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                         MemoryProperties props) {
        this(vectorStore, embeddingModelProvider, props, defaultExecutor());
    }

    /** Spring 构造：注入共享 memoryIoPool（P0-1 统一线程资源管理）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryEmbeddingBackfillRunner(VectorStore vectorStore,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                         MemoryProperties props,
                                         @org.springframework.beans.factory.annotation.Qualifier("memoryIoPool") ExecutorService executor) {
        this.vectorStore = vectorStore;
        this.embeddingModelProvider = embeddingModelProvider;
        this.props = props;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-backfill");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isEnabled()) return;
        if (!props.getBackfill().isEnabled()) return;
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            log.info("未装配 EmbeddingModel，跳过存量记忆回填");
            return;
        }
        log.info("启动存量记忆回填: batchSize={}, maxPerRun={}",
            props.getBackfill().getBatchSize(), props.getBackfill().getMaxPerRun());
        executor.submit(() -> runBackfill(model));
    }

    /** 回填主循环（package-private 便于单测直接调用） */
    void runBackfill(EmbeddingModel model) {
        int total = 0;
        int maxPerRun = props.getBackfill().getMaxPerRun();
        while (total < maxPerRun) {
            List<MemoryRecord> missing = vectorStore.findMissingEmbeddings(props.getBackfill().getBatchSize()).join();
            if (missing.isEmpty()) {
                log.info("记忆回填完成，共 {} 条", total);
                return;
            }
            int batchSuccess = 0;
            for (MemoryRecord rec : missing) {
                try {
                    float[] vec = model.embed(rec.getContent());
                    vectorStore.updateEmbedding(rec.getId(), vec).join();
                    total++;
                    batchSuccess++;
                } catch (Exception e) {
                    log.warn("回填单条失败，跳过: id={}, error={}", rec.getId(), e.getMessage());
                }
                if (total >= maxPerRun) break;
            }
            // 整批全失败 → 说明 embedding 不可用或记录无法处理，停止避免空转
            if (batchSuccess == 0) {
                log.warn("本批回填全部失败（{} 条），停止回填避免空转", missing.size());
                return;
            }
        }
        log.warn("记忆回填达到单次上限 {}，剩余未回填", maxPerRun);
    }
}
