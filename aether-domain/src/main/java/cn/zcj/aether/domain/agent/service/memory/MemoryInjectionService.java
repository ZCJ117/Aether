package cn.zcj.aether.domain.agent.service.memory;

import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.domain.agent.service.retrieval.IdentifierRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 记忆注入服务 — O2 从 ChatService 拆出。
 *
 * <p>单一职责：将记忆上下文注入 Agent instruction（优先 MemoryLifecycleHooks prefetch，
 * 回退文件存储关键词匹配），并前置注入标识符上下文（项目文件结构+文档索引）。
 */
@Slf4j
@Service
public class MemoryInjectionService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryStore memoryStore;

    /**
     * 记忆生命周期门面（prefetch/syncTurn/onSessionEnd）。
     * required=false：未配置 EmbeddingModel/向量库或 aether.memory.enabled=false 时不影响启动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryLifecycleHooks memoryLifecycleHooks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IdentifierRegistry identifierRegistry;

    /**
     * P1-4: 记忆注入（优先 MemoryLifecycleHooks prefetch，内部经 BuiltinMemoryProvider 委托 MemoryFacade；回退文件存储关键词匹配）。
     */
    public String injectMemory(String instruction, String userMessage, String agentId, String sessionId) {
        // Phase 9: 注入标识符上下文（项目文件结构+文档索引）
        if (identifierRegistry != null) {
            try {
                String identifierCtx = identifierRegistry.buildIdentifierContext();
                if (instruction != null && !identifierCtx.isEmpty()) {
                    instruction = instruction + "\n\n" + identifierCtx;
                }
            } catch (Exception e) {
                log.debug("标识符上下文生成失败: {}", e.getMessage());
            }
        }

        // 新：MemoryLifecycleHooks prefetch（含 <memory-context> 围栏格式化与 char-limit 截断）
        if (memoryLifecycleHooks != null) {
            String memoryBlock = memoryLifecycleHooks.prefetch(userMessage, sessionId);
            if (memoryBlock != null && !memoryBlock.isEmpty()) {
                if (instruction == null) return memoryBlock;
                String enriched = instruction.replace("{memory}", memoryBlock);
                if (enriched.equals(instruction)) {
                    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                             "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryLifecycleHooks 路径）。", agentId);
                }
                return enriched;
            }
        }

        // 回退：文件存储关键词匹配（仅当 MemoryStore 可用时）
        if (memoryStore == null) return instruction;
        String memoryPrompt = memoryStore.loadMemoryPrompt(userMessage);
        if (memoryPrompt == null || memoryPrompt.isEmpty()) return instruction;
        if (instruction == null) return memoryPrompt;
        String enriched = instruction.replace("{memory}", memoryPrompt);
        if (enriched.equals(instruction)) {
            log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
                     "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryStore 回退路径）。", agentId);
        }
        return enriched;
    }
}
