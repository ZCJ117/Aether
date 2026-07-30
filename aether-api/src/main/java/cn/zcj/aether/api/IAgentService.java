package cn.zcj.aether.api;

import cn.zcj.aether.api.dto.*;
import cn.zcj.aether.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author zuochangjian
 * 2026/ 5/10
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    Response<List<SessionItemDTO>> listSessions(String agentId, String userId);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

}
