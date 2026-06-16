package cn.zcj.aether.domain.agent.service.memory;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台记忆提取器 —— 在 Agent 执行完成后异步提取关键信息写入长期记忆。
 * 灵感来源：cc-haha SessionMemory（后台 Fork Agent 提取）。
 *
 * 作为 AgentHook 注册，在 onAfterExecute 中触发。
 */
@Slf4j
@Component
public class SessionMemoryExtractor implements AgentHook {

    private final MemoryFacade memoryFacade;
    private final ExecutorService extractor = Executors.newSingleThreadExecutor();

    public SessionMemoryExtractor(MemoryFacade memoryFacade) {
        this.memoryFacade = memoryFacade;
    }

    @Override public int priority() { return 300; } // 最后执行

    @Override
    public void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {
        // 异步提取，不阻塞主流程
        extractor.submit(() -> {
            try {
                List<TurnMessage> messages = agent.getState().getMessages();
                if (messages.size() < 5) return; // 不够一定量不提取

                // 提取最后一条 assistant 响应中的关键信息
                String lastResponse = getLastAssistantMessage(messages);
                if (lastResponse != null && lastResponse.length() > 20) {
                    MemoryScope scope = MemoryScope.global()
                        .subscope("agent")
                        .subscope(agent.getId());

                    memoryFacade.remember(lastResponse, scope,
                        new MemoryFacade.StoreOptions());
                }
            } catch (Exception e) {
                log.warn("后台记忆提取失败: agentId={}", agent.getId(), e);
            }
        });
    }

    private String getLastAssistantMessage(List<TurnMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return null;
    }
}
