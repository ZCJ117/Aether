package cn.zcj.aether.domain.agent.service.tool.python;

import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Python microservice tool implementations.
 * <p>
 * Each tool wraps an HTTP call to a Python service via {@link PythonServicePort}.
 * Registered automatically in {@link ToolRegistry} on startup.
 *
 * <p>Pattern: follows Hermes Agent's tool registration model —
 * each tool has name, description, JSON Schema input, and a call handler.
 * Tool execution is delegated to Python services via REST.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonTools {

    private final ToolRegistry toolRegistry;
    private final PythonServicePort pythonService;

    /** 已注册的 Python 工具实例（用于生成对 LLM 可见的 ToolCallback） */
    private final java.util.List<Tool> tools = new java.util.ArrayList<>();

    @PostConstruct
    public void registerAll() {
        register(new ReadFileTool());
        register(new WriteFileTool());
        register(new ListDirectoryTool());
        register(new SearchFilesTool());
        register(new ExecuteCodeTool());
        register(new ReadDocumentTool());
        register(new CreateDocumentTool());
        log.info("Registered 7 Python service tools");
    }

    private void register(Tool tool) {
        toolRegistry.register(tool);
        tools.add(tool);
    }

    /**
     * 将 Python 工具转换为对 LLM 可见的 Spring AI ToolCallback。
     *
     * <p>必须在 {@link #registerAll()} 之后调用。由 ChatModel 装配流程
     * （ChatModelNode）收集这些回调并注入模型，LLM 才能调用文件系统等工具。
     */
    public org.springframework.ai.tool.ToolCallback[] toolCallbacks() {
        return tools.stream()
                .map(PythonToolCallbackAdapter::new)
                .toArray(org.springframework.ai.tool.ToolCallback[]::new);
    }

    // ── Tool: read_file ─────────────────────────────────────

    class ReadFileTool implements Tool {
        @Override public String name() { return "read_file"; }

        @Override
        public String description() {
            return "读取文件内容（带行号分页）。支持 .txt, .json, .md, .py, .js, .java, .sh 等文本文件。"
                    + " 参数: path(必填, 文件路径), offset(起始行, 默认1), limit(最大行数, 默认500)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of("type", "string", "description", "文件路径，如 data/uploads/report.txt"),
                    "offset", Map.of("type", "integer", "description", "起始行号，从1开始", "default", 1),
                    "limit", Map.of("type", "integer", "description", "最大返回行数", "default", 500)
                ),
                "required", java.util.List.of("path")
            );
        }

        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String path = (String) input.get("path");
            int offset = input.containsKey("offset") ? ((Number) input.get("offset")).intValue() : 1;
            int limit = input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : 500;
            try {
                JsonNode r = pythonService.readFile(path, offset, limit);
                return ToolResult.success(ctx.toolCallId(), name(), r.get("lines").asText());
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "读取失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: write_file ────────────────────────────────────

    class WriteFileTool implements Tool {
        @Override public String name() { return "write_file"; }

        @Override
        public String description() {
            return "原子写入文件内容（创建或覆盖）。自动创建父目录。"
                    + " 参数: path(必填, 文件路径), content(必填, 文件内容)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of("type", "string", "description", "文件路径"),
                    "content", Map.of("type", "string", "description", "文件内容（UTF-8）")
                ),
                "required", java.util.List.of("path", "content")
            );
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            try {
                JsonNode r = pythonService.writeFile(path, content);
                return ToolResult.success(ctx.toolCallId(), name(), "已写入 " + r.get("size_bytes").asInt() + " 字节到 " + path);
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "写入失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: list_directory ────────────────────────────────

    class ListDirectoryTool implements Tool {
        @Override public String name() { return "list_directory"; }

        @Override
        public String description() {
            return "浏览目录内容，返回文件和子目录列表。"
                    + " 参数: path(必填, 目录路径), recursive(可选, 是否递归, 默认false)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of("type", "string", "description", "目录路径，如 data/uploads/"),
                    "recursive", Map.of("type", "boolean", "description", "是否递归列出", "default", false)
                ),
                "required", java.util.List.of("path")
            );
        }

        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String path = (String) input.get("path");
            boolean recursive = Boolean.TRUE.equals(input.get("recursive"));
            try {
                JsonNode r = pythonService.listDir(path, recursive);
                StringBuilder sb = new StringBuilder("共 ").append(r.get("count").asInt()).append(" 项:\n");
                for (JsonNode e : r.get("entries")) {
                    String icon = "dir".equals(e.get("type").asText()) ? "[DIR]" : "[FILE]";
                    sb.append(icon).append(" ").append(e.get("name").asText())
                      .append(" (").append(e.get("size_bytes").asInt()).append(" bytes)\n");
                }
                return ToolResult.success(ctx.toolCallId(), name(), sb.toString());
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "目录浏览失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: search_files ──────────────────────────────────

    class SearchFilesTool implements Tool {
        @Override public String name() { return "search_files"; }

        @Override
        public String description() {
            return "使用正则表达式搜索文件内容（ripgrep）。"
                    + " 参数: pattern(必填, 正则表达式), path(搜索路径, 默认当前目录),"
                    + " glob(文件过滤, 如 *.py), mode(content/files_only/count, 默认content)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "pattern", Map.of("type", "string", "description", "搜索的正则表达式"),
                    "path", Map.of("type", "string", "description", "搜索路径", "default", "."),
                    "glob", Map.of("type", "string", "description", "文件过滤，如 *.java"),
                    "mode", Map.of("type", "string", "description", "content, files_only, 或 count", "default", "content")
                ),
                "required", java.util.List.of("pattern")
            );
        }

        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String pattern = (String) input.get("pattern");
            String path = (String) input.getOrDefault("path", ".");
            String glob = (String) input.get("glob");
            String mode = (String) input.getOrDefault("mode", "content");
            try {
                JsonNode r = pythonService.searchFiles(pattern, path, glob, mode);
                return ToolResult.success(ctx.toolCallId(), name(), "找到 " + r.get("count").asInt() + " 个匹配:\n" + r.get("matches").toString());
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "搜索失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: execute_code ──────────────────────────────────

    class ExecuteCodeTool implements Tool {
        @Override public String name() { return "execute_code"; }

        @Override
        public String description() {
            return "在安全沙箱中执行代码。支持 Python, JavaScript, Java, Bash。"
                    + " 参数: language(必填, python/javascript/java/bash),"
                    + " code(必填, 源代码), timeout(超时秒数, 默认60)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "language", Map.of("type", "string", "description", "python, javascript, java, 或 bash"),
                    "code", Map.of("type", "string", "description", "要执行的源代码"),
                    "timeout", Map.of("type", "integer", "description", "超时（秒）", "default", 60)
                ),
                "required", java.util.List.of("language", "code")
            );
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String lang = (String) input.get("language");
            String code = (String) input.get("code");
            int timeout = input.containsKey("timeout") ? ((Number) input.get("timeout")).intValue() : 60;
            try {
                JsonNode r = pythonService.executeCode(lang, code, timeout);
                StringBuilder sb = new StringBuilder();
                sb.append("[退出码: ").append(r.get("exit_code").asInt()).append("]");
                sb.append(" [耗时: ").append(r.get("execution_time_ms").asDouble()).append("ms]\n");
                sb.append(r.get("stdout").asText());
                if (r.has("stderr") && !r.get("stderr").asText().isEmpty()) {
                    sb.append("\n[STDERR]\n").append(r.get("stderr").asText());
                }
                return ToolResult.success(ctx.toolCallId(), name(), sb.toString());
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "执行失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: read_document ─────────────────────────────────

    class ReadDocumentTool implements Tool {
        @Override public String name() { return "read_document"; }

        @Override
        public String description() {
            return "读取并解析文档文件（.docx, .pptx, .xlsx），返回结构化内容。"
                    + " 参数: path(必填), format(必填, docx/pptx/xlsx)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of("type", "string", "description", "文档路径"),
                    "format", Map.of("type", "string", "description", "docx, pptx, 或 xlsx")
                ),
                "required", java.util.List.of("path", "format")
            );
        }

        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String path = (String) input.get("path");
            String format = (String) input.get("format");
            try {
                JsonNode r = pythonService.readDocument(path, format);
                return ToolResult.success(ctx.toolCallId(), name(), r.toPrettyString());
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "文档读取失败: " + e.getMessage());
            }
        }
    }

    // ── Tool: create_document ───────────────────────────────

    class CreateDocumentTool implements Tool {
        @Override public String name() { return "create_document"; }

        @Override
        public String description() {
            return "创建新的文档文件（.docx, .pptx, .xlsx）。"
                    + " content参数根据format不同而不同:"
                    + " xlsx → {sheets: {Sheet1: {rows: [[],...]}}},"
                    + " pptx → {slides: [{title, shapes: [{type,text,...}]}]},"
                    + " docx → {paragraphs: [{text,style,...}], tables: [{rows: [[]]}]}"
                    + " 参数: path(必填), format(必填), content(必填, 结构化内容JSON对象)";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of("type", "string", "description", "输出文件路径"),
                    "format", Map.of("type", "string", "description", "docx, pptx, 或 xlsx"),
                    "content", Map.of("type", "object", "description", "结构化文档内容")
                ),
                "required", java.util.List.of("path", "format", "content")
            );
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext ctx) {
            String path = (String) input.get("path");
            String format = (String) input.get("format");
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) input.get("content");
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                JsonNode contentNode = mapper.valueToTree(content);
                JsonNode r = pythonService.createDocument(path, format, contentNode);
                return ToolResult.success(ctx.toolCallId(), name(), "已创建 " + format + " 文件: " + path
                        + " (" + r.get("size_bytes").asInt() + " 字节)");
            } catch (Exception e) {
                return ToolResult.error(ctx.toolCallId(), name(), "文档创建失败: " + e.getMessage());
            }
        }
    }
}
