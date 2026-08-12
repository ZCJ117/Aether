package cn.zcj.aether.domain.agent.service.agent.hook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HookPointTest {

    @Test
    void coversHermesLifecyclePoints() {
        // 覆盖 hermes VALID_HOOKS 的三类：API request / subagent / session
        assertNotNull(HookPoint.valueOf("PRE_API_REQUEST"));
        assertNotNull(HookPoint.valueOf("POST_API_REQUEST"));
        assertNotNull(HookPoint.valueOf("API_REQUEST_ERROR"));
        assertNotNull(HookPoint.valueOf("SUBAGENT_START"));
        assertNotNull(HookPoint.valueOf("SUBAGENT_STOP"));
        assertNotNull(HookPoint.valueOf("ON_SESSION_START"));
        assertNotNull(HookPoint.valueOf("ON_SESSION_END"));
    }

    @Test
    void coversAetherGraphPoints() {
        assertNotNull(HookPoint.valueOf("ON_GRAPH_NODE_START"));
        assertNotNull(HookPoint.valueOf("ON_GRAPH_NODE_END"));
        assertNotNull(HookPoint.valueOf("ON_GRAPH_FINALIZE"));
    }
}
