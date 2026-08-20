package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.flag.ai.dto.RiskAssessmentResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for AI-powered submission integrity analysis.
 */
public interface AiFlaggingService {

    /**
     * Performs full integrity analysis on a completed submission:
     * AI content/timing analysis + cross-submission similarity.
     * Persists risk assessment and auto-flags if warranted.
     */
    void analyze(UUID submissionId);

    /**
     * Retrieves the stored risk assessment for a submission (recruiter view).
     */
    Optional<RiskAssessmentResponse> getRiskAssessment(UUID submissionId);
}
