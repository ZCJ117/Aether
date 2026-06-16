package cn.zcj.aether.domain.agent.service.armory.factory;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.armory.node.RootNode;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的装配工厂
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private AgentRegistry agentRegistry;

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return AiAgentRegisterVO.builder()
                .agentId(agentId)
                .agentGraph(agentRegistry.get(agentId))
                .build();
    }

    /**
     * 策略树上下文对象，节点之间共享数据
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * P0-2 改造：ChatModel 现在由 ChatModelNode 创建并设置
         */
        private ChatModel chatModel;

        /**
         * P0-2 新增：模型配置
         */
        private ModelConfig modelConfig;

        /**
         * P0-2 新增：解析后的 Provider
         */
        private ModelProvider modelProvider;

        /**
         * Agent 名称列表 (替代原 Google ADK BaseAgent agentGroup)
         */
        @Builder.Default
        private List<String> agentNames = new ArrayList<>();

        @Builder.Default
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }

    }

}
