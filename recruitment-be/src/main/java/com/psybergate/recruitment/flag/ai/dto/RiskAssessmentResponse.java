package com.psybergate.recruitment.flag.ai.dto;

import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response DTO for a submission's AI risk assessment.
 * Maps from {@link com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment}.
 */
public record RiskAssessmentResponse(
    UUID submissionId,
    RiskLevel risk,
    List<FlagReason> reasons,
    String rationale,
    double confidence,
    Instant analyzedAt,
    String promptVersion,
    boolean flagCreated
) {}
