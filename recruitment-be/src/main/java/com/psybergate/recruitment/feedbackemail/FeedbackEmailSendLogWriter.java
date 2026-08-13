package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;
import com.psybergate.recruitment.feedbackemail.repository.FeedbackEmailSendLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists the {@code FAILED} {@link FeedbackEmailSendLog} row on its own transaction so the
 * row is durably committed even though {@code FeedbackEmailServiceImpl#sendFeedbackEmail} goes
 * on to rethrow as a 502 (Req 2.9, 2.10, 6.1, 6.2). This lives on a separate Spring-managed bean
 * — rather than a same-class private method on {@code FeedbackEmailServiceImpl} — because
 * {@code REQUIRES_NEW} is only honored when the call passes through the Spring AOP proxy; a
 * same-class self-invocation would bypass the proxy and silently fall back to the enclosing
 * transaction (or no transaction at all).
 */
@Component
@RequiredArgsConstructor
class FeedbackEmailSendLogWriter {

    private final FeedbackEmailSendLogRepository feedbackEmailSendLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FeedbackEmailSendLog saveFailure(UUID submissionId, UUID sentBy, String failureReason) {
        FeedbackEmailSendLog log = new FeedbackEmailSendLog();
        log.setSubmissionId(submissionId);
        log.setSentBy(sentBy);
        log.setStatus(FeedbackEmailSendStatus.FAILED);
        log.setFailureReason(failureReason);
        return feedbackEmailSendLogRepository.save(log);
    }
}
