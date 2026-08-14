package com.psybergate.recruitment.feedbackemail.repository;

import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackEmailSendLogRepository extends JpaRepository<FeedbackEmailSendLog, UUID> {
    List<FeedbackEmailSendLog> findBySubmissionIdOrderBySentAtDesc(UUID submissionId);
}
