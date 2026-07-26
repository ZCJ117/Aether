package cn.zcj.aether.domain.agent.service.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {
    private int stepId;
    private String description;
    private String output;
    private boolean success;
    private long durationMs;
}
