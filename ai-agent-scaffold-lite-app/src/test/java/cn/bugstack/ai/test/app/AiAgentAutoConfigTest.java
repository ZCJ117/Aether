package cn.bugstack.ai.test.app;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.chat.ChatService;
import cn.bugstack.ai.domain.agent.service.runtime.RuntimeEvent;
import com.alibaba.fastjson.JSON;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Agent 自动配置测试 — 基于自研 AgentRuntime (替代 Google ADK)
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiAgentAutoConfigTest {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ChatService chatService;

    @Value("classpath:file/dog.png")
    private org.springframework.core.io.Resource resource;

    @Test
    public void test_agent() throws InterruptedException {
        AiAgentRegisterVO vo = applicationContext.getBean("100001", AiAgentRegisterVO.class);
        log.info("Agent 已注册: appName={} agentName={}", vo.getAppName(), vo.getAgentName());

        // 使用 ChatService 替代 Google ADK InMemoryRunner
        Flowable<RuntimeEvent> events = chatService.handleMessageStream(
                vo.getAgentId(), "xiaofuge", "test-session-01", "编写冒泡排序");

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> {
            if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                outputs.add(event.getText());
            }
        });

        log.info("测试结果:{}", JSON.toJSONString(outputs));
        new CountDownLatch(1).await();
    }

    @Test
    public void test_handlerMessage_02() {
        AiAgentRegisterVO vo = applicationContext.getBean("100002", AiAgentRegisterVO.class);
        log.info("Agent 已注册: appName={} agentName={}", vo.getAppName(), vo.getAgentName());

        List<String> outputs = chatService.handleMessage(
                vo.getAgentId(), "xiaofuge", "你具备哪些能力");

        log.info("测试结果:{}", JSON.toJSONString(outputs));
    }

    @Test
    public void test_handlerMessage_03() {
        AiAgentRegisterVO vo = applicationContext.getBean("100003", AiAgentRegisterVO.class);
        log.info("Agent 已注册: appName={} agentName={}", vo.getAppName(), vo.getAgentName());

        List<String> outputs = chatService.handleMessage(
                vo.getAgentId(), "xiaofuge", "请描述这张图片的主要内容");

        log.info("测试结果:{}", JSON.toJSONString(outputs));
    }

}
