package cn.bugstack.ai.test;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import com.alibaba.fastjson.JSON;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.InputStream;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiTest {

    public static void main(String[] args) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream resourceAsStream = classLoader.getResourceAsStream("dog.png");
        Resource resource = new ClassPathResource("dog.png", classLoader);
        assert resourceAsStream != null;

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.xiaomimimo.com")
                .apiKey("***REMOVED-CREDENTIAL***")
                .completionsPath("v1/chat/completions")
                .embeddingsPath("v1/embeddings")
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("mimo-v2.5")
                        .build())
                .build();

        // 模型测试，没问题可以识别图片
//        ChatResponse response = chatModel.call(new Prompt(
//                UserMessage.builder()
//                        .text("请描述这张图片的主要内容，并说明图中物品的可能用途。")
//                        .media(Media.builder()
//                                .mimeType(MimeType.valueOf(MimeTypeUtils.IMAGE_PNG_VALUE))
//                                .data(resource)
//                                .build())
//                        .build(),
//                OpenAiChatOptions.builder()
//                        .model("mimo-v2.5")
//                        .build()));
//
//        System.out.println("测试结果" + JSON.toJSONString(response));


//         agent 测试
        LlmAgent agent = LlmAgent.builder()
                .name("test")
                .description("Chess coach agent")
                .model(new MySpringAI(chatModel))
                .instruction("""
                        你是描述大师，能将图片内容描述的非常详细，尤其是图片中的物品和场景。请仔细观察图片，
                        描述图片中的物品、场景、颜色、形状、位置等细节，并尝试推测图片中物品的用途和功能。
                        """)
                .build();

        InMemoryRunner runner = new InMemoryRunner(agent);

        Session session = runner
                .sessionService()
                .createSession("test", "fzw")
                .blockingGet();

        Flowable<Event> events = runner.runAsync("fzw", session.id(),
                Content.fromParts(Part.fromText("这是什么图片"),
                        Part.fromBytes(resource.getContentAsByteArray(), MimeTypeUtils.IMAGE_PNG_VALUE)));

        System.out.print("\nAgent > ");
        events.blockingForEach(event -> System.out.println(event.stringifyContent()));

    }

}
