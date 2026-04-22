package cn.bugstack.ai.test.api.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Spring AI Test
 * 文档：<a href="https://docs.spring.io/spring-ai/reference/1.0/api/advisors.html">spring ai</a>
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/14 09:15
 */
@Slf4j
public class SpringAiApiTest {

//    public static void main(String[] args) {
//        OpenAiApi openAiApi = OpenAiApi.builder()
//                .baseUrl("https://apis.itedus.cn/")
//                .apiKey("***REMOVED-CREDENTIAL***")
//                .completionsPath("v1/chat/completions")
//                .embeddingsPath("v1/embeddings")
//                .build();
//
//        ChatModel chatModel = OpenAiChatModel.builder()
//                .openAiApi(openAiApi)
//                .defaultOptions(OpenAiChatOptions.builder()
//                        .model("gpt-4.1")
//                        .build())
//                .build();
//
//        String call = chatModel.call("hi 你好哇!");
//
//        log.info("测试结果:{}", call);
//    }

    public static void main(String[] args) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                // 去掉结尾的 /v1，框架会自动补 /v1/chat/completions
                .baseUrl("https://api.deepseek.com")
                .apiKey("***REMOVED-CREDENTIAL***")
                // completionsPath 可以不写，它默认就是 /v1/chat/completions
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-reasoner")   // 或 "deepseek-chat"
                        .build())
                .build();

        String call = chatModel.call("hi 你好哇!");
        log.info("测试结果:{}", call);
    }




}
