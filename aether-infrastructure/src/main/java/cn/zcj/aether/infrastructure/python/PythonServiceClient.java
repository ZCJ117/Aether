package cn.zcj.aether.infrastructure.python;

import cn.zcj.aether.domain.agent.service.tool.python.PythonServicePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP client for Aether Python microservices using {@link RestTemplate}.
 * Implements {@link PythonServicePort} so domain-layer tools can call these
 * services without depending on infrastructure details.
 */
@Slf4j
@Component
public class PythonServiceClient implements PythonServicePort {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String docUrl;
    private final String sandboxUrl;
    private final String fsUrl;

    public PythonServiceClient(
            RestTemplate restTemplate,
            ObjectMapper mapper,
            @Value("${aether.python.doc-url:http://localhost:8001}") String docUrl,
            @Value("${aether.python.sandbox-url:http://localhost:8002}") String sandboxUrl,
            @Value("${aether.python.fs-url:http://localhost:8003}") String fsUrl) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.docUrl = docUrl;
        this.sandboxUrl = sandboxUrl;
        this.fsUrl = fsUrl;
    }

    private JsonNode post(String baseUrl, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                baseUrl + path, HttpMethod.POST, entity, JsonNode.class);
        return resp.getBody();
    }

    // ── File System ─────────────────────────────────────────

    @Override
    public JsonNode readFile(String path, int offset, int limit) {
        Map<String, Object> body = Map.of("path", path, "offset", offset, "limit", limit);
        return post(fsUrl, "/file/read", body);
    }

    @Override
    public JsonNode writeFile(String path, String content) {
        Map<String, Object> body = Map.of("path", path, "content", content);
        return post(fsUrl, "/file/write", body);
    }

    @Override
    public JsonNode listDir(String path, boolean recursive) {
        Map<String, Object> body = Map.of("path", path, "recursive", recursive);
        return post(fsUrl, "/dir/list", body);
    }

    @Override
    public JsonNode searchFiles(String pattern, String path, String glob, String mode) {
        ObjectNode body = mapper.createObjectNode()
                .put("pattern", pattern)
                .put("path", path);
        if (glob != null) body.put("glob", glob);
        body.put("mode", mode);
        return post(fsUrl, "/search", body);
    }

    // ── Sandbox ─────────────────────────────────────────────

    @Override
    public JsonNode executeCode(String language, String code, int timeout) {
        ObjectNode body = mapper.createObjectNode()
                .put("language", language)
                .put("code", code)
                .put("timeout", timeout);
        return post(sandboxUrl, "/execute", body);
    }

    // ── Document ────────────────────────────────────────────

    @Override
    public JsonNode readDocument(String path, String format) {
        Map<String, Object> body = Map.of("path", path, "format", format);
        return post(docUrl, "/read", body);
    }

    @Override
    public JsonNode createDocument(String path, String format, JsonNode content) {
        ObjectNode body = mapper.createObjectNode()
                .put("path", path)
                .put("format", format);
        body.set("content", content);
        return post(docUrl, "/create", body);
    }

    // ── RAG Rerank（P1-4.2）────────────────────────────────────

    @Override
    public JsonNode rerank(String query, java.util.List<String> documents) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            ObjectNode body = mapper.createObjectNode()
                    .put("query", query);
            body.set("documents", mapper.valueToTree(documents));
            return post(docUrl, "/rerank", body);
        } catch (Exception e) {
            log.warn("Python rerank 调用失败（调用方降级原序）: {}", e.getMessage());
            return null;
        }
    }
}
