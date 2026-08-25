package com.psybergate.recruitment.take;

import com.psybergate.recruitment.take.dto.AssessmentTakeResponse;
import com.psybergate.recruitment.take.dto.SaveAnswersRequest;
import com.psybergate.recruitment.take.dto.SaveAnswersResponse;
import com.psybergate.recruitment.take.dto.SubmitResponse;
import com.psybergate.recruitment.take.dto.TakeQuestionDto;

import java.util.UUID;

public interface CandidateTakeService {

    AssessmentTakeResponse loadAssessment(UUID candidateId, UUID assessmentId);

    SaveAnswersResponse saveAnswers(UUID candidateId, UUID assessmentId, SaveAnswersRequest request);

    SubmitResponse submitAssessment(UUID candidateId, UUID assessmentId, boolean autoSubmitted);

    /**
     * Resolves a single question for clarification during an active take.
     * <p>
     * Enforces the take guards (active, unlocked, in-deadline submission) and validates
     * that {@code questionId} belongs to the candidate's snapshot-aware resolved question
     * set (including GROUP sub-questions). Returns the sanitized {@link TakeQuestionDto}
     * (never carrying option correctness).
     *
     * @throws org.springframework.web.server.ResponseStatusException 404 if no active
     *         submission, 409 if locked or past deadline, 403 if the question is out of scope
     * @return the active submission id and the sanitized question view
     */
    ClarificationTarget resolveQuestionForClarification(UUID candidateId, UUID assessmentId, UUID questionId);

    /** Result of {@link #resolveQuestionForClarification}: the active submission id plus the sanitized question. */
    record ClarificationTarget(UUID submissionId, TakeQuestionDto question) {}
}
