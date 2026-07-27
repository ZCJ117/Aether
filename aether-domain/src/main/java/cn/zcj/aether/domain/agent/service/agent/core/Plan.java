package cn.zcj.aether.domain.agent.service.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * P1-#4: Agent 执行计划。借鉴 MetaGPT 的 Plan 模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    private String taskDescription;
    private List<Step> steps;
    private int totalSteps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step {
        private int id;
        private String description;
        private String expectedOutput;
        @Builder.Default
        private String status = "pending";
        private String result;
        @Builder.Default
        private List<Integer> dependsOn = new ArrayList<>();

        @Builder.Default
        private int retryCount = 0;
    }

    public static Plan parse(String jsonText) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(jsonText);
            var stepsNode = root.get("steps");
            List<Step> steps = new ArrayList<>();
            if (stepsNode != null && stepsNode.isArray()) {
                int idx = 1;
                for (var node : stepsNode) {
                    List<Integer> dependsOn = new ArrayList<>();
                    if (node.has("dependsOn") && node.get("dependsOn").isArray()) {
                        for (var depNode : node.get("dependsOn")) {
                            dependsOn.add(depNode.asInt());
                        }
                    }
                    steps.add(Step.builder()
                            .id(idx++)
                            .description(node.has("description") ? node.get("description").asText() : "")
                            .expectedOutput(node.has("expectedOutput") ? node.get("expectedOutput").asText() : "")
                            .dependsOn(dependsOn)
                            .build());
                }
            }
            return Plan.builder()
                    .taskDescription(root.has("taskDescription") ? root.get("taskDescription").asText() : "")
                    .steps(steps)
                    .totalSteps(steps.size())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("无法解析LLM返回的计划JSON: " + jsonText, e);
        }
    }
}
