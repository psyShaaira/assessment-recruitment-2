package com.psybergate.recruitment.flag.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flagging")
public record AiFlaggingProperties(
        boolean aiEnabled,
        double highThreshold,
        double mediumThreshold,
        double similarityThreshold,
        int timeoutSeconds
) {
    public AiFlaggingProperties {
        if (highThreshold <= 0) highThreshold = 0.8;
        if (mediumThreshold <= 0) mediumThreshold = 0.5;
        if (similarityThreshold <= 0) similarityThreshold = 0.8;
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
