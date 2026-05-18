package cn.zcj.aether.domain.agent.service.armory;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.IArmoryService;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

// NOTE 8,调用IArmoryService的acceptArmoryAgents方法之后，到这里调用实现，遍历AiAgentConfigTableVO，获取策略树 handler
@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        for (AiAgentConfigTableVO table : tables) {

            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler =
                    defaultArmoryFactory.armoryStrategyHandler(); // NOTE 9, 通过工厂获取策略树的根节点handler，默认是RootNode
            // 策略树：责任链模式 + 策略路由的组合，每个节点都继承AbstractArmorySupport，要实现doApply方法和get方法，
            // doApply方法是这个节点的具体处理逻辑，get方法是路由到下一个节点的逻辑
            // 策略树可以把YAML配置“翻译”可运行的AI Agent示例

            handler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext());
        }
    }

}
