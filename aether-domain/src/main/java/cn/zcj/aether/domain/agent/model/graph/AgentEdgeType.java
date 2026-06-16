package cn.zcj.aether.domain.agent.model.graph;

import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;

/**
 * 工作流边类型枚举
 */
public enum AgentEdgeType {

    SEQUENTIAL,
    PARALLEL,
    LOOP,
    GRAPHFLOW;    // P1-1 新增：DAG 图流模式

    public static AgentEdgeType fromYamlType(String yamlType) {
        if (yamlType == null) return null;
        return switch (yamlType.toLowerCase()) {
            case "sequential" -> SEQUENTIAL;
            case "parallel" -> PARALLEL;
            case "loop" -> LOOP;
            case "graphflow" -> GRAPHFLOW;    // P1-1 新增
            default -> throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "未知的工作流类型: " + yamlType);
        };
    }
}
