package cn.zcj.aether.domain.agent.service.agent.permission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 / T1-7：{@link PermissionModes#resolve} 的合法值 / 非法值 / 空白 / 非 String 值 / null metadata 解析。
 */
class PermissionModesTest {

    private static RuntimeContext ctxWith(Object modeValue) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(PermissionModes.METADATA_KEY, modeValue);
        return ctx(metadata);
    }

    private static RuntimeContext ctx(Map<String, Object> metadata) {
        return new RuntimeContext("u1", "s1", null, null, "hi", metadata, null);
    }

    /** 捕获 {@link PermissionModes} 自身 logger 的日志事件（按 Level 断言，避免控制台编码干扰）。 */
    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PermissionModes.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PermissionModes.class)).detachAppender(appender);
    }

    @Test
    void resolvesLegalValuesCaseInsensitively() {
        assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith("default")));
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctxWith("plan")));
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctxWith("PLAN")));
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctxWith("Plan")));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionModes.resolve(ctxWith("accept_edits")));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionModes.resolve(ctxWith("ACCEPT_EDITS")));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionModes.resolve(ctxWith("Accept_Edits")));
        assertEquals(PermissionMode.BYPASS, PermissionModes.resolve(ctxWith("bypass")));
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctxWith("  plan  ")));
    }

    @Test
    void fallsBackToDefaultForIllegalValueAndWarnsWithRawValue() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith("NOT_A_MODE")));
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("NOT_A_MODE")),
                    "非法模式值必须打 WARN 且包含非法值原文");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void fallsBackToDefaultForBlankValueSilently() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith("   ")));
            assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith("")));
            assertTrue(appender.list.isEmpty(), "空白值属正常缺省，不应产生任何告警");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void fallsBackToDefaultWhenKeyAbsentOrValueNull() {
        assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctx(Map.of())));
        assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith(null)));
    }

    @Test
    void handlesNonStringValueViaStringValueOf() {
        // 枚举实例 / 其他类型经 String.valueOf 转换后解析
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctxWith(PermissionMode.PLAN)));
        assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(ctxWith(404)));
    }

    @Test
    void returnsDefaultForNullContext() {
        assertEquals(PermissionMode.DEFAULT, PermissionModes.resolve(null));
    }
}
