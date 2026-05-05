package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 * @author zuochangjian
 * 2026/4/28
 */
public interface IChatService {

    // Agent列表，这个再渲染到前端，用户选择要和哪个Agent对话
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    //消息处理接口，输入是agentId、userId、消息内容，输出是Agent的回复列表
    List<String> handleMessage(String agentId, String userId, String message);

    //handleMessage方法的重载版本，增加了sessionId参数
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    //流式消息处理接口
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
