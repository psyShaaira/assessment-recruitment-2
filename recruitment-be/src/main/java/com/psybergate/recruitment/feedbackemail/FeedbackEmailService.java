package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendLogDto;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendResponse;

import java.util.List;
import java.util.UUID;

public interface FeedbackEmailService {

    FeedbackEmailSendResponse sendFeedbackEmail(UUID submissionId, UUID sentBy);

    List<FeedbackEmailSendLogDto> getSendHistory(UUID submissionId);
}
