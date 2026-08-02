package cn.zcj.aether.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型配置 DTO — 用于 GET /api/v1/models 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigDTO {
    /** 模型唯一标识 */
    private String id;
    /** Provider 名称 */
    private String providerId;
    /** 模型 ID */
    private String modelId;
    /** 连接状态 */
    private String status;
}
