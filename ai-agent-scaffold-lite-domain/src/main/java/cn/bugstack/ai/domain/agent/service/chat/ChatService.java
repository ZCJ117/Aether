package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Agent对话服务核心实现类
 * 基于Google ADK (Agent Development Kit) 实现对话功能
 * 主要职责：
 * 1. 管理用户会话
 * 2. 处理用户消息并调用Agent执行
 * 3. 支持同步/异步/流式多种消息处理方式
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    /**
     * Agent工厂，管理所有已注册的Agent
     * 通过agentId获取对应的Agent运行配置
     */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /**
     * AI Agent自动配置属性
     * 从配置文件加载Agent配置信息
     */
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * 用户会话映射表
     * key: userId - 用户唯一标识
     * value: sessionId - 会话唯一标识
     * 使用ConcurrentHashMap保证线程安全
     */
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    /**
     * 查询可用的Agent列表
     * 从配置文件中加载所有已注册的Agent信息
     * 返回给前端供用户选择要对话的Agent
     *
     * @return Agent配置列表
     */
    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    /**
     * 创建或获取用户会话
     * 如果用户已有会话，直接返回已有的sessionId
     * 如果用户没有会话，创建新会话并返回新的sessionId
     *
     * 执行流程：
     * 1. 通过agentId获取Agent注册信息（包含Runner）
     * 2. 检查Agent是否存在，不存在则抛出异常
     * 3. 使用ConcurrentHashMap.computeIfAbsent确保同一用户只创建一次会话
     * 4. 调用Google ADK的sessionService创建会话
     * 5. 返回sessionId
     *
     * @param agentId Agent唯一标识
     * @param userId 用户唯一标识
     * @return sessionId 会话唯一标识
     */
    @Override
    public String createSession(String agentId, String userId) {
        // 通过agentId获取Agent注册信息，注册信息中包含了Agent的运行器（Runner）
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 使用computeIfAbsent确保同一用户只创建一次会话
        // 如果用户已有会话，直接返回已有的sessionId
        // 如果用户没有会话，创建新会话并返回新的sessionId
        return userSessions.computeIfAbsent(userId, uid -> {
            Session session = runner.sessionService().createSession(appName, uid)
                    .blockingGet();  // 阻塞等待会话创建完成
            return session.id();
        });
    }

    /**
     * 简单消息处理方法（自动创建会话）
     * 这是最常用的消息处理入口，适用于首次对话或不需要关心sessionId的场景
     *
     * 完整调用链：
     * handleMessage(agentId, userId, message)
     *   -> getAiAgentRegisterVO(agentId) 获取Agent配置
     *   -> createSession(agentId, userId) 创建/获取会话
     *   -> handleMessage(agentId, userId, sessionId, message) 委托给4参数版本处理
     *
     * @param agentId Agent唯一标识
     * @param userId 用户唯一标识
     * @param message 用户发送的文本消息
     * @return Agent的回复列表
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        // 获取Agent注册信息
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 创建或获取已有会话
        String sessionId = createSession(agentId, userId);

        // 委托给4参数版本处理实际消息
        return handleMessage(agentId, userId, sessionId, message);
    }

    /**
     * 指定会话的消息处理方法（阻塞式）
     * 适用于需要保持会话连续性的多轮对话场景
     *
     * 执行流程：
     * 1. 通过agentId获取Agent注册信息
     * 2. 检查Agent是否存在
     * 3. 从AiAgentRegisterVO获取InMemoryRunner
     * 4. 将文本消息封装为Content对象（Google ADK的消息类型）
     * 5. 调用runner.runAsync()异步执行Agent，返回Flowable<Event>事件流
     * 6. 阻塞等待所有事件完成，收集输出
     * 7. 返回所有Agent响应
     *
     * @param agentId Agent唯一标识
     * @param userId 用户唯一标识
     * @param sessionId 会话唯一标识
     * @param message 用户发送的文本消息
     * @return Agent的回复列表
     */
    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        // 获取Agent注册信息
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 获取Agent运行器
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 构建消息内容
        // Content是Google ADK中表示消息内容的类型，由多个Part组成
        // 每个Part可以是文本、文件、图片等不同类型
        Content userMsg = Content.fromParts(Part.fromText(message));

        // 调用Runner的runAsync方法执行对话
        // runAsync方法是异步的，返回一个Flowable<Event>
        // Event是Google ADK中表示对话事件的类型（模型回复、工具调用结果等）
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        // 阻塞式地收集事件内容，转换成字符串列表返回给调用方
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    /**
     * 流式消息处理方法（非阻塞式）
     * 直接返回事件流，调用方可以订阅这个事件流来实时获取模型的回复
     * 适用于需要实时交互的场景，比如WebSocket连接、SSE等
     *
     * 与阻塞式方法的区别：
     * - 阻塞式：调用后等待所有事件完成，一次性返回结果
     * - 流式式：调用后立即返回事件流，调用方可以逐步获取结果
     *
     * @param agentId Agent唯一标识
     * @param userId 用户唯一标识
     * @param sessionId 会话唯一标识
     * @param message 用户发送的文本消息
     * @return 事件流，包含Agent的回复事件
     */
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        // 获取Agent注册信息
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 获取Agent运行器
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 构建消息内容
        Content userMsg = Content.fromParts(Part.fromText(message));

        // 直接返回事件流，不阻塞等待
        return runner.runAsync(userId, sessionId, userMsg);
    }

    /**
     * 复杂内容消息处理方法（支持多模态）
     * 支持文本、文件、内联数据（如图片）等多种内容类型
     *
     * 执行流程：
     * 1. 通过agentId获取Agent注册信息
     * 2. 检查Agent是否存在
     * 3. 构建多模态内容：
     *    - 处理文本内容：Part.fromText()
     *    - 处理文件内容：Part.fromUri() - 支持文件URI
     *    - 处理内联数据：Part.fromBytes() - 支持二进制数据（如图片）
     * 4. 构建完整的Content对象
     * 5. 调用runner.runAsync()执行Agent
     * 6. 阻塞等待所有事件完成，收集输出
     * 7. 返回所有Agent响应
     *
     * @param chatCommandEntity 对话命令实体，包含多种内容类型
     * @return Agent的回复列表
     */
    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        // 获取Agent注册信息
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 构建多模态内容列表
        List<Part> parts = new ArrayList<>();

        // 处理文本内容
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        // 处理文件内容（通过URI引用）
        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        // 处理内联数据（如图片、音频等二进制数据）
        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        // 构建完整的Content对象，设置角色为"user"
        Content content = Content.builder().role("user").parts(parts).build();

        // 获取Agent运行器
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 执行Agent并获取事件流
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        // 阻塞等待所有事件完成，收集输出
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

}
