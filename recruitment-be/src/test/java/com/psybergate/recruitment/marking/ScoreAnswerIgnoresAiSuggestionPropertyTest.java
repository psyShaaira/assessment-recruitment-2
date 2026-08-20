package com.psybergate.recruitment.marking;

import com.psybergate.recruitment.domain.AiMarkingSuggestion;
import com.psybergate.recruitment.domain.AnswerScore;
import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.marking.dto.AnswerScoreResponse;
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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests proving that {@link SubmissionServiceImpl#scoreAnswer} is completely
 * unaffected by any AI-generated marking suggestion. {@code SubmissionServiceImpl} has no
 * dependency on {@code AiMarkingSuggestionRepository} at all, so an {@link AiMarkingSuggestion}
 * (or its absence) is generated purely as "prior state" context in these tests and is never
 * wired into any repository consulted by {@code scoreAnswer()} — its content structurally
 * cannot influence the persisted {@code AnswerScore}.
 */
class ScoreAnswerIgnoresAiSuggestionPropertyTest {

    // Feature: ai-assisted-marking, Property 11: Recording an Answer_Score is never influenced by an existing AI suggestion
    @Property(tries = 20)
    void recordingScoreIgnoresSuggestion(
            @ForAll @IntRange(min = 0, max = 1000) int recruiterScore,
            @ForAll("feedbackTexts") String recruiterFeedback,
            @ForAll("priorSuggestions") Optional<AiMarkingSuggestion> priorSuggestion) {

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

        SubmissionServiceImpl service = new SubmissionServiceImpl(
                submissionRepository, answerRepository, scoreRepository, candidateRepository,
                assessmentRepository, assessmentQuestionRepository, questionRepository,
                submissionFlagRepository, invitationRepository, snapshotRepository,
                mock(com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository.class));

        UUID submissionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();

        CandidateSubmission submission = new CandidateSubmission();
        submission.setId(submissionId);

        CandidateAnswer answer = new CandidateAnswer();
        answer.setId(answerId);
        answer.setSubmissionId(submissionId);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));
        when(scoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // `priorSuggestion` intentionally has no mock wiring: no repository or collaborator
        // consulted by scoreAnswer() can observe it, regardless of whether it is present,
        // absent, or what score/rationale it carries.

        AnswerScoreResponse response = service.scoreAnswer(
                submissionId, answerId, recruiterScore, recruiterFeedback, markerId);

        assertThat(response.score()).isEqualTo(recruiterScore);
        assertThat(response.feedback()).isEqualTo(recruiterFeedback);

        verify(scoreRepository).save(argThatMatches(recruiterScore, recruiterFeedback));
    }

    private static AnswerScore argThatMatches(int expectedScore, String expectedFeedback) {
        return org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getScore() == expectedScore
                        && Objects.equals(saved.getFeedback(), expectedFeedback));
    }

    @Provide
    Arbitrary<String> feedbackTexts() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMaxLength(50).injectNull(0.2);
    }

    @Provide
    Arbitrary<Optional<AiMarkingSuggestion>> priorSuggestions() {
        Arbitrary<AiMarkingSuggestion> suggestionArbitrary = Combinators.combine(
                Arbitraries.integers().between(0, 1000),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(200)
        ).as((score, rationale) -> {
            AiMarkingSuggestion suggestion = new AiMarkingSuggestion();
            suggestion.setId(UUID.randomUUID());
            suggestion.setCandidateAnswerId(UUID.randomUUID());
            suggestion.setScore(score);
            suggestion.setRationale(rationale);
            suggestion.setGeneratedAt(Instant.now());
            return suggestion;
        });

        Arbitrary<Optional<AiMarkingSuggestion>> presentArbitrary = suggestionArbitrary.map(Optional::of);
        Arbitrary<Optional<AiMarkingSuggestion>> absentArbitrary = Arbitraries.just(Optional.empty());

        return Arbitraries.oneOf(presentArbitrary, absentArbitrary);
    }
}
