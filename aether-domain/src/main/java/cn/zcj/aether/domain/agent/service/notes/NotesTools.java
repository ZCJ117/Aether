package cn.zcj.aether.domain.agent.service.notes;

import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NoteCategory;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NoteItem;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NotesDocument;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.TodoItem;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.TodoStatus;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 笔记工具集。
 * 提供 todo_write 和 note_write 两个工具，供 Agent 在对话过程中
 * 管理待办事项和记录决策/发现等笔记。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotesTools {

    private final ExternalNotes externalNotes;

    /**
     * TODO 写入工具。
     * action: add | complete | delete
     * content: 待办内容（add 时必填）
     * id: 待办ID（complete/delete 时必填）
     * priority: 优先级数字（add 时可选，默认2）
     */
    @Component("todoWriteTool")
    @RequiredArgsConstructor
    public static class TodoWriteTool implements Tool {
        private final ExternalNotes externalNotes;

        @Override
        public String name() {
            return "todo_write";
        }

        @Override
        public String description() {
            return """
                管理待办事项。
                add 添加、complete 完成、delete 删除。
                add需要content和可选的priority（默认2）。
                complete/delete需要id。
                """;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string",
                            "description", "操作类型: add, complete, delete"),
                    "content", Map.of("type", "string",
                            "description", "待办内容（add时必填）"),
                    "id", Map.of("type", "string",
                            "description", "待办ID（complete/delete时必填）"),
                    "priority", Map.of("type", "integer",
                            "description", "优先级1-5（add时可选，默认2）")
                ),
                "required", new String[]{"action"}
            );
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext context) {
            String action = (String) input.getOrDefault("action", "add");
            String sessionId = context.sessionId();
            NotesDocument doc = externalNotes.load(sessionId);

            try {
                return switch (action) {
                    case "add" -> {
                        String content = (String) input.get("content");
                        if (content == null || content.isBlank()) {
                            yield ToolResult.error(null, "todo_write",
                                    "content 不能为空");
                        }
                        int priority = input.get("priority") instanceof Number n
                                ? n.intValue() : 2;
                        TodoItem item = TodoItem.builder()
                                .id(UUID.randomUUID().toString().substring(0, 8))
                                .content(content)
                                .status(TodoStatus.PENDING)
                                .priority(priority)
                                .createdAt(Instant.now())
                                .build();
                        doc.getTodos().add(item);
                        externalNotes.persist(sessionId, doc);
                        log.debug("TodoWriteTool: 添加待办 id={} content={}", item.getId(), content);
                        yield ToolResult.success(null, "todo_write",
                                "已添加待办: " + item.getId());
                    }
                    case "complete" -> {
                        String id = (String) input.get("id");
                        if (id == null || id.isBlank()) {
                            yield ToolResult.error(null, "todo_write",
                                    "id 不能为空");
                        }
                        boolean found = false;
                        for (TodoItem item : doc.getTodos()) {
                            if (id.equals(item.getId())) {
                                item.setStatus(TodoStatus.DONE);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            yield ToolResult.error(null, "todo_write",
                                    "未找到待办: " + id);
                        }
                        externalNotes.persist(sessionId, doc);
                        yield ToolResult.success(null, "todo_write",
                                "已完成待办: " + id);
                    }
                    case "delete" -> {
                        String id = (String) input.get("id");
                        if (id == null || id.isBlank()) {
                            yield ToolResult.error(null, "todo_write",
                                    "id 不能为空");
                        }
                        boolean removed = doc.getTodos().removeIf(
                                item -> id.equals(item.getId()));
                        if (!removed) {
                            yield ToolResult.error(null, "todo_write",
                                    "未找到待办: " + id);
                        }
                        externalNotes.persist(sessionId, doc);
                        yield ToolResult.success(null, "todo_write",
                                "已删除待办: " + id);
                    }
                    default -> ToolResult.error(null, "todo_write",
                            "未知操作: " + action + "，支持: add, complete, delete");
                };
            } catch (Exception e) {
                log.error("TodoWriteTool 执行失败: action={}", action, e);
                return ToolResult.error(null, "todo_write",
                        "操作失败: " + e.getMessage());
            }
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }
    }

    /**
     * NOTE 写入工具。
     * category: DECISION | FINDING | QUESTION | REFERENCE
     * content: 笔记内容
     */
    @Component("noteWriteTool")
    @RequiredArgsConstructor
    public static class NoteWriteTool implements Tool {
        private final ExternalNotes externalNotes;

        @Override
        public String name() {
            return "note_write";
        }

        @Override
        public String description() {
            return """
                记录笔记。category: DECISION/FINDING/QUESTION/REFERENCE。
                需提供content。
                """;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "category", Map.of("type", "string",
                            "description", "笔记类别: DECISION, FINDING, QUESTION, REFERENCE"),
                    "content", Map.of("type", "string",
                            "description", "笔记内容")
                ),
                "required", new String[]{"category", "content"}
            );
        }

        @Override
        public ToolResult call(Map<String, Object> input, ToolContext context) {
            String categoryStr = (String) input.get("category");
            String content = (String) input.get("content");
            String sessionId = context.sessionId();

            if (categoryStr == null || categoryStr.isBlank()) {
                return ToolResult.error(null, "note_write", "category 不能为空");
            }
            if (content == null || content.isBlank()) {
                return ToolResult.error(null, "note_write", "content 不能为空");
            }

            NoteCategory category;
            try {
                category = NoteCategory.valueOf(categoryStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(null, "note_write",
                        "无效分类: " + categoryStr
                                + "，支持: DECISION, FINDING, QUESTION, REFERENCE");
            }

            try {
                NotesDocument doc = externalNotes.load(sessionId);
                NoteItem item = NoteItem.builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .content(content)
                        .category(category)
                        .createdAt(Instant.now())
                        .build();
                doc.getNotes().add(item);
                externalNotes.persist(sessionId, doc);
                log.debug("NoteWriteTool: 添加笔记 id={} category={}",
                        item.getId(), category);
                return ToolResult.success(null, "note_write",
                        "已记录笔记: " + item.getId());
            } catch (Exception e) {
                log.error("NoteWriteTool 执行失败", e);
                return ToolResult.error(null, "note_write",
                        "记录失败: " + e.getMessage());
            }
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
}
