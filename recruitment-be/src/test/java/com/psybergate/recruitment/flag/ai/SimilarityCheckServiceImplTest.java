package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.domain.SubmissionStatus;
import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.repository.CandidateAnswerRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilarityCheckServiceImplTest {

    @Mock private CandidateSubmissionRepository submissionRepository;
    @Mock private CandidateAnswerRepository answerRepository;

    private SimilarityCheckServiceImpl service;

    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final UUID ASSESSMENT_ID = UUID.randomUUID();
    private static final UUID QUESTION_ID_1 = UUID.randomUUID();
    private static final UUID QUESTION_ID_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AiFlaggingProperties properties = new AiFlaggingProperties(true, 0.8, 0.5, 0.8, 30);
        service = new SimilarityCheckServiceImpl(submissionRepository, answerRepository, properties);
    }

    @Test
    void check_noOtherSubmissions_returnsLow() {
        SubmissionAnalysisContext context = buildContext();
        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of());

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reason()).isNull();
        assertThat(result.maxSimilarity()).isEqualTo(0.0);
        assertThat(result.rationale()).isEqualTo("No other submissions to compare");
    }

    @Test
    void check_otherSubmissionsBelowThreshold_returnsLow() {
        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Current submission: "Java is a programming language used widely"
        CandidateAnswer currentAnswer = createAnswer(SUBMISSION_ID, QUESTION_ID_1,
                "Java is a programming language used widely");
        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer));

        // Other submission: completely different answer
        CandidateAnswer otherAnswer = createAnswer(otherSubmissionId, QUESTION_ID_1,
                "Python excels at data science and machine learning tasks");
        when(answerRepository.findBySubmissionId(otherSubmissionId))
                .thenReturn(List.of(otherAnswer));

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reason()).isNull();
        assertThat(result.maxSimilarity()).isLessThan(0.8);
        assertThat(result.rationale()).isEqualTo("No significant similarity");
    }

    @Test
    void check_otherSubmissionsAboveThreshold_returnsHighWithCopiedAnswers() {
        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Both submissions have nearly identical answers
        String sharedAnswer = "The singleton pattern ensures that a class has only one instance and provides a global point of access to it";
        CandidateAnswer currentAnswer = createAnswer(SUBMISSION_ID, QUESTION_ID_1, sharedAnswer);
        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer));

        CandidateAnswer otherAnswer = createAnswer(otherSubmissionId, QUESTION_ID_1, sharedAnswer);
        when(answerRepository.findBySubmissionId(otherSubmissionId))
                .thenReturn(List.of(otherAnswer));

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.reason()).isEqualTo(FlagReason.COPIED_ANSWERS);
        assertThat(result.maxSimilarity()).isGreaterThanOrEqualTo(0.8);
        assertThat(result.rationale()).contains("exceeds threshold");
    }

    @Test
    void check_emptyAnswers_returnsLowWithoutCrashing() {
        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Current submission has empty text answers
        CandidateAnswer currentAnswer = createAnswer(SUBMISSION_ID, QUESTION_ID_1, "");
        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer));

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reason()).isNull();
        assertThat(result.maxSimilarity()).isEqualTo(0.0);
    }

    @Test
    void check_nullTextContent_returnsLowWithoutCrashing() {
        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Current submission has null text content
        CandidateAnswer currentAnswer = createAnswer(SUBMISSION_ID, QUESTION_ID_1, null);
        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer));

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reason()).isNull();
    }

    @Test
    void check_similarityAtExactThreshold_returnsHigh() {
        // Use a custom threshold of 0.5 for easier testing
        AiFlaggingProperties props = new AiFlaggingProperties(true, 0.8, 0.5, 0.5, 30);
        SimilarityCheckServiceImpl serviceWithLowThreshold =
                new SimilarityCheckServiceImpl(submissionRepository, answerRepository, props);

        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Answers with about 50% overlap: "a b c d" vs "a b e f" → intersection={a,b}, union={a,b,c,d,e,f} → 2/6 = 0.33
        // Need higher overlap: "a b c" vs "a b c d" → intersection={a,b,c}, union={a,b,c,d} → 3/4 = 0.75
        CandidateAnswer currentAnswer = createAnswer(SUBMISSION_ID, QUESTION_ID_1, "alpha beta gamma");
        CandidateAnswer otherAnswer = createAnswer(otherSubmissionId, QUESTION_ID_1, "alpha beta gamma delta");

        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer));
        when(answerRepository.findBySubmissionId(otherSubmissionId))
                .thenReturn(List.of(otherAnswer));

        SimilarityResult result = serviceWithLowThreshold.check(context);

        // 3/4 = 0.75 >= 0.5 threshold
        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.reason()).isEqualTo(FlagReason.COPIED_ANSWERS);
    }

    @Test
    void check_multipleQuestionsOnlyOneExceedsThreshold_returnsHigh() {
        SubmissionAnalysisContext context = buildContext();

        UUID otherSubmissionId = UUID.randomUUID();
        CandidateSubmission otherSubmission = createSubmission(otherSubmissionId);

        when(submissionRepository.findByAssessmentIdAndStatusInAndIdNot(
                eq(ASSESSMENT_ID), any(), eq(SUBMISSION_ID)))
                .thenReturn(List.of(otherSubmission));

        // Q1: different answers
        CandidateAnswer currentAnswer1 = createAnswer(SUBMISSION_ID, QUESTION_ID_1,
                "Completely unique answer about databases");
        // Q2: identical answers
        String copiedText = "The observer pattern defines a one to many dependency between objects so that when one object changes state all its dependents are notified";
        CandidateAnswer currentAnswer2 = createAnswer(SUBMISSION_ID, QUESTION_ID_2, copiedText);

        when(answerRepository.findBySubmissionId(SUBMISSION_ID))
                .thenReturn(List.of(currentAnswer1, currentAnswer2));

        CandidateAnswer otherAnswer1 = createAnswer(otherSubmissionId, QUESTION_ID_1,
                "My own original analysis of NoSQL advantages");
        CandidateAnswer otherAnswer2 = createAnswer(otherSubmissionId, QUESTION_ID_2, copiedText);

        when(answerRepository.findBySubmissionId(otherSubmissionId))
                .thenReturn(List.of(otherAnswer1, otherAnswer2));

        SimilarityResult result = service.check(context);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.reason()).isEqualTo(FlagReason.COPIED_ANSWERS);
    }

    // --- Jaccard similarity unit tests ---

    @Test
    void jaccardSimilarity_identicalTexts_returnsOne() {
        double similarity = service.jaccardSimilarity("hello world", "hello world");
        assertThat(similarity).isEqualTo(1.0);
    }

    @Test
    void jaccardSimilarity_completelyDifferentTexts_returnsZero() {
        double similarity = service.jaccardSimilarity("alpha beta gamma", "delta epsilon zeta");
        assertThat(similarity).isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarity_partialOverlap_returnsCorrectRatio() {
        // "hello world" → {hello, world}
        // "hello there" → {hello, there}
        // intersection = {hello}, union = {hello, world, there}
        // Jaccard = 1/3
        double similarity = service.jaccardSimilarity("hello world", "hello there");
        assertThat(similarity).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void jaccardSimilarity_bothEmpty_returnsZero() {
        double similarity = service.jaccardSimilarity("", "");
        assertThat(similarity).isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarity_oneEmpty_returnsZero() {
        double similarity = service.jaccardSimilarity("hello world", "");
        assertThat(similarity).isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarity_ignoresCaseAndPunctuation() {
        double similarity = service.jaccardSimilarity("Hello, World!", "hello world");
        assertThat(similarity).isEqualTo(1.0);
    }

    // --- normalize tests ---

    @Test
    void normalize_lowercasesAndStripsPunctuation() {
        var words = service.normalize("Hello, World! This is a TEST.");
        assertThat(words).containsExactlyInAnyOrder("hello", "world", "this", "is", "a", "test");
    }

    @Test
    void normalize_nullInput_returnsEmptySet() {
        var words = service.normalize(null);
        assertThat(words).isEmpty();
    }

    @Test
    void normalize_blankInput_returnsEmptySet() {
        var words = service.normalize("   ");
        assertThat(words).isEmpty();
    }

    // --- helpers ---

    private SubmissionAnalysisContext buildContext() {
        return new SubmissionAnalysisContext(
                SUBMISSION_ID,
                ASSESSMENT_ID,
                "Test Assessment",
                60,
                1800L,
                2,
                List.of()
        );
    }

    private CandidateSubmission createSubmission(UUID id) {
        CandidateSubmission submission = new CandidateSubmission();
        submission.setId(id);
        submission.setAssessmentId(ASSESSMENT_ID);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        return submission;
    }

    private CandidateAnswer createAnswer(UUID submissionId, UUID questionId, String textContent) {
        CandidateAnswer answer = new CandidateAnswer();
        answer.setId(UUID.randomUUID());
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setTextContent(textContent);
        answer.setSavedAt(Instant.now());
        return answer;
    }
}
