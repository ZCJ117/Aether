package cn.zcj.aether.domain.agent.service.curation;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Getter
@Component
public class SignalScorer {
    @Value("${aether.context.scoring.recency-weight:0.3}")
    private double recencyWeight;
    @Value("${aether.context.scoring.semantic-weight:0.5}")
    private double semanticWeight;
    @Value("${aether.context.scoring.importance-weight:0.2}")
    private double importanceWeight;
    @Value("${aether.context.scoring.decay-half-life-hours:24}")
    private double decayHalfLifeHours;

    public double score(Instant lastAccessedAt, double semanticSimilarity, double importance) {
        double recency = computeRecency(lastAccessedAt);
        return recencyWeight * recency + semanticWeight * semanticSimilarity + importanceWeight * importance;
    }

    private double computeRecency(Instant lastAccessedAt) {
        if (lastAccessedAt == null) {
            return 0.1;
        }
        double hoursSince = Duration.between(lastAccessedAt, Instant.now()).toSeconds() / 3600.0;
        double halfLife = decayHalfLifeHours / Math.log(2);
        return Math.exp(-hoursSince / halfLife);
    }
}
