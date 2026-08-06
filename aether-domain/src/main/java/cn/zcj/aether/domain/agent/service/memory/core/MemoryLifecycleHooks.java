package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryFacade;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * ChatService 记忆生命周期门面 —— 对齐 hermes MemoryManager 在 turn 循环中的接入：
 * turn 前 {@link #prefetch}、turn 后 {@link #syncTurn}、会话结束 {@link #onSessionEnd}。
 *
 * <p>组装 {@link MemoryManager} + {@link BuiltinMemoryProvider}；builtin 委托现有
 * {@link MemoryFacade}。{@code aether.memory.enabled=false} 时不激活。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryLifecycleHooks {

    private final MemoryProperties props;
    private final MemoryFacade memoryFacade; // 可空：未配置向量库/EmbeddingModel 时不激活

    private volatile MemoryManager manager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MemoryLifecycleHooks(MemoryProperties props, MemoryFacade memoryFacade) {
        this.props = props != null ? props : new MemoryProperties();
        this.memoryFacade = memoryFacade;
    }

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("aether.memory.enabled=false，记忆系统未激活");
            return;
        }
        MemoryManager m = new MemoryManager(props);
        m.addProvider(new BuiltinMemoryProvider(memoryFacade, props));
        this.manager = m;
        log.info("MemoryLifecycleHooks 已初始化，provider=builtin, enabled={}", props.isEnabled());
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** turn 前召回：返回待注入的 {@code <memory-context>} 围栏文本（空串=无）。 */
    public String prefetch(String query, String sessionId) {
        MemoryManager m = manager;
        return m == null ? "" : m.prefetchAll(query, sessionId);
    }

    /** turn 后持久化：非阻塞异步写入。 */
    public void syncTurn(String userContent, String assistantContent,
                         String sessionId, List<Map<String, Object>> messages) {
        MemoryManager m = manager;
        if (m != null) {
            m.syncAll(userContent, assistantContent, sessionId, messages);
        }
    }

    /** 会话结束：轮次 ≥ flush-min-turns 时触发 onSessionEnd（对齐 hermes flush_min_turns）。 */
    public void onSessionEnd(String sessionId, int sessionTurnCount) {
        MemoryManager m = manager;
        if (m == null) {
            return;
        }
        int min = props.getFlushMinTurns();
        if (min <= 0 || sessionTurnCount >= min) {
            m.onSessionEnd(List.of());
        }
    }

    /** 等待所有后台写入完成。 */
    public void drain() {
        MemoryManager m = manager;
        if (m != null) {
            m.drain();
        }
    }

    /** 干净退出。 */
    public void shutdown() {
        MemoryManager m = manager;
        if (m != null) {
            m.shutdown();
        }
    }
}
