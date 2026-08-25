package com.psybergate.recruitment.take.clarify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clarification")
public record ClarificationProperties(
        boolean enabled,
        int maxPerQuestion,
        int maxPerAssessment
) {
    public ClarificationProperties {
        if (maxPerQuestion <= 0) maxPerQuestion = 3;
        if (maxPerAssessment <= 0) maxPerAssessment = 15;
    }
}
