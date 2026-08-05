package cn.zcj.aether.domain.agent.service.tool.python;

import com.fasterxml.jackson.databind.JsonNode;

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
}
