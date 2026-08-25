package com.psybergate.recruitment.take.clarify.repository;

import com.psybergate.recruitment.take.clarify.domain.ClarificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClarificationRequestRepository extends JpaRepository<ClarificationRequest, UUID> {

    long countBySubmissionId(UUID submissionId);

    long countBySubmissionIdAndQuestionId(UUID submissionId, UUID questionId);
}
