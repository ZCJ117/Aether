package cn.zcj.aether.domain.agent.model.graph;

import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;

/**
 * 工作流边类型枚举
 */
public enum AgentEdgeType {

    SEQUENTIAL,
    PARALLEL,
    LOOP;

    public static AgentEdgeType fromYamlType(String yamlType) {
        return switch (yamlType) {
            case "sequential" -> SEQUENTIAL;
            case "parallel" -> PARALLEL;
            case "loop" -> LOOP;
            default -> throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "Unknown workflow type: " + yamlType);
        };
    }
}
