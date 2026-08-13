package cn.zcj.aether.domain.agent.service.subagent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaleDelegationScannerTest {

    @Test
    void scanOnceDelegatesDetectStaleWithTimeout() {
        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        when(lifecycle.detectStale(Duration.ofMinutes(10)))
                .thenReturn(List.of("ad-1", "ad-2"));

        StaleDelegationScanner scanner = new StaleDelegationScanner(lifecycle,
                Duration.ofMinutes(10), 1000);
        List<String> stale = scanner.scanOnce();

        assertEquals(List.of("ad-1", "ad-2"), stale);
        verify(lifecycle).detectStale(Duration.ofMinutes(10));
    }

    @Test
    void zeroIntervalDoesNotSchedule() {
        SubagentLifecycleService lifecycle = mock(SubagentLifecycleService.class);
        StaleDelegationScanner scanner = new StaleDelegationScanner(lifecycle,
                Duration.ofMinutes(1), 0);
        scanner.start();
        // 不抛异常即通过（interval<=0 不启动调度线程）
        scanner.shutdown();
    }
}
