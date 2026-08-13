package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphExecutionRecorderTest {

    @Test
    void beginRecordEndProducesOrderedTrace() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        String id = recorder.beginExecution("s1");

        recorder.recordNodeEvent(id, "a", "researcher", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);
        recorder.recordNodeEvent(id, "a", "researcher", GraphFlowState.NodeStatus.COMPLETED,
                Instant.now(), Instant.now(), 100, null);
        recorder.recordNodeEvent(id, "b", "summarizer", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(id);
        assertEquals(3, trace.size());
        assertEquals("a", trace.get(0).nodeName());
        assertEquals(GraphFlowState.NodeStatus.RUNNING, trace.get(0).status());
        assertEquals("a", trace.get(1).nodeName());
        assertEquals(GraphFlowState.NodeStatus.COMPLETED, trace.get(1).status());
        assertEquals("b", trace.get(2).nodeName());
    }

    @Test
    void retentionEvictsOldestExecutions() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(2);
        String id1 = recorder.beginExecution("s1");
        String id2 = recorder.beginExecution("s2");
        recorder.recordNodeEvent(id2, "a", "researcher", GraphFlowState.NodeStatus.COMPLETED,
                Instant.now(), Instant.now(), 50, null);
        String id3 = recorder.beginExecution("s3");
        recorder.recordNodeEvent(id3, "b", "summarizer", GraphFlowState.NodeStatus.COMPLETED,
                Instant.now(), Instant.now(), 60, null);

        assertTrue(recorder.getExecutionTrace(id1).isEmpty(), "最旧执行应被逐出");
        assertEquals(1, recorder.getExecutionTrace(id2).size(), "保留的执行应保留其事件");
        assertEquals(1, recorder.getExecutionTrace(id3).size(), "保留的执行应保留其事件");
    }

    @Test
    void endExecutionRecordsGraphFailureNode() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        String id = recorder.beginExecution("s1");
        recorder.endExecution(id, new RuntimeException("boom"));

        List<GraphExecutionRecorder.NodeEvent> trace = recorder.getExecutionTrace(id);
        assertEquals(1, trace.size());
        assertEquals(GraphFlowState.NodeStatus.FAILED, trace.get(0).status());
        assertEquals("__graph", trace.get(0).nodeName());
    }

    @Test
    void unknownIdReturnsEmpty() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        assertTrue(recorder.getExecutionTrace("nope").isEmpty());
    }

    @Test
    void eventsForEvictedIdAreIgnored() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(1);
        String id = recorder.beginExecution("s1");
        recorder.beginExecution("s2"); // 逐出 id
        recorder.recordNodeEvent(id, "a", "x", GraphFlowState.NodeStatus.RUNNING,
                Instant.now(), null, 0, null);
        assertTrue(recorder.getExecutionTrace(id).isEmpty());
    }
}
