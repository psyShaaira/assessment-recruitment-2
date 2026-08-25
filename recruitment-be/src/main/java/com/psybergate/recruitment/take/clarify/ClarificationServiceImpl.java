package com.psybergate.recruitment.take.clarify;

import com.psybergate.recruitment.ai.AiAuthenticationException;
import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiRateLimitException;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.ai.AiTimeoutException;
import com.psybergate.recruitment.take.CandidateTakeService;
import com.psybergate.recruitment.take.CandidateTakeService.ClarificationTarget;
import com.psybergate.recruitment.take.clarify.domain.ClarificationRequest;
import com.psybergate.recruitment.take.clarify.dto.ClarificationRequestDto;
import com.psybergate.recruitment.take.clarify.dto.ClarificationResponse;
import com.psybergate.recruitment.take.clarify.repository.ClarificationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClarificationServiceImpl implements ClarificationService {

    private final CandidateTakeService takeService;
    private final ClarificationRequestRepository requestRepository;
    private final ClarificationPromptBuilder promptBuilder;
    private final ClarificationProperties properties;
    private final AiService aiService;

    @Override
    @Transactional
    public ClarificationResponse clarify(UUID candidateId, UUID assessmentId, ClarificationRequestDto request) {
        // 1. Resolve + guard (active/unlocked/in-deadline submission, question in scope).
        //    Throws 404/409/403 — surfaced by GlobalExceptionHandler.
        ClarificationTarget target = takeService.resolveQuestionForClarification(
                candidateId, assessmentId, request.questionId());
        UUID submissionId = target.submissionId();
        UUID questionId = request.questionId();

        // 2. Compute current usage and remaining quota.
        long usedForQuestion = requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId);
        long usedForAssessment = requestRepository.countBySubmissionId(submissionId);
        int remainingForQuestion = remaining(properties.maxPerQuestion(), usedForQuestion);
        int remainingForAssessment = remaining(properties.maxPerAssessment(), usedForAssessment);

        // 3. Feature switch — degrade without calling AI or consuming quota.
        if (!properties.enabled()) {
            return ClarificationResponse.degraded(remainingForQuestion, remainingForAssessment);
        }

        // 4. Rate limits — reject before any AI call. No row is persisted for rejected requests.
        if (usedForQuestion >= properties.maxPerQuestion()) {
            throw new ClarificationRateLimitException("per-question");
        }
        if (usedForAssessment >= properties.maxPerAssessment()) {
            throw new ClarificationRateLimitException("per-assessment");
        }

        // 5. Build the guarded prompt from the sanitized question view.
        String prompt = promptBuilder.build(target.question(), request.candidateNote());

        // 6. Call AI — degrade gracefully on any provider failure (no persistence, no quota consumed).
        String clarification;
        try {
            clarification = aiService.prompt(prompt);
        } catch (AiAuthenticationException | AiRateLimitException | AiCommunicationException
                 | AiTimeoutException | AiResponseException e) {
            log.warn("Clarification AI unavailable for submission {}: {}", submissionId, e.getMessage());
            return ClarificationResponse.degraded(remainingForQuestion, remainingForAssessment);
        }

        // 7. Persist the successful request/response for audit.
        ClarificationRequest record = new ClarificationRequest();
        record.setSubmissionId(submissionId);
        record.setQuestionId(questionId);
        record.setCandidateId(candidateId);
        record.setCandidateNote(request.candidateNote());
        record.setClarificationResponse(clarification);
        record.setPromptVersion(ClarificationPromptBuilder.PROMPT_VERSION);
        requestRepository.save(record);

        // 8. Return with quota decremented to reflect this successful request.
        return new ClarificationResponse(
                clarification,
                remainingForQuestion - 1,
                remainingForAssessment - 1,
                false);
    }

    private int remaining(int max, long used) {
        return (int) Math.max(0, max - used);
    }
}
