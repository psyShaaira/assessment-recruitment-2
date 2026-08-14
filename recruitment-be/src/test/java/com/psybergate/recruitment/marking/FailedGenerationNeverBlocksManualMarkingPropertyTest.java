package com.psybergate.recruitment.marking;

import com.psybergate.recruitment.ai.AiAuthenticationException;
import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiRateLimitException;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.ai.AiTimeoutException;
import com.psybergate.recruitment.domain.AssessmentQuestion;
import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.marking.ai.AiMarkingPromptBuilder;
import com.psybergate.recruitment.marking.ai.AiMarkingResponseException;
import com.psybergate.recruitment.marking.ai.AiMarkingServiceImpl;
import com.psybergate.recruitment.marking.dto.AnswerScoreResponse;
import com.psybergate.recruitment.question.domain.TextQuestion;
import com.psybergate.recruitment.repository.AiMarkingSuggestionRepository;
import com.psybergate.recruitment.repository.AnswerScoreRepository;
import com.psybergate.recruitment.repository.AssessmentQuestionRepository;
import com.psybergate.recruitment.repository.AssessmentRepository;
import com.psybergate.recruitment.repository.CandidateAnswerRepository;
import com.psybergate.recruitment.repository.CandidateRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import com.psybergate.recruitment.repository.InvitationRepository;
import com.psybergate.recruitment.repository.QuestionRepository;
import com.psybergate.recruitment.repository.SubmissionFlagRepository;
import com.psybergate.recruitment.repository.SubmissionQuestionSnapshotRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests proving that a failed {@code AiMarkingServiceImpl.generateSuggestion()}
 * call never prevents a subsequent {@code SubmissionServiceImpl.scoreAnswer()} call for the
 * same {@code CandidateAnswer} from succeeding. The two services share no state (each is
 * constructed here with its own independently-mocked repositories), and manual marking uses
 * a completely different code path (no dependency on {@code AiMarkingSuggestionRepository} or
 * {@code AiService}) — so a failure in one structurally cannot affect the other.
 */
class FailedGenerationNeverBlocksManualMarkingPropertyTest {

    // Feature: ai-assisted-marking, Property 13: A failed suggestion generation never blocks manual marking of the same answer
    @Property(tries = 20)
    void failedGenerationNeverBlocksManualMarking(
            @ForAll("generationFailures") FailureMode failureMode,
            @ForAll @IntRange(min = 0, max = 1000) int recruiterScore,
            @ForAll("feedbackTexts") String recruiterFeedback) {

        UUID submissionId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();

        CandidateSubmission submission = new CandidateSubmission();
        submission.setId(submissionId);
        submission.setAssessmentId(assessmentId);

        TextQuestion question = new TextQuestion();
        question.setId(questionId);
        question.setTitle("Explain polymorphism");
        question.setBody("Describe polymorphism with an example.");
        question.setMaxScore(10);

        CandidateAnswer answer = new CandidateAnswer();
        answer.setId(answerId);
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setTextContent("Polymorphism lets objects of different types respond to the same call.");

        // ── Step 1: generateSuggestion() fails ─────────────────────────────────

        AiService aiService = mock(AiService.class);
        AiMarkingPromptBuilder promptBuilder = mock(AiMarkingPromptBuilder.class);
        AiMarkingSuggestionRepository aiMarkingSuggestionRepository = mock(AiMarkingSuggestionRepository.class);
        CandidateAnswerRepository aiAnswerRepository = mock(CandidateAnswerRepository.class);
        CandidateSubmissionRepository aiSubmissionRepository = mock(CandidateSubmissionRepository.class);
        AssessmentQuestionRepository aiAssessmentQuestionRepository = mock(AssessmentQuestionRepository.class);
        QuestionRepository aiQuestionRepository = mock(QuestionRepository.class);

        AiMarkingServiceImpl aiMarkingService = new AiMarkingServiceImpl(
                aiService, promptBuilder, aiMarkingSuggestionRepository, aiAnswerRepository,
                aiSubmissionRepository, aiAssessmentQuestionRepository, aiQuestionRepository);

        AssessmentQuestion aq = new AssessmentQuestion();
        aq.setQuestion(question);
        aq.setDisplayOrder(0);

        when(aiSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(aiAssessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));
        when(aiQuestionRepository.findById(questionId)).thenReturn(Optional.of(question));
        when(aiAnswerRepository.findBySubmissionIdAndQuestionId(submissionId, questionId))
                .thenReturn(Optional.of(answer));
        when(promptBuilder.build(any(), any())).thenReturn("prompt text");

        failureMode.configure(aiService);

        assertThatThrownBy(() -> aiMarkingService.generateSuggestion(submissionId, questionId))
                .isInstanceOf(failureMode.expectedExceptionType());

        // ── Step 2: scoreAnswer() for the same answer still succeeds ───────────

        CandidateSubmissionRepository submissionRepository = mock(CandidateSubmissionRepository.class);
        CandidateAnswerRepository answerRepository = mock(CandidateAnswerRepository.class);
        AnswerScoreRepository scoreRepository = mock(AnswerScoreRepository.class);
        CandidateRepository candidateRepository = mock(CandidateRepository.class);
        AssessmentRepository assessmentRepository = mock(AssessmentRepository.class);
        AssessmentQuestionRepository assessmentQuestionRepository = mock(AssessmentQuestionRepository.class);
        QuestionRepository questionRepository = mock(QuestionRepository.class);
        SubmissionFlagRepository submissionFlagRepository = mock(SubmissionFlagRepository.class);
        InvitationRepository invitationRepository = mock(InvitationRepository.class);
        SubmissionQuestionSnapshotRepository snapshotRepository = mock(SubmissionQuestionSnapshotRepository.class);

        SubmissionServiceImpl submissionService = new SubmissionServiceImpl(
                submissionRepository, answerRepository, scoreRepository, candidateRepository,
                assessmentRepository, assessmentQuestionRepository, questionRepository,
                submissionFlagRepository, invitationRepository, snapshotRepository);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));
        when(scoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnswerScoreResponse response = submissionService.scoreAnswer(
                submissionId, answerId, recruiterScore, recruiterFeedback, markerId);

        assertThat(response.answerId()).isEqualTo(answerId);
        assertThat(response.score()).isEqualTo(recruiterScore);
        assertThat(response.feedback()).isEqualTo(recruiterFeedback);
        assertThat(response.autoMarked()).isFalse();
        assertThat(response.markedBy()).isEqualTo(markerId);
    }

    @Provide
    Arbitrary<String> feedbackTexts() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMaxLength(50).injectNull(0.2);
    }

    @Provide
    Arbitrary<FailureMode> generationFailures() {
        return Arbitraries.of(FailureMode.values());
    }

    /**
     * The 7 distinct ways {@code generateSuggestion()} can fail: the 5 {@code AiService}
     * exception types propagating unchanged, plus the 2 ways an AI response text can be
     * unparseable (missing {@code SCORE:} or missing {@code RATIONALE:}), which surface as
     * {@code AiMarkingResponseException}.
     */
    enum FailureMode {
        AUTHENTICATION {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenThrow(new AiAuthenticationException("auth failed"));
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiAuthenticationException.class;
            }
        },
        COMMUNICATION {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenThrow(new AiCommunicationException("comm failed"));
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiCommunicationException.class;
            }
        },
        TIMEOUT {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenThrow(new AiTimeoutException("timed out"));
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiTimeoutException.class;
            }
        },
        RATE_LIMIT {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenThrow(new AiRateLimitException("rate limited"));
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiRateLimitException.class;
            }
        },
        AI_RESPONSE_ERROR {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenThrow(new AiResponseException("bad response"));
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiResponseException.class;
            }
        },
        MISSING_SCORE {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenReturn("RATIONALE: looks reasonable overall");
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiMarkingResponseException.class;
            }
        },
        MISSING_RATIONALE {
            void configure(AiService aiService) {
                when(aiService.prompt(anyString())).thenReturn("SCORE: 7");
            }
            Class<? extends RuntimeException> expectedExceptionType() {
                return AiMarkingResponseException.class;
            }
        };

        abstract void configure(AiService aiService);

        abstract Class<? extends RuntimeException> expectedExceptionType();
    }
}
