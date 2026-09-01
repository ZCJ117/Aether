package cn.zcj.aether.domain.agent.service.agent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 类型注册表 — O15（对应 D15）。
 *
 * <p>消除 {@code DefaultAgentFactory} 硬编码 react/plan_act 的扩展瓶颈：
 * Agent 类型可经两条途径注册——
 * <ol>
 *   <li>Spring Bean 自动发现：实现 {@link AgentFactory} 的 Bean 按 {@code supportedType()} 注册；</li>
 *   <li>配置驱动：{@code aether.agent.types.<type>.factory-bean=<beanName>} 指定工厂 Bean，
 *       同名类型后注册覆盖内置（对齐 hermes providers 注册表 last-writer-wins）。</li>
 * </ol>
 */
@Slf4j
@Component
public class AgentTypeRegistry {

    /** aether.agent.types.<type> 的配置形状 */
    public static class AgentTypeSpec {
        private String factoryBean;

        public String getFactoryBean() { return factoryBean; }
        public void setFactoryBean(String factoryBean) { this.factoryBean = factoryBean; }
    }

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private Environment environment;

    /** type -> AgentFactory */
    private final Map<String, AgentFactory> factories = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        // 1) Spring Bean 自动发现
        Map<String, AgentFactory> beans = applicationContext.getBeansOfType(AgentFactory.class);
        beans.forEach((beanName, factory) -> register(factory.supportedType(), factory));

        // 2) aether.agent.types.<type>.factory-bean 配置驱动（覆盖同名 Bean 注册）
        Map<String, AgentTypeSpec> configured = Binder.get(environment)
                .bind("aether.agent.types", Bindable.mapOf(String.class, AgentTypeSpec.class))
                .orElse(Map.of());
        for (Map.Entry<String, AgentTypeSpec> e : configured.entrySet()) {
            String factoryBean = e.getValue().getFactoryBean();
            if (factoryBean == null || factoryBean.isBlank()) continue;
            AgentFactory factory = applicationContext.getBean(factoryBean, AgentFactory.class);
            register(e.getKey(), factory);
        }

        log.info("AgentTypeRegistry 初始化完成: types={}", factories.keySet());
    }

    /** 注册（或覆盖）一个 Agent 类型工厂 */
    public void register(String type, AgentFactory factory) {
        if (type == null || type.isBlank() || factory == null) return;
        AgentFactory prev = factories.put(type, factory);
        if (prev != null) {
            log.info("覆盖 Agent 类型工厂: type={} {} → {}", type,
                    prev.getClass().getSimpleName(), factory.getClass().getSimpleName());
        }
    }

    /** 解析类型对应的工厂 */
    public Optional<AgentFactory> resolve(String type) {
        return Optional.ofNullable(factories.get(type));
    }

    /** 已注册类型集合 */
    public Set<String> registeredTypes() {
        return Set.copyOf(factories.keySet());
    }
}
