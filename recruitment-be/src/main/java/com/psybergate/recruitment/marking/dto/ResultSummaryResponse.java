package com.psybergate.recruitment.marking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.psybergate.recruitment.flag.domain.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResultSummaryResponse(
        UUID submissionId,
        String candidateName,
        String assessmentTitle,
        Instant submittedAt,
        int totalScore,
        int maxScore,
        int answeredCount,
        String markingStatus,
        List<ResultQuestionDto> questions,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        RiskLevel aiRiskLevel
) {}
