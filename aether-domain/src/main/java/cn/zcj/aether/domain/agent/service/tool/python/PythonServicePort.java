package cn.zcj.aether.domain.agent.service.tool.python;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Port interface for Python microservices.
 * Infrastructure layer implements this via HTTP.
 */
public interface PythonServicePort {

    JsonNode readFile(String path, int offset, int limit);

    JsonNode writeFile(String path, String content);

    JsonNode listDir(String path, boolean recursive);

    JsonNode searchFiles(String pattern, String path, String glob, String mode);

    JsonNode executeCode(String language, String code, int timeout);

    JsonNode readDocument(String path, String format);

    JsonNode createDocument(String path, String format, JsonNode content);

    /**
     * P1(4.2): RAG 重排 —— POST document-service {@code /rerank}。
     *
     * @param query     查询文本
     * @param documents 候选文档文本列表（顺序即候选序）
     * @return {results: [{index, score}...]}（按重排分降序；服务不可用/解析失败返回 null，
     *         调用方降级保留原序）
     */
    JsonNode rerank(String query, List<String> documents);
}
