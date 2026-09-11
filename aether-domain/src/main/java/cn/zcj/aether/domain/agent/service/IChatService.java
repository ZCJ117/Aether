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

    /**
     * <p><b>【架构亮点 · 事件驱动统一流式架构】</b><br>
     * 面试举证点：本方法返回 {@code Flowable<RuntimeEvent>}（行23）是贯穿全链路的统一流式原语——
     * 领域层编排（GraphExecutor）经 FlowableEmitter 逐事件发射 RuntimeEvent，HTTP 边缘以
     * ResponseBodyEmitter(SSE) 逐帧下发，跨进程经 Kafka 桥解耦。声明即契约：任何 Agent 图执行
     * 都收敛到这一流式出口，与阻塞式 {@code handleMessage}（List&lt;String&gt;）形成流式/块式双通道。
     */
    // 【流式】统一流式入口：Flowable<RuntimeEvent> 逐事件推送，下游对接 SSE / Kafka
    Flowable<RuntimeEvent> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /** 删除会话（软删除：状态改为 ARCHIVED） */
    void deleteSession(String sessionId);

}
