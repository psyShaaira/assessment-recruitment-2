package com.psybergate.recruitment.feedbackemail.dto;

import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;

import java.time.Instant;
import java.util.UUID;

public record FeedbackEmailSendLogDto(
        Instant sentAt,
        FeedbackEmailSendStatus status,
        UUID sentBy,
        String failureReason
) {
    public static FeedbackEmailSendLogDto from(FeedbackEmailSendLog log) {
        return new FeedbackEmailSendLogDto(log.getSentAt(), log.getStatus(), log.getSentBy(), log.getFailureReason());
    }
}
