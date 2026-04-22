package cn.bugstack.ai.test.api.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * LangChain4j
 * <p>
 * 文档：<a href="https://docs.langchain4j.info/">langchain4j</a>
 * 案例：<a href="https://github.com/langchain4j/langchain4j-examples">langchain4j-examples</a>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/14 09:20
 */
@Slf4j
public class LangChain4jApiTest {

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")   // DeepSeek 官方 base_url（兼容 OpenAI）
                .apiKey("***REMOVED-CREDENTIAL***")                // 替换成你自己的 DeepSeek API Key
                .modelName("deepseek-reasoner")               // 聊天模型，推理模型可换成 deepseek-reasoner
                .build();

        String chat = model.chat("hi 你好哇!");
        log.info("测试结果:{}", chat);
    }


}
