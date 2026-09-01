package cn.zcj.aether.domain.agent.service.executor.orchestration;

import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionContext;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestrationServicesBroadcastTest {

    @Mock
    private InterventionHandler interventionHandler;

    @Test
    void broadcastUsesPublishInterceptorInsteadOfSendInterceptor() {
        OrchestrationServices services = new OrchestrationServices(
                null, interventionHandler, null, null);
        InterventionContext ctx = new InterventionContext(
                InterventionContext.ChannelType.BROADCAST, "agent", "session", "user",
                "PARALLEL", "workflow", java.util.Map.of());
        when(interventionHandler.onPublish("message", ctx))
                .thenReturn(InterventionResult.drop("blocked by publish policy"));

        boolean accepted = services.applyBroadcastInterception("message", ctx, "agent");

        assertThat(accepted).isFalse();
        verify(interventionHandler).onPublish("message", ctx);
        verify(interventionHandler, never()).onSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
