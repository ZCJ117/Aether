package cn.zcj.aether.domain.agent.service.compiler;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HookConfigLoaderTest {

    private HookRegistry registry = new HookRegistry();
    private HookConfigLoader loader = new HookConfigLoader(registry);

    @Test
    void registersShellHooksFromConfig() {
        AiAgentConfigTableVO.Module.HookConfigVO cfg = new AiAgentConfigTableVO.Module.HookConfigVO();
        cfg.setPoint("SUBAGENT_START");
        cfg.setCommand("python scripts/notify.py");
        cfg.setTimeoutMs(5000);

        loader.load(List.of(cfg));

        assertEquals(1, registry.hooksFor(HookPoint.SUBAGENT_START).size());
    }

    @Test
    void skipsUnknownPointWithWarning() {
        AiAgentConfigTableVO.Module.HookConfigVO cfg = new AiAgentConfigTableVO.Module.HookConfigVO();
        cfg.setPoint("not_a_real_point");
        cfg.setCommand("echo hi");

        loader.load(List.of(cfg));

        assertTrue(registry.hooksFor(HookPoint.SUBAGENT_START).isEmpty());
    }

    @Test
    void skipsBlankCommand() {
        AiAgentConfigTableVO.Module.HookConfigVO cfg = new AiAgentConfigTableVO.Module.HookConfigVO();
        cfg.setPoint("ON_SESSION_END");
        cfg.setCommand("");

        loader.load(List.of(cfg));

        assertTrue(registry.hooksFor(HookPoint.ON_SESSION_END).isEmpty());
    }

    @Test
    void nullOrEmptyConfigIsNoOp() {
        loader.load(null);
        loader.load(List.of());
        assertTrue(registry.hooksFor(HookPoint.ON_GRAPH_FINALIZE).isEmpty());
    }

    @Test
    void duplicateRegistrationIsDeduplicated() {
        AiAgentConfigTableVO.Module.HookConfigVO cfg = new AiAgentConfigTableVO.Module.HookConfigVO();
        cfg.setPoint("SUBAGENT_START");
        cfg.setCommand("echo hi");

        loader.load(List.of(cfg, cfg));

        assertEquals(1, registry.hooksFor(HookPoint.SUBAGENT_START).size());
    }
}
