package com.psybergate.recruitment.flag.ai.dto;

import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.RiskLevel;

import java.util.List;

public record AiFlaggingResult(
    RiskLevel risk,
    List<FlagReason> reasons,
    String rationale,
    double confidence
) {
    public static final AiFlaggingResult LOW_DEFAULT = new AiFlaggingResult(
        RiskLevel.LOW, List.of(), "No issues detected", 0.0
    );
}
