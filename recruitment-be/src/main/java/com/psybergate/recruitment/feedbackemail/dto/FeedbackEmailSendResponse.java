package com.psybergate.recruitment.feedbackemail.dto;

import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;

import java.time.Instant;
import java.util.UUID;

public record FeedbackEmailSendResponse(
        UUID submissionId,
        FeedbackEmailSendStatus status,
        Instant sentAt
) {
}
