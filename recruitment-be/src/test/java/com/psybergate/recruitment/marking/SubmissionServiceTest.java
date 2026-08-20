package com.psybergate.recruitment.marking;

import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.marking.dto.AnswerScoreResponse;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import com.psybergate.recruitment.question.domain.TextQuestion;
import com.psybergate.recruitment.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock private CandidateSubmissionRepository submissionRepository;
    @Mock private CandidateAnswerRepository answerRepository;
    @Mock private AnswerScoreRepository scoreRepository;
    @Mock private CandidateRepository candidateRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private AssessmentQuestionRepository assessmentQuestionRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private SubmissionFlagRepository submissionFlagRepository;
    @Mock private InvitationRepository invitationRepository;
    @Mock private com.psybergate.recruitment.repository.SubmissionQuestionSnapshotRepository snapshotRepository;
    @Mock private com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository flaggingRiskAssessmentRepository;

    @InjectMocks
    private SubmissionServiceImpl service;

    private UUID submissionId;
    private UUID assessmentId;
    private UUID candidateId;
    private UUID markerId;
    private CandidateSubmission submission;
    private Assessment assessment;
    private Candidate candidate;

    @BeforeEach
    void setUp() {
        submissionId = UUID.randomUUID();
        assessmentId = UUID.randomUUID();
        candidateId = UUID.randomUUID();
        markerId = UUID.randomUUID();

        submission = new CandidateSubmission();
        submission.setId(submissionId);
        submission.setCandidateId(candidateId);
        submission.setAssessmentId(assessmentId);
        submission.setStatus(SubmissionStatus.SUBMITTED);

        assessment = new Assessment();
        assessment.setId(assessmentId);
        assessment.setTitle("Java Test");

        candidate = new Candidate();
        candidate.setId(candidateId);
        candidate.setFirstName("Alice");
        candidate.setLastName("Smith");
    }

    // ── scoreAnswer() ─────────────────────────────────────────────────────────

    @Test
    void scoreAnswer_submissionNotFound_throws404() {
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scoreAnswer(submissionId, UUID.randomUUID(), 5, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scoreAnswer_answerNotFound_throws404() {
        UUID answerId = UUID.randomUUID();
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scoreAnswer(submissionId, answerId, 5, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scoreAnswer_answerDoesNotBelongToSubmission_throws404() {
        UUID answerId = UUID.randomUUID();
        UUID differentSubmissionId = UUID.randomUUID();

        CandidateAnswer answer = new CandidateAnswer();
        answer.setId(answerId);
        answer.setSubmissionId(differentSubmissionId);  // different submission!

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));

        // kills the `!answer.getSubmissionId().equals(submissionId)` boundary mutant
        assertThatThrownBy(() -> service.scoreAnswer(submissionId, answerId, 5, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scoreAnswer_negativeScore_throws400() {
        UUID answerId = UUID.randomUUID();
        CandidateAnswer answer = buildAnswer(answerId, submissionId);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));

        assertThatThrownBy(() -> service.scoreAnswer(submissionId, answerId, -1, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void scoreAnswer_createsNewScore_returnsAllSixFields() {
        UUID answerId = UUID.randomUUID();
        CandidateAnswer answer = buildAnswer(answerId, submissionId);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));
        when(scoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());

        AnswerScore saved = new AnswerScore();
        saved.setScore(7);
        saved.setFeedback("Good");
        saved.setAutoMarked(false);
        saved.setMarkedBy(markerId);
        saved.setMarkedAt(Instant.now());
        when(scoreRepository.save(any())).thenReturn(saved);

        AnswerScoreResponse response = service.scoreAnswer(submissionId, answerId, 7, "Good", markerId);

        assertThat(response.answerId()).isEqualTo(answerId);
        assertThat(response.score()).isEqualTo(7);
        assertThat(response.feedback()).isEqualTo("Good");
        assertThat(response.autoMarked()).isFalse();
        assertThat(response.markedBy()).isEqualTo(markerId);
        assertThat(response.markedAt()).isNotNull();
    }

    @Test
    void scoreAnswer_existingScore_updatesAndSetsAutoMarkedFalse() {
        UUID answerId = UUID.randomUUID();
        CandidateAnswer answer = buildAnswer(answerId, submissionId);

        AnswerScore existing = new AnswerScore();
        existing.setScore(3);
        existing.setAutoMarked(true);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(answer));
        when(scoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.of(existing));
        when(scoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.scoreAnswer(submissionId, answerId, 9, "Updated", markerId);

        verify(scoreRepository).save(argThat(s ->
                s.getScore() == 9 &&
                !s.isAutoMarked() &&
                markerId.equals(s.getMarkedBy())));
    }

    // ── scoreByQuestionId() ───────────────────────────────────────────────────

    @Test
    void scoreByQuestionId_submissionNotFound_throws404() {
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scoreByQuestionId(submissionId, UUID.randomUUID(), 5, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scoreByQuestionId_negativeScore_throws400() {
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));

        assertThatThrownBy(() -> service.scoreByQuestionId(submissionId, UUID.randomUUID(), -1, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void scoreByQuestionId_questionNotInAssessment_throws404() {
        UUID unknownQuestionId = UUID.randomUUID();
        TextQuestion otherQ = new TextQuestion();
        otherQ.setId(UUID.randomUUID());  // different ID

        AssessmentQuestion aq = buildAQ(otherQ);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));

        assertThatThrownBy(() -> service.scoreByQuestionId(submissionId, unknownQuestionId, 5, null, markerId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scoreByQuestionId_questionFoundAsGroupSubQuestion_saves() {
        // Tests the `instanceof GroupQuestion` branch — kills mutation that removes it
        UUID subQuestionId = UUID.randomUUID();
        TextQuestion subQ = new TextQuestion();
        subQ.setId(subQuestionId);
        subQ.setTitle("Sub Q");
        subQ.setBody("body");
        subQ.setMaxScore(4);

        GroupQuestionMember member = new GroupQuestionMember();
        member.setQuestion(subQ);
        member.setDisplayOrder(0);

        GroupQuestion groupQ = new GroupQuestion();
        groupQ.setId(UUID.randomUUID());
        groupQ.setTitle("Group");
        groupQ.setBody("body");
        groupQ.setMaxScore(10);
        groupQ.setMembers(List.of(member));

        AssessmentQuestion aq = buildAQ(groupQ);

        UUID answerId = UUID.randomUUID();
        CandidateAnswer savedAnswer = buildAnswer(answerId, submissionId);
        savedAnswer.setQuestionId(subQuestionId);

        AnswerScore savedScore = new AnswerScore();
        savedScore.setScore(4);
        savedScore.setAutoMarked(false);
        savedScore.setMarkedBy(markerId);
        savedScore.setMarkedAt(Instant.now());

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(aq));
        when(answerRepository.findBySubmissionIdAndQuestionId(submissionId, subQuestionId))
                .thenReturn(Optional.of(savedAnswer));
        when(scoreRepository.findByCandidateAnswerId(answerId)).thenReturn(Optional.empty());
        when(scoreRepository.save(any())).thenReturn(savedScore);

        AnswerScoreResponse response = service.scoreByQuestionId(submissionId, subQuestionId, 4, null, markerId);

        assertThat(response.score()).isEqualTo(4);
        verify(scoreRepository).save(argThat(s ->
                s.getScore() == 4 &&
                !s.isAutoMarked() &&
                markerId.equals(s.getMarkedBy())));
    }

    // ── getResult() ───────────────────────────────────────────────────────────

    @Test
    void getResult_submissionNotFound_throws404() {
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult(submissionId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getResult_regularQuestion_answeredAndScored_returnsFullyMarked() {
        UUID questionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();

        TextQuestion question = new TextQuestion();
        question.setId(questionId);
        question.setTitle("Q1");
        question.setBody("Describe X");
        question.setMaxScore(10);

        AssessmentQuestion aq = buildAQ(question);

        CandidateAnswer answer = buildAnswer(answerId, submissionId);
        answer.setQuestionId(questionId);
        answer.setTextContent("My answer");

        AnswerScore score = new AnswerScore();
        score.setCandidateAnswerId(answerId);
        score.setScore(8);
        score.setAutoMarked(false);
        score.setMarkedBy(markerId);
        score.setMarkedAt(Instant.now());

        setupGetResultMocks(List.of(aq), List.of(answer), List.of(score));

        ResultSummaryResponse result = service.getResult(submissionId);

        assertThat(result.totalScore()).isEqualTo(8);
        assertThat(result.answeredCount()).isEqualTo(1);
        assertThat(result.markingStatus()).isEqualTo("FULLY_MARKED");
        assertThat(result.candidateName()).isEqualTo("Alice Smith");
        assertThat(result.assessmentTitle()).isEqualTo("Java Test");
        assertThat(result.questions()).hasSize(1);
    }

    @Test
    void getResult_regularQuestion_answeredButNoScore_pendingReview() {
        UUID questionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();

        TextQuestion question = new TextQuestion();
        question.setId(questionId);
        question.setTitle("Q1");
        question.setBody("Describe X");
        question.setMaxScore(10);

        AssessmentQuestion aq = buildAQ(question);

        CandidateAnswer answer = buildAnswer(answerId, submissionId);
        answer.setQuestionId(questionId);
        answer.setTextContent("Some answer");

        // no score returned
        setupGetResultMocks(List.of(aq), List.of(answer), List.of());

        ResultSummaryResponse result = service.getResult(submissionId);

        assertThat(result.markingStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(result.totalScore()).isEqualTo(0);
    }

    @Test
    void getResult_regularQuestion_notAnswered_pendingReview() {
        UUID questionId = UUID.randomUUID();

        TextQuestion question = new TextQuestion();
        question.setId(questionId);
        question.setTitle("Q1");
        question.setBody("Describe X");
        question.setMaxScore(10);

        AssessmentQuestion aq = buildAQ(question);

        // no answers
        setupGetResultMocks(List.of(aq), List.of(), List.of());

        ResultSummaryResponse result = service.getResult(submissionId);

        assertThat(result.markingStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(result.answeredCount()).isEqualTo(0);
    }

    @Test
    void getResult_groupQuestion_expandsToSubDtosAndAggregatesScore() {
        // Tests the `instanceof GroupQuestion` branch — kills mutation removing the expansion
        UUID subQuestionId = UUID.randomUUID();
        UUID subAnswerId = UUID.randomUUID();

        TextQuestion subQ = new TextQuestion();
        subQ.setId(subQuestionId);
        subQ.setTitle("Sub Q");
        subQ.setBody("Sub body");
        subQ.setMaxScore(5);

        GroupQuestionMember member = new GroupQuestionMember();
        member.setQuestion(subQ);
        member.setDisplayOrder(0);

        GroupQuestion groupQ = new GroupQuestion();
        groupQ.setId(UUID.randomUUID());
        groupQ.setTitle("Group Q");
        groupQ.setBody("preamble");
        groupQ.setMaxScore(5);
        groupQ.setMembers(List.of(member));

        AssessmentQuestion aq = buildAQ(groupQ);

        CandidateAnswer subAnswer = buildAnswer(subAnswerId, submissionId);
        subAnswer.setQuestionId(subQuestionId);
        subAnswer.setTextContent("Sub answer");

        AnswerScore subScore = new AnswerScore();
        subScore.setCandidateAnswerId(subAnswerId);
        subScore.setScore(4);
        subScore.setAutoMarked(true);
        subScore.setMarkedAt(Instant.now());

        setupGetResultMocks(List.of(aq), List.of(subAnswer), List.of(subScore));

        ResultSummaryResponse result = service.getResult(submissionId);

        // Group is expanded: one top-level GROUP entry with one sub-question
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).subQuestions()).hasSize(1);
        assertThat(result.questions().get(0).subQuestions().get(0).score()).isEqualTo(4);
        assertThat(result.totalScore()).isEqualTo(4);
        assertThat(result.answeredCount()).isEqualTo(1);
        assertThat(result.markingStatus()).isEqualTo("FULLY_MARKED");
    }

    @Test
    void getResult_questionBothStandaloneAndGroupMember_countsScoreOnce() {
        // Regression test: a question appearing both as its own standalone
        // AssessmentQuestion AND as a member of a GROUP question on the same
        // assessment must not have its score/maxScore counted twice.
        UUID sharedQuestionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();

        TextQuestion sharedQ = new TextQuestion();
        sharedQ.setId(sharedQuestionId);
        sharedQ.setTitle("Shared Q");
        sharedQ.setBody("body");
        sharedQ.setMaxScore(5);

        GroupQuestionMember member = new GroupQuestionMember();
        member.setQuestion(sharedQ);
        member.setDisplayOrder(0);

        GroupQuestion groupQ = new GroupQuestion();
        groupQ.setId(UUID.randomUUID());
        groupQ.setTitle("Group Q");
        groupQ.setBody("preamble");
        groupQ.setMaxScore(5);
        groupQ.setMembers(List.of(member));

        AssessmentQuestion standaloneAq = buildAQ(sharedQ);
        AssessmentQuestion groupAq = buildAQ(groupQ);

        CandidateAnswer answer = buildAnswer(answerId, submissionId);
        answer.setQuestionId(sharedQuestionId);
        answer.setTextContent("Answer text");

        AnswerScore score = new AnswerScore();
        score.setCandidateAnswerId(answerId);
        score.setScore(4);
        score.setAutoMarked(false);
        score.setMarkedAt(Instant.now());

        setupGetResultMocks(List.of(standaloneAq, groupAq), List.of(answer), List.of(score));

        ResultSummaryResponse result = service.getResult(submissionId);

        // Only the GROUP entry is rendered — the standalone duplicate is skipped
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).subQuestions()).hasSize(1);
        assertThat(result.totalScore()).isEqualTo(4);
        assertThat(result.maxScore()).isEqualTo(5);
        assertThat(result.answeredCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CandidateAnswer buildAnswer(UUID answerId, UUID submissionId) {
        CandidateAnswer a = new CandidateAnswer();
        a.setId(answerId);
        a.setSubmissionId(submissionId);
        return a;
    }

    private AssessmentQuestion buildAQ(Question question) {
        AssessmentQuestion aq = new AssessmentQuestion();
        aq.setQuestion(question);
        aq.setDisplayOrder(1);
        return aq;
    }

    private void setupGetResultMocks(List<AssessmentQuestion> aqList,
                                      List<CandidateAnswer> answers,
                                      List<AnswerScore> scores) {
        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(candidateRepository.findById(candidateId)).thenReturn(Optional.of(candidate));
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(aqList);
        when(answerRepository.findBySubmissionId(submissionId)).thenReturn(answers);

        Set<UUID> answerIds = new HashSet<>();
        for (CandidateAnswer a : answers) answerIds.add(a.getId());
        if (!answerIds.isEmpty()) {
            when(scoreRepository.findByCandidateAnswerIdIn(answerIds)).thenReturn(scores);
        } else {
            lenient().when(scoreRepository.findByCandidateAnswerIdIn(any())).thenReturn(scores);
        }
    }
}
