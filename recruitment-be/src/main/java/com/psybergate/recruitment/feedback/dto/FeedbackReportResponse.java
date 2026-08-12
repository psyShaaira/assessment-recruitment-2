package com.psybergate.recruitment.feedback.dto;

import java.time.Instant;
import java.util.UUID;

public record FeedbackReportResponse(
        UUID submissionId,
        FeedbackReportContent content,
        boolean aiGenerated,
        String promptVersion,
        Instant generatedAt
) {}
