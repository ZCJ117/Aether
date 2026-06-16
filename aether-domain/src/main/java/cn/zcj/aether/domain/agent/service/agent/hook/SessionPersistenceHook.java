package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.session.SessionEntity;
import cn.zcj.aether.domain.agent.service.session.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;

/**
 * 自动会话持久化钩子 — P0-4 新增。
 * 每次 Agent 执行完成后异步保存状态。
 */
@Slf4j
@Component
@ConditionalOnBean(SessionRepository.class)
public class SessionPersistenceHook implements AgentHook {

    @Resource
    private SessionRepository sessionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public int priority() { return 200; } // 靠后执行，确保其他 hook 先完成

    @Override
    public void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {
        try {
            String stateJson = objectMapper.writeValueAsString(agent.saveState());
            SessionEntity entity = SessionEntity.builder()
                .sessionId(ctx.sessionId())
                .userId(ctx.userId())
                .agentId(agent.getId())
                .status("ACTIVE")
                .stateJson(stateJson)
                .updatedAt(Instant.now())
                .build();
            sessionRepository.save(entity);
        } catch (Exception e) {
            log.error("会话持久化失败: agentId={}, sessionId={}", agent.getId(), ctx.sessionId(), e);
        }
    }

    @Override
    public void onError(Agent agent, RuntimeContext ctx, Throwable error) {
        try {
            String stateJson = objectMapper.writeValueAsString(agent.saveState());
            SessionEntity entity = SessionEntity.builder()
                .sessionId(ctx.sessionId())
                .userId(ctx.userId())
                .agentId(agent.getId())
                .status("ERROR")
                .stateJson(stateJson)
                .updatedAt(Instant.now())
                .build();
            sessionRepository.save(entity);
        } catch (Exception e) {
            log.error("错误状态持久化失败", e);
        }
    }
}
