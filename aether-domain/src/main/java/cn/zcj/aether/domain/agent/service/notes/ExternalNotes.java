package cn.zcj.aether.domain.agent.service.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 外部笔记持久化服务。
 * 将 TODO/NOTES 持久化到 .aether/notes/{sessionId}.json。
 * 支持 TODO 项和 NOTE 项的管理与摘要生成。
 */
@Slf4j
@Component
public class ExternalNotes {

    private static final String NOTES_DIR = ".aether/notes";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** TODO 项状态枚举 */
    public enum TodoStatus {
        PENDING,
        IN_PROGRESS,
        DONE,
        BLOCKED
    }

    /** NOTE 项分类枚举 */
    public enum NoteCategory {
        DECISION,
        FINDING,
        QUESTION,
        REFERENCE
    }

    /** TODO 项 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TodoItem {
        private String id;
        private String content;
        private TodoStatus status;
        @Builder.Default
        private int priority = 2;
        private Instant createdAt;
    }

    /** NOTE 项 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NoteItem {
        private String id;
        private String content;
        private NoteCategory category;
        private Instant createdAt;
    }

    /** 笔记文档（TODO + NOTE） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotesDocument {
        @Builder.Default
        private List<TodoItem> todos = new ArrayList<>();
        @Builder.Default
        private List<NoteItem> notes = new ArrayList<>();
        private Instant lastModified;
    }

    /**
     * 从磁盘加载指定会话的笔记文档。不存在时返回空文档。
     */
    public NotesDocument load(String sessionId) {
        Path filePath = resolvePath(sessionId);
        if (!Files.exists(filePath)) {
            return new NotesDocument();
        }
        try {
            NotesDocument doc = MAPPER.readValue(filePath.toFile(), NotesDocument.class);
            if (doc.getTodos() == null) {
                doc.setTodos(new ArrayList<>());
            }
            if (doc.getNotes() == null) {
                doc.setNotes(new ArrayList<>());
            }
            return doc;
        } catch (IOException e) {
            log.warn("ExternalNotes 加载失败: sessionId={} error={}", sessionId, e.getMessage());
            return new NotesDocument();
        }
    }

    /**
     * 持久化笔记文档到磁盘。
     */
    public void persist(String sessionId, NotesDocument doc) {
        doc.setLastModified(Instant.now());
        Path filePath = resolvePath(sessionId);
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writeValue(filePath.toFile(), doc);
            log.debug("ExternalNotes 已持久化: sessionId={}", sessionId);
        } catch (IOException e) {
            log.error("ExternalNotes 持久化失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 构建笔记摘要文本块，供 Agent 上下文注入使用。
     */
    public String buildSummaryBlock(String sessionId) {
        NotesDocument doc = load(sessionId);
        if (doc.getTodos().isEmpty() && doc.getNotes().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 外部笔记摘要\n");

        List<TodoItem> activeTodos = doc.getTodos().stream()
                .filter(t -> t.getStatus() != TodoStatus.DONE)
                .collect(Collectors.toList());
        if (!activeTodos.isEmpty()) {
            sb.append("### 待办事项\n");
            for (TodoItem todo : activeTodos) {
                sb.append("- [").append(todo.getStatus()).append("] ")
                        .append(todo.getContent()).append("\n");
            }
        }

        if (!doc.getNotes().isEmpty()) {
            sb.append("### 笔记\n");
            for (NoteItem note : doc.getNotes()) {
                sb.append("- [").append(note.getCategory()).append("] ")
                        .append(note.getContent()).append("\n");
            }
        }

        return sb.toString();
    }

    private Path resolvePath(String sessionId) {
        String safeId = sessionId.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return Paths.get(NOTES_DIR, safeId + ".json");
    }
}
