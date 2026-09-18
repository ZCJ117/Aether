package cn.zcj.aether.domain.agent.service.memory.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆编排器 —— 对齐 hermes agent/memory_manager.py 的 MemoryManager。
 *
 * <p>约束：builtin provider 恒被接受；外部（非 builtin）provider 同一时刻只允许
 * 一个，重复注册打警告并拒绝（防 tool schema 膨胀与后端冲突）。</p>
 *
 * <p>同步策略：turn 后写入提交到单线程后台 executor，保证 turn N 先于 turn N+1
 * 落盘；{@link #drain()} 有 5 秒排水超时。</p>
 */
@Slf4j
public final class MemoryManager {

    public static final String BUILTIN_NAME = "builtin";
    private static final long DRAIN_TIMEOUT_SECONDS = 5;

    private final MemoryProperties props;
    private final List<MemoryProvider> providers = new ArrayList<>();
    private boolean hasExternal = false;
    private final AtomicInteger userTurnCount = new AtomicInteger(0);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    public MemoryManager(MemoryProperties props) {
        this.props = props != null ? props : new MemoryProperties();
    }

    /** 注册 provider；builtin 恒接受，外部只许一个。 */
    public synchronized void addProvider(MemoryProvider provider) {
        if (provider == null) {
            return;
        }
        boolean isBuiltin = BUILTIN_NAME.equals(provider.name());
        if (!isBuiltin) {
            if (hasExternal) {
                log.warn("拒绝记忆 provider '{}' — 外部 provider '{}' 已注册。"
                        + "同一时刻只允许一个外部记忆 provider。",
                        provider.name(), firstExternalName());
                return;
            }
            hasExternal = true;
        }
        providers.add(provider);
    }

    /** 汇总所有 provider 的系统提示块 + nudge 提醒。 */
    public String buildSystemPrompt() {
        if (!props.isEnabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryProvider p : providers) {
            try {
                sb.append(p.systemPromptBlock());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' systemPromptBlock 失败: {}", p.name(), e.getMessage());
            }
        }
        sb.append(buildNudge());
        return sb.toString();
    }

    /** nudge：累计轮次达到 nudge-interval 倍数时注入保存记忆提醒。 */
    String buildNudge() {
        int interval = props.getNudgeInterval();
        int turns = userTurnCount.get();
        if (interval <= 0 || turns <= 0 || turns % interval != 0) {
            return "";
        }
        return "\n[Memory nudge: 请考虑是否将本回合的重要信息保存到记忆。]\n";
    }

    //NOTE 这里的 prefetchAll 会调用各个 provider 的 prefetch 方法
    //NOTE 每个p调用prefetch()重写的方法，也就是BuiltinMemoryProvider的prefetch()方法，返回的记忆内容会被注入到instruction里
    /** turn 前召回：汇总各 provider 上下文，单个失败不阻塞其余。 */
    public String prefetchAll(String query, String sessionId) {
        if (!props.isEnabled()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (MemoryProvider p : providers) {
            try {
                String ctx = p.prefetch(query, sessionId);
                if (ctx != null) {
                    out.append(ctx);
                }
            } catch (Exception e) {
                log.warn("记忆 provider '{}' prefetch 失败: {}", p.name(), e.getMessage());
            }
        }
        return out.toString();
    }

    //NOTE
    /** turn 后同步：计数 + 异步提交各 provider 写入。 */
    public void syncAll(String userMsg, String assistantResponse, String sessionId,
                        List<Map<String, Object>> messages) {
        if (!props.isEnabled()) {
            return;
        }
        //NOTE turn计数+1
        userTurnCount.incrementAndGet();
        for (MemoryProvider p : providers) {
            try {
                //NOTE 异步提交各 provider 写入，保证 turn N 先于 turn N+1 落盘
                syncExecutor.submit(() -> {
                    try {
                        //NOTE 这里调用重写的syncTurn方法，也就是BuiltinMemoryProvider的syncTurn方法
                        p.syncTurn(userMsg, assistantResponse, sessionId, messages);
                    } catch (Exception e) {
                        log.warn("记忆 provider '{}' syncTurn 失败: {}", p.name(), e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // drain/shutdown 后提交被拒：降级为告警，不中断调用方（如 ChatService 的 turn 路径）
                log.warn("记忆 provider '{}' syncTurn 提交被拒（executor 已关闭）: {}",
                        p.name(), e.getMessage());
            }
        }
    }

    /** turn 后为下一轮入队后台召回。 */
    public void queuePrefetchAll(String query, String sessionId) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.queuePrefetch(query, sessionId);
            } catch (Exception e) {
                log.warn("记忆 provider '{}' queuePrefetch 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 汇总所有 tool schema。 */
    public List<Map<String, Object>> getAllToolSchemas() {
        if (!props.isEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (MemoryProvider p : providers) {
            try {
                schemas.addAll(p.getToolSchemas());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' getToolSchemas 失败: {}", p.name(), e.getMessage());
            }
        }
        return schemas;
    }

    /** 会话结束回调。 */
    public void onSessionEnd(List<Map<String, Object>> messages) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.onSessionEnd(messages);
            } catch (Exception e) {
                log.warn("记忆 provider '{}' onSessionEnd 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 会话切换回调。 */
    public void onSessionSwitch(String newSessionId, String parentSessionId, boolean reset) {
        if (!props.isEnabled()) {
            return;
        }
        for (MemoryProvider p : providers) {
            try {
                p.onSessionSwitch(newSessionId, parentSessionId, reset, false, Map.of());
            } catch (Exception e) {
                log.warn("记忆 provider '{}' onSessionSwitch 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    /** 已累计的用户轮次。 */
    public int getUserTurnCount() {
        return userTurnCount.get();
    }

    /** 等待所有后台写入完成（5 秒超时）。 */
    public void drain() {
        syncExecutor.shutdown();
        try {
            if (!syncExecutor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                syncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            syncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 干净退出：drain 后调用各 provider shutdown。 */
    public void shutdown() {
        drain();
        for (MemoryProvider p : providers) {
            try {
                p.shutdown();
            } catch (Exception e) {
                log.warn("记忆 provider '{}' shutdown 失败: {}", p.name(), e.getMessage());
            }
        }
    }

    private String firstExternalName() {
        return providers.stream()
                .filter(p -> !BUILTIN_NAME.equals(p.name()))
                .map(MemoryProvider::name)
                .findFirst()
                .orElse("unknown");
    }
}
