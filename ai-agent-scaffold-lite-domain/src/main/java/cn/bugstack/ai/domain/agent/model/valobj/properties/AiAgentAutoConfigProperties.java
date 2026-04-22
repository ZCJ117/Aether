package cn.bugstack.ai.domain.agent.model.valobj.properties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;

import java.util.Map;


// NOTE 用prefix = "ai.agent.config"绑定配置,这里导入的配置文件在application-dev.yml中设置的
// 通过yml文件和@ConfigurationProperties注解，配置AiAgentAutoConfigProperties类的属性值
// 之后通过@Resource注解注入到AiAgentAutoConfig类中
@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    private Map<String, AiAgentConfigTableVO> tables;

}
