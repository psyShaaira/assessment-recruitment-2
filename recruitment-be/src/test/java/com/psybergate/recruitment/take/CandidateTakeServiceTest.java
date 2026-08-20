package com.psybergate.recruitment.take;

import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.marking.MarkingService;
import com.psybergate.recruitment.repository.*;
import com.psybergate.recruitment.take.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateTakeServiceTest {

    @Mock private AssessmentRepository assessmentRepository;
    @Mock private AssessmentQuestionRepository assessmentQuestionRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private CandidateSubmissionRepository submissionRepository;
    @Mock private CandidateAnswerRepository answerRepository;
    @Mock private AnswerScoreRepository answerScoreRepository;
    @Mock private InvitationRepository invitationRepository;
    @Mock private SubmissionQuestionSnapshotRepository snapshotRepository;
    @Mock private MarkingService markingService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CandidateTakeServiceImpl service;

    private UUID candidateId;
    private UUID assessmentId;
    private UUID submissionId;
    private Assessment assessment;
    private CandidateSubmission submission;
    private McqQuestion mcqQuestion;
    private AssessmentQuestion aq;

    @BeforeEach
    void setUp() {
        candidateId = UUID.randomUUID();
        assessmentId = UUID.randomUUID();
        submissionId = UUID.randomUUID();

        assessment = new Assessment();
        assessment.setId(assessmentId);
        assessment.setTimeLimitMinutes(60);

        submission = new CandidateSubmission();
        submission.setId(submissionId);
        submission.setCandidateId(candidateId);
        submission.setAssessmentId(assessmentId);
        submission.setStatus(SubmissionStatus.IN_PROGRESS);
        submission.setStartedAt(Instant.now().minusSeconds(60));

        mcqQuestion = new McqQuestion();
        mcqQuestion.setId(UUID.randomUUID());

        aq = new AssessmentQuestion();
        aq.setQuestion(mcqQuestion);
        aq.setDisplayOrder(1);
    }

    @Test
    void submitAssessment_oneUnansweredQuestion_createsZeroScoreRecord() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(submission));
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(submissionRepository.save(any())).thenReturn(submission);

        // No questions answered
        when(answerRepository.findQuestionIdsBySubmissionId(submissionId)).thenReturn(Set.of());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));

        UUID answerId = UUID.randomUUID();
        CandidateAnswer savedAnswer = new CandidateAnswer();
        savedAnswer.setId(answerId);
        when(answerRepository.findBySubmissionIdAndQuestionId(submissionId, mcqQuestion.getId()))
                .thenReturn(Optional.empty());
        when(answerRepository.save(any(CandidateAnswer.class))).thenReturn(savedAnswer);
        when(answerScoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());

        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(savedAnswer));

        service.submitAssessment(candidateId, assessmentId, false);

        ArgumentCaptor<AnswerScore> scoreCaptor = ArgumentCaptor.forClass(AnswerScore.class);
        verify(answerScoreRepository).save(scoreCaptor.capture());
        AnswerScore saved = scoreCaptor.getValue();
        assertThat(saved.getScore()).isEqualTo(0);
        assertThat(saved.isAutoMarked()).isTrue();
        assertThat(saved.getFeedback()).isEqualTo("Not answered");
        assertThat(saved.getCandidateAnswerId()).isEqualTo(answerId);
    }

    @Test
    void submitAssessment_allQuestionsAnswered_noZeroScoresCreated() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(submission));
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(submissionRepository.save(any())).thenReturn(submission);

        // All questions answered
        when(answerRepository.findQuestionIdsBySubmissionId(submissionId))
                .thenReturn(Set.of(mcqQuestion.getId()));
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));

        CandidateAnswer existingAnswer = new CandidateAnswer();
        existingAnswer.setId(UUID.randomUUID());
        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(existingAnswer));

        service.submitAssessment(candidateId, assessmentId, false);

        verify(answerScoreRepository, never()).save(any());
    }

    @Test
    void submitAssessment_calledTwice_doesNotCreateDuplicateZeroScore() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        // First call — submission is IN_PROGRESS
        CandidateSubmission inProgress = submission;
        // Second call — submission is already SUBMITTED (isLocked = true)
        CandidateSubmission submitted = new CandidateSubmission();
        submitted.setId(submissionId);
        submitted.setStatus(SubmissionStatus.SUBMITTED);
        submitted.setSubmittedAt(Instant.now());
        submitted.setCandidateId(candidateId);
        submitted.setAssessmentId(assessmentId);
        submitted.setStartedAt(inProgress.getStartedAt());

        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(inProgress))
                .thenReturn(Optional.of(submitted));
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(submissionRepository.save(any())).thenReturn(submitted);

        when(answerRepository.findQuestionIdsBySubmissionId(submissionId)).thenReturn(Set.of());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));

        UUID answerId = UUID.randomUUID();
        CandidateAnswer savedAnswer = new CandidateAnswer();
        savedAnswer.setId(answerId);
        when(answerRepository.findBySubmissionIdAndQuestionId(submissionId, mcqQuestion.getId()))
                .thenReturn(Optional.empty());
        when(answerRepository.save(any(CandidateAnswer.class))).thenReturn(savedAnswer);
        when(answerScoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());

        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of(savedAnswer));

        // First call creates the score
        service.submitAssessment(candidateId, assessmentId, false);
        verify(answerScoreRepository, times(1)).save(any());

        // Second call — submission is locked, short-circuits before zero-scoring
        service.submitAssessment(candidateId, assessmentId, false);
        // Still only 1 save (second call returns early because isLocked)
        verify(answerScoreRepository, times(1)).save(any());
    }

    // ── loadAssessment() ──────────────────────────────────────────────────────

    @Test
    void loadAssessment_assessmentNotFound_throws404() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadAssessment(candidateId, assessmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void loadAssessment_submissionAlreadySubmitted_throws409() {
        CandidateSubmission locked = new CandidateSubmission();
        locked.setId(submissionId);
        locked.setStatus(SubmissionStatus.SUBMITTED);

        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.loadAssessment(candidateId, assessmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void loadAssessment_noSubmission_noInvitation_throws403() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadAssessment(candidateId, assessmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void loadAssessment_noSubmission_cancelledInvitation_throws403() {
        CandidateInvitation cancelled = new CandidateInvitation();
        cancelled.setId(UUID.randomUUID());
        cancelled.setStatus(InvitationStatus.CANCELLED);

        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.of(cancelled));

        // kills mutation that removes the CANCELLED check
        assertThatThrownBy(() -> service.loadAssessment(candidateId, assessmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void loadAssessment_noSubmission_validInvitation_createsNewSubmission() {
        CandidateInvitation invitation = new CandidateInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setStatus(InvitationStatus.SENT);

        CandidateSubmission newSub = new CandidateSubmission();
        newSub.setId(submissionId);
        newSub.setCandidateId(candidateId);
        newSub.setAssessmentId(assessmentId);
        newSub.setStatus(SubmissionStatus.IN_PROGRESS);
        newSub.setStartedAt(Instant.now());

        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.of(invitation));
        when(submissionRepository.save(any())).thenReturn(newSub);
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of());
        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

        service.loadAssessment(candidateId, assessmentId);

        // Verify a new submission was saved with the correct candidateId and assessmentId
        verify(submissionRepository).save(argThat(s ->
                candidateId.equals(s.getCandidateId()) &&
                assessmentId.equals(s.getAssessmentId()) &&
                invitation.getId().equals(s.getInvitationId())));
    }

    // ── submitAssessment() explicit status assertions ─────────────────────────

    @Test
    void submitAssessment_manualSubmit_setsSubmittedStatus() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(submission));
        when(submissionRepository.save(any())).thenReturn(submission);
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(answerRepository.findQuestionIdsBySubmissionId(submissionId)).thenReturn(Set.of());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of());
        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

        service.submitAssessment(candidateId, assessmentId, false);

        // Verify SUBMITTED (not AUTO_SUBMITTED) status saved
        verify(submissionRepository).save(argThat(s -> s.getStatus() == SubmissionStatus.SUBMITTED));
    }

    @Test
    void submitAssessment_autoSubmit_setsAutoSubmittedStatus() {
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(submission));
        when(submissionRepository.save(any())).thenReturn(submission);
        when(invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId))
                .thenReturn(Optional.empty());
        when(answerRepository.findQuestionIdsBySubmissionId(submissionId)).thenReturn(Set.of());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of());
        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(List.of());

        service.submitAssessment(candidateId, assessmentId, true);

        // Kills mutation: `autoSubmitted ? AUTO_SUBMITTED : SUBMITTED`
        verify(submissionRepository).save(argThat(s -> s.getStatus() == SubmissionStatus.AUTO_SUBMITTED));
    }

    // ── saveAnswers() ─────────────────────────────────────────────────────────

    @Test
    void saveAnswers_deadlineExpired_throws409() {
        // submission started 2 minutes ago, time limit is 1 minute
        CandidateSubmission expiredSubmission = new CandidateSubmission();
        expiredSubmission.setId(submissionId);
        expiredSubmission.setCandidateId(candidateId);
        expiredSubmission.setAssessmentId(assessmentId);
        expiredSubmission.setStatus(SubmissionStatus.IN_PROGRESS);
        expiredSubmission.setStartedAt(Instant.now().minusSeconds(120));

        Assessment shortAssessment = new Assessment();
        shortAssessment.setId(assessmentId);
        shortAssessment.setTimeLimitMinutes(1);

        when(submissionRepository.findByCandidateIdAndAssessmentId(candidateId, assessmentId))
                .thenReturn(Optional.of(expiredSubmission));
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(shortAssessment));

        SaveAnswersRequest request = new SaveAnswersRequest(
                List.of(new AnswerInput(mcqQuestion.getId(), null, "some answer")));

        assertThatThrownBy(() -> service.saveAnswers(candidateId, assessmentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
