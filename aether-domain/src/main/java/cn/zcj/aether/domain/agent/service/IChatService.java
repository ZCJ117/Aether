package cn.zcj.aether.domain.agent.service;

import cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 */
public interface IChatService {

    List<AiAgentConfigTableVO> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<RuntimeEvent> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /** 删除会话（软删除：状态改为 ARCHIVED） */
    void deleteSession(String sessionId);

}
