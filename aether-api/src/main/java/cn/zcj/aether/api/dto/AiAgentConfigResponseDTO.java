package cn.zcj.aether.api.dto;

import lombok.Data;

/**
 * 智能体配置响应对象
 *
 * @author zuochangjian
 * 2026/ 5/10
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 模型引用
     */
    private String modelRef;

}
