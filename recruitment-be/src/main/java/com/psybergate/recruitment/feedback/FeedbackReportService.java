package com.psybergate.recruitment.feedback;

import com.psybergate.recruitment.feedback.dto.FeedbackReportResponse;

import java.util.UUID;

public interface FeedbackReportService {

    FeedbackReportResponse generate(UUID submissionId, UUID requestedBy);

    FeedbackReportResponse getExisting(UUID submissionId);
}
