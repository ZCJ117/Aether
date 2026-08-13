package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图级执行 trace 录制 — 对齐 hermes moa_trace.py 的 opt-in 语义（aether.graph.trace.persistence=true
 * 才异步落 JSONL）。内存保留最近 {@code retention} 次执行，可 getExecutionTrace 回放。
 * <p>best-effort：任何失败 debug log，绝不影响 GraphExecutor 主流程。</p>
 */
@Slf4j
@Component
public class GraphExecutionRecorder {

    /** 单个节点事件。 */
    public record NodeEvent(
            String graphExecutionId,
            String nodeName,
            String agentType,
            GraphFlowState.NodeStatus status,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            String error
    ) {}

    private final int retention;
    private final boolean persistToFile;
    private final String traceDir;
    private final ExecutorService fileWriter;
    private final Map<String, List<NodeEvent>> traces = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Spring 构造。 */
    @Autowired
    public GraphExecutionRecorder(
            @Value("${aether.graph.trace.retention:200}") int retention,
            @Value("${aether.graph.trace.persistence:false}") boolean persistToFile,
            @Value("${aether.graph.trace.dir:./cache/graph-traces}") String traceDir) {
        this.retention = retention;
        this.persistToFile = persistToFile;
        this.traceDir = traceDir;
        this.fileWriter = persistToFile
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "graph-trace-writer");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    /** 测试构造：仅内存。 */
    GraphExecutionRecorder(int retention) {
        this(retention, false, "./cache/graph-traces");
    }

    /** 开始一次图执行，返回 graphExecutionId（内存注册 + 有界逐出）。 */
    public String beginExecution(String sessionId) {
        String id = "gx-" + UUID.randomUUID().toString().substring(0, 8);
        traces.put(id, new ArrayList<>());
        synchronized (order) {
            order.addLast(id);
            while (order.size() > retention) {
                String evict = order.removeFirst();
                traces.remove(evict);
            }
        }
        return id;
    }

    /** 记录一个节点事件；graphExecutionId 已被逐出时忽略。 */
    public void recordNodeEvent(String graphExecutionId, String nodeName, String agentType,
                                GraphFlowState.NodeStatus status, Instant startedAt,
                                Instant finishedAt, long durationMs, String error) {
        List<NodeEvent> events = traces.get(graphExecutionId);
        if (events == null) {
            return;
        }
        NodeEvent ne = new NodeEvent(graphExecutionId, nodeName, agentType, status,
                startedAt, finishedAt, durationMs, error);
        events.add(ne);
        if (persistToFile && fileWriter != null) {
            fileWriter.submit(() -> appendJsonl(graphExecutionId, ne));
        }
    }

    /** 结束一次图执行；error 非空时记录一条 __graph 失败节点。 */
    public void endExecution(String graphExecutionId, Throwable error) {
        if (error == null) {
            return;
        }
        recordNodeEvent(graphExecutionId, "__graph", null, GraphFlowState.NodeStatus.FAILED,
                Instant.now(), Instant.now(), 0, error.getMessage());
    }

    /** 读取指定图执行的节点事件序列（有序、不可变拷贝）。 */
    public List<NodeEvent> getExecutionTrace(String graphExecutionId) {
        List<NodeEvent> events = traces.get(graphExecutionId);
        return events == null ? List.of() : List.copyOf(events);
    }

    private void appendJsonl(String graphExecutionId, NodeEvent ne) {
        try {
            Path dir = Paths.get(traceDir);
            Files.createDirectories(dir);
            String line = MAPPER.writeValueAsString(ne);
            Files.write(dir.resolve(graphExecutionId + ".jsonl"),
                    (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception e) {
            log.debug("GraphExecutionRecorder: 落盘失败 graphExecutionId={}", graphExecutionId, e);
        }
    }
}
