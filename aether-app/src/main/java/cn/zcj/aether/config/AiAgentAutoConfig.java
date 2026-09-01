package cn.zcj.aether.config;

import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.IArmoryService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.ArrayList;

// NOTE 6,执行onApplicationEvent方法，调用IArmoryService的acceptArmoryAgents方法
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    // NOTE 注入AiAgentAutoConfigProperties类的属性值，
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // O17: enabled 开关真实生效（D17：原实现从不读取该字段）。
        // false 时跳过自动装配——不装配任何 Agent，由显式调用方自行触发。
        if (!aiAgentAutoConfigProperties.isEnabled()) {
            log.info("ai.agent.config.enabled=false，跳过 Agent 自动装配");
            return;
        }
        try {
            // NOTE 将AiAgentAutoConfigProperties.getTables()传递给IArmoryService，用于自动装配AI Agent
            log.info("Ai Agent 智能体装配 {}", JSON.toJSONString(aiAgentAutoConfigProperties.getTables().values()));

            armoryService.acceptArmoryAgents(new ArrayList<>(aiAgentAutoConfigProperties.getTables().values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
