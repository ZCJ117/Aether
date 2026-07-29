package cn.zcj.aether.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenTelemetry Java Agent 配置属性占位类。
 *
 * <p>otel.* 属性由 OTel Java Agent（-javaagent）在 JVM 启动时读取，
 * 不属于 Spring Boot 标准属性体系。本类仅用于让 IDE 的
 * spring-boot-configuration-processor 识别 otel 前缀，消除
 * "无法解析配置属性" 警告。
 */
@Component
@ConfigurationProperties(prefix = "otel")
public class OtelProperties {

    private Traces traces = new Traces();
    private Exporter exporter = new Exporter();
    private Service service = new Service();

    public Traces getTraces() { return traces; }
    public void setTraces(Traces traces) { this.traces = traces; }
    public Exporter getExporter() { return exporter; }
    public void setExporter(Exporter exporter) { this.exporter = exporter; }
    public Service getService() { return service; }
    public void setService(Service service) { this.service = service; }

    public static class Traces {
        private String exporter;

        public String getExporter() { return exporter; }
        public void setExporter(String exporter) { this.exporter = exporter; }
    }

    public static class Exporter {
        private Otlp otlp = new Otlp();

        public Otlp getOtlp() { return otlp; }
        public void setOtlp(Otlp otlp) { this.otlp = otlp; }
    }

    public static class Otlp {
        private String endpoint;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    public static class Service {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
