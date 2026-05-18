package cn.zcj.aether.domain.agent.model.valobj.properties;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;

import java.util.Map;

// NOTE 5,项目启动之后，加载only-one-agent.properties.yml配置，绑定到AiAgentAutoConfigProperties类中，
//  之后通过@Resource注解注入到AiAgentAutoConfig类中

// NOTE 用prefix = "ai.agent.config"绑定配置,这里导入的配置文件在application-dev.yml中设置的
// 通过yml文件和@ConfigurationProperties注解，配置AiAgentAutoConfigProperties类的属性值

@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    //上面的配置中，会自动将yml文件中ai.agent.config.tables的值绑定到这个属性上，类型是Map<String, AiAgentConfigTableVO>
    private Map<String, AiAgentConfigTableVO> tables;

}
