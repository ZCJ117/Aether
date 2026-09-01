package cn.zcj.aether.domain.agent.service.retrieval.rag;

import cn.zcj.aether.domain.agent.service.support.DaemonThreads;
import cn.zcj.aether.domain.agent.service.support.LlmJson;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * P1(4.2) 一级：查询改写 —— 用便宜模型把口语 query 改写为检索友好形式
 * + 生成 N 个同义变体（roadmap 4.2 第 1 步）。
 *
 * <p>降级链：未启用 / 无 ChatModel / 超时 / 异常 / 解析失败 → 原样返回原 query
 * （{@code rewritten=false}），管道以原 query 继续——改写只增益不添堵。</p>
 */
@Slf4j
@Component
public class QueryRewriter {

    private static final String REWRITE_PROMPT = """
            你是检索查询改写器。把用户口语化查询改写为更适合检索的关键词形式，并生成 %d 个同义变体。
            严格返回 JSON（不要 markdown 代码块）：
            {"rewritten":"<改写后的主查询>","variants":["<变体1>","<变体2>"]}

            用户查询：%s
            """;

    private final ChatModel chatModel;
    private final ExecutorService executor = DaemonThreads.singleThreadExecutor("rag-query-rewriter");

    public QueryRewriter(@Autowired(required = false) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean available() {
        return chatModel != null;
    }

    /** @return 改写结果；任何失败返回原 query（rewritten=false）。 */
    public RewriteResult rewrite(String query, long timeoutMs, int variants) {
        if (!available() || query == null || query.isBlank()) {
            return RewriteResult.identity(query);
        }
        Future<RewriteResult> future = executor.submit(() -> callLlm(query, variants));
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.debug("查询改写超时/失败，使用原查询: {}", e.getMessage());
            return RewriteResult.identity(query);
        }
    }

    private RewriteResult callLlm(String query, int variants) {
        try {
            String prompt = String.format(REWRITE_PROMPT, Math.max(0, variants), query);
            var response = chatModel.call(new Prompt(new UserMessage(prompt)));
            String text = response.getResult().getOutput().getText();
            JsonNode node = LlmJson.parse(text);
            String rewritten = node.has("rewritten") && !node.get("rewritten").asText().isBlank()
                    ? node.get("rewritten").asText() : query;
            List<String> variantList = new ArrayList<>();
            if (node.has("variants") && node.get("variants").isArray()) {
                node.get("variants").forEach(v -> variantList.add(v.asText()));
            }
            return new RewriteResult(rewritten, variantList, true, query);
        } catch (Exception e) {
            log.debug("查询改写解析失败，使用原查询: {}", e.getMessage());
            return RewriteResult.identity(query);
        }
    }

    /** @param rewrittenQuery 改写后主查询；@param rewritten 是否真实改写（false=回退原 query） */
    public record RewriteResult(String rewrittenQuery, List<String> variants, boolean rewritten,
                                String original) {
        static RewriteResult identity(String query) {
            return new RewriteResult(query, List.of(), false, query);
        }
    }
}
