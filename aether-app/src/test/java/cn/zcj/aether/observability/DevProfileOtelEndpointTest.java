package cn.zcj.aether.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D4/F2-7: dev profile 的 OTLP 端点必须可被 {@code OTEL_EXPORTER_OTLP_ENDPOINT} 覆盖。
 *
 * <p><b>为什么需要本用例</b>：{@code docker/docker-compose-fullstack.yml} 以
 * {@code SPRING_PROFILES_ACTIVE=dev} 启动应用（:29），并注入
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT=otel-collector:4317}（:50）。但只有
 * {@code application-prod.yml} 写了 {@code ${OTEL_EXPORTER_OTLP_ENDPOINT:...}} 占位符——
 * dev yml 若把端点写死成 {@code http://localhost:4317}，则容器内 span 会打向**容器自身的
 * loopback**（那里没有 collector），且没有任何报错：导出链路静默失效，T2-9 必然拿不到 span。</p>
 *
 * <p>本用例按 Spring Boot 的真实优先级（环境变量 &gt; application*.yml）解析，
 * 同时锁住"有环境变量时被覆盖"与"无环境变量时回落 localhost"两个方向。</p>
 *
 * <p>不需要任何外部依赖（不连 collector、不启上下文），随默认测试集执行。</p>
 */
class DevProfileOtelEndpointTest {

    private static final String KEY = "otel.exporter.otlp.endpoint";

    @Test
    @DisplayName("D4/F2-7 dev profile 的 OTLP 端点被 OTEL_EXPORTER_OTLP_ENDPOINT 覆盖")
    void devEndpointHonoursEnvOverride() throws Exception {
        assertEquals("otel-collector:4317",
                resolve(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "otel-collector:4317")),
                "compose 以 dev profile 启动并注入 collector 地址；dev yml 写死 localhost 会让"
                        + "容器内 span 打向自身 loopback，导出静默失效");
    }

    @Test
    @DisplayName("D4/F2-7 未注入环境变量时回落 localhost:4317（本地开发形态不变）")
    void devEndpointFallsBackToLocalhost() throws Exception {
        assertEquals("http://localhost:4317", resolve(Map.of()),
                "未注入环境变量时应保持本地开发既有形态");
    }

    /**
     * 按 "环境变量 &gt; application.yml → application-dev.yml" 的真实优先级解析端点。
     *
     * @param envVars 拟注入的环境变量（替代真实系统环境，保证用例可重复）
     */
    private static String resolve(Map<String, Object> envVars) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // 排除真实系统环境变量：本机若恰好设了该变量，断言口径会漂移
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);

        // dev 后插 → 排在 application.yml 之前，与 "profile 覆盖基准配置" 一致
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : List.of("application.yml", "application-dev.yml")) {
            for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                env.getPropertySources().addFirst(source);
            }
        }

        // 环境变量优先级高于 application*.yml —— 最后 addFirst 才能排到最前
        env.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));

        return env.getProperty(KEY);
    }
}
