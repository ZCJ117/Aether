package cn.zcj.aether.domain.agent.service;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * 装配接口
 *
 *
 * @author zuochangjian
 * 2026/04/24
 */
//NOTE 7,在AiAgentAutoConfig类中，调用IArmoryService的acceptArmoryAgents方法
public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
