package com.psybergate.recruitment.feedback.repository;

import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionFeedbackReportRepository extends JpaRepository<SubmissionFeedbackReport, UUID> {

    Optional<SubmissionFeedbackReport> findBySubmissionId(UUID submissionId);
}
