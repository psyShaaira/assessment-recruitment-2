package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.flag.SubmissionFlagService;
import com.psybergate.recruitment.flag.ai.dto.AiFlaggingResult;
import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository;
import com.psybergate.recruitment.question.domain.TextQuestion;
import com.psybergate.recruitment.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiFlaggingServiceImplTest {

    @Mock private AiService aiService;
    @Mock private AiFlaggingPromptBuilder promptBuilder;
    @Mock private FlaggingRiskAssessmentRepository riskAssessmentRepository;
    @Mock private SubmissionFlagService flagService;
    @Mock private SubmissionFlagRepository flagRepository;
    @Mock private CandidateSubmissionRepository submissionRepository;
    @Mock private CandidateAnswerRepository answerRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private AssessmentQuestionRepository assessmentQuestionRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private SimilarityCheckService similarityCheckService;

    private AiFlaggingServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default thresholds: high=0.8, medium=0.5, similarity=0.8, timeout=30
        AiFlaggingProperties properties = new AiFlaggingProperties(true, 0.8, 0.5, 0.8, 30);

        service = new AiFlaggingServiceImpl(
                aiService,
                promptBuilder,
                properties,
                similarityCheckService,
                riskAssessmentRepository,
                flagService,
                flagRepository,
                submissionRepository,
                answerRepository,
                assessmentRepository,
                assessmentQuestionRepository,
                questionRepository
        );
    }

    // --- Threshold validation tests ---

    @Test
    void parseResult_highRiskAboveThreshold_staysHigh() {
        String json = """
                {"risk": "HIGH", "reasons": ["TIMING_ANOMALY"], "rationale": "Suspiciously fast", "confidence": 0.92}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.confidence()).isEqualTo(0.92);
        assertThat(result.reasons()).containsExactly(FlagReason.TIMING_ANOMALY);
        assertThat(result.rationale()).isEqualTo("Suspiciously fast");
    }

    @Test
    void parseResult_highRiskBelowHighThreshold_downgradedToMedium() {
        String json = """
                {"risk": "HIGH", "reasons": ["AI_GENERATED_CONTENT"], "rationale": "Possible AI text", "confidence": 0.7}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.confidence()).isEqualTo(0.7);
        assertThat(result.reasons()).containsExactly(FlagReason.AI_GENERATED_CONTENT);
    }

    @Test
    void parseResult_highRiskAtExactHighThreshold_staysHigh() {
        String json = """
                {"risk": "HIGH", "reasons": ["TIMING_ANOMALY"], "rationale": "Fast", "confidence": 0.8}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void parseResult_mediumRiskAboveThreshold_staysMedium() {
        String json = """
                {"risk": "MEDIUM", "reasons": ["SUSPICIOUS_BEHAVIOUR"], "rationale": "Unusual pattern", "confidence": 0.6}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.confidence()).isEqualTo(0.6);
    }

    @Test
    void parseResult_mediumRiskBelowMediumThreshold_downgradedToLow() {
        String json = """
                {"risk": "MEDIUM", "reasons": ["SUSPICIOUS_BEHAVIOUR"], "rationale": "Slight concern", "confidence": 0.4}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.confidence()).isEqualTo(0.4);
    }

    @Test
    void parseResult_mediumRiskAtExactMediumThreshold_staysMedium() {
        String json = """
                {"risk": "MEDIUM", "reasons": ["TIMING_ANOMALY"], "rationale": "Borderline", "confidence": 0.5}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void parseResult_lowRiskAlwaysStaysLow() {
        String json = """
                {"risk": "LOW", "reasons": [], "rationale": "No issues detected", "confidence": 0.95}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
    }

    // --- Malformed/edge case tests ---

    @Test
    void parseResult_malformedJson_returnsLowDefault() {
        String json = "this is not JSON at all {{{";

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result).isEqualTo(AiFlaggingResult.LOW_DEFAULT);
    }

    @Test
    void parseResult_emptyString_returnsLowDefault() {
        AiFlaggingResult result = service.parseResult("");

        assertThat(result).isEqualTo(AiFlaggingResult.LOW_DEFAULT);
    }

    @Test
    void parseResult_nullInput_returnsLowDefault() {
        AiFlaggingResult result = service.parseResult(null);

        assertThat(result).isEqualTo(AiFlaggingResult.LOW_DEFAULT);
    }

    @Test
    void parseResult_unknownRiskLevel_defaultsToLow() {
        String json = """
                {"risk": "CRITICAL", "reasons": [], "rationale": "Unknown risk", "confidence": 0.9}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void parseResult_unknownReasonCodes_skipped() {
        String json = """
                {"risk": "HIGH", "reasons": ["TIMING_ANOMALY", "UNKNOWN_REASON", "AI_GENERATED_CONTENT"], "rationale": "Mixed reasons", "confidence": 0.85}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.reasons()).containsExactly(FlagReason.TIMING_ANOMALY, FlagReason.AI_GENERATED_CONTENT);
    }

    @Test
    void parseResult_missingFields_usesDefaults() {
        String json = """
                {"risk": "LOW"}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.rationale()).isEmpty();
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void parseResult_multipleReasons_allParsed() {
        String json = """
                {"risk": "HIGH", "reasons": ["TIMING_ANOMALY", "AI_GENERATED_CONTENT", "COPIED_ANSWERS"], "rationale": "Multiple flags", "confidence": 0.95}
                """;

        AiFlaggingResult result = service.parseResult(json);

        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.reasons()).containsExactly(
                FlagReason.TIMING_ANOMALY,
                FlagReason.AI_GENERATED_CONTENT,
                FlagReason.COPIED_ANSWERS
        );
    }

    // --- Merge results tests ---

    @Test
    void mergeResults_similarityHigherThanAi_takesHigherRisk() {
        AiFlaggingResult aiResult = new AiFlaggingResult(
                RiskLevel.LOW, List.of(), "No issues", 0.3);
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.HIGH, FlagReason.COPIED_ANSWERS, 0.92, "92% similarity with submission X");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(merged.reasons()).containsExactly(FlagReason.COPIED_ANSWERS);
        assertThat(merged.rationale()).contains("Similarity:");
        assertThat(merged.rationale()).contains("92% similarity");
    }

    @Test
    void mergeResults_aiHigherThanSimilarity_keepsAiRisk() {
        AiFlaggingResult aiResult = new AiFlaggingResult(
                RiskLevel.HIGH, List.of(FlagReason.TIMING_ANOMALY), "Suspiciously fast", 0.9);
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.LOW, null, 0.3, "");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(merged.reasons()).containsExactly(FlagReason.TIMING_ANOMALY);
        assertThat(merged.rationale()).isEqualTo("Suspiciously fast");
    }

    @Test
    void mergeResults_bothHigh_combinesReasons() {
        AiFlaggingResult aiResult = new AiFlaggingResult(
                RiskLevel.HIGH, List.of(FlagReason.TIMING_ANOMALY), "Fast completion", 0.85);
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.HIGH, FlagReason.COPIED_ANSWERS, 0.95, "95% match");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(merged.reasons()).containsExactly(FlagReason.TIMING_ANOMALY, FlagReason.COPIED_ANSWERS);
        // Same risk level — no rationale override
        assertThat(merged.rationale()).isEqualTo("Fast completion");
    }

    @Test
    void mergeResults_bothLow_staysLow() {
        AiFlaggingResult aiResult = AiFlaggingResult.LOW_DEFAULT;
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.LOW, null, 0.2, "");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(merged.reasons()).isEmpty();
    }

    @Test
    void mergeResults_duplicateReasonNotAdded() {
        AiFlaggingResult aiResult = new AiFlaggingResult(
                RiskLevel.MEDIUM, List.of(FlagReason.COPIED_ANSWERS), "Detected copy", 0.6);
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.HIGH, FlagReason.COPIED_ANSWERS, 0.88, "88% match");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.HIGH);
        // Should not have duplicate COPIED_ANSWERS
        assertThat(merged.reasons()).containsExactly(FlagReason.COPIED_ANSWERS);
    }

    @Test
    void mergeResults_similarityMediumAiLow_upgradesAndAppendsRationale() {
        AiFlaggingResult aiResult = new AiFlaggingResult(
                RiskLevel.LOW, List.of(), "No issues", 0.2);
        SimilarityResult similarityResult = new SimilarityResult(
                RiskLevel.MEDIUM, FlagReason.COPIED_ANSWERS, 0.75, "75% partial match");

        AiFlaggingResult merged = service.mergeResults(aiResult, similarityResult);

        assertThat(merged.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(merged.reasons()).containsExactly(FlagReason.COPIED_ANSWERS);
        assertThat(merged.rationale()).contains("75% partial match");
    }

    // --- analyze() flow tests ---

    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final UUID ASSESSMENT_ID = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();

    private void setupAnalyzeHappyPath() {
        CandidateSubmission submission = new CandidateSubmission();
        submission.setId(SUBMISSION_ID);
        submission.setAssessmentId(ASSESSMENT_ID);
        submission.setStartedAt(Instant.now().minusSeconds(1800));
        submission.setSubmittedAt(Instant.now());

        Assessment assessment = new Assessment();
        assessment.setId(ASSESSMENT_ID);
        assessment.setTitle("Java Basics");
        assessment.setTimeLimitMinutes(60);

        CandidateAnswer answer = new CandidateAnswer();
        answer.setQuestionId(QUESTION_ID);
        answer.setSubmissionId(SUBMISSION_ID);
        answer.setTextContent("Some answer text");
        answer.setSavedAt(Instant.now().minusSeconds(900));

        TextQuestion question = new TextQuestion();
        question.setId(QUESTION_ID);
        question.setTitle("Explain OOP");
        question.setDifficulty(Difficulty.MEDIUM);
        question.setMaxScore(10);

        when(flagRepository.existsBySubmissionIdAndStatusIn(eq(SUBMISSION_ID), anyList())).thenReturn(false);
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));
        when(assessmentRepository.findById(ASSESSMENT_ID)).thenReturn(Optional.of(assessment));
        when(answerRepository.findBySubmissionId(SUBMISSION_ID)).thenReturn(List.of(answer));
        when(questionRepository.findAllById(anyIterable())).thenReturn(List.of(question));
        when(riskAssessmentRepository.findBySubmissionId(SUBMISSION_ID)).thenReturn(Optional.empty());
        when(riskAssessmentRepository.save(any(FlaggingRiskAssessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(similarityCheckService.check(any(SubmissionAnalysisContext.class)))
                .thenReturn(new SimilarityResult(RiskLevel.LOW, null, 0.1, ""));
    }

    @Test
    void analyze_highRisk_createsFlag() {
        setupAnalyzeHappyPath();

        String highRiskJson = """
                {"risk": "HIGH", "reasons": ["TIMING_ANOMALY"], "rationale": "Suspiciously fast", "confidence": 0.92}
                """;
        when(promptBuilder.build(any(SubmissionAnalysisContext.class))).thenReturn("prompt");
        when(aiService.promptForJson("prompt")).thenReturn(highRiskJson);

        service.analyze(SUBMISSION_ID);

        verify(flagService).createFlag(eq(SUBMISSION_ID), eq(FlagReason.TIMING_ANOMALY), any(UUID.class), eq("SYSTEM"));
    }

    @Test
    void analyze_mediumRisk_doesNotCreateFlag() {
        setupAnalyzeHappyPath();

        String mediumRiskJson = """
                {"risk": "MEDIUM", "reasons": ["SUSPICIOUS_BEHAVIOUR"], "rationale": "Unusual pattern", "confidence": 0.6}
                """;
        when(promptBuilder.build(any(SubmissionAnalysisContext.class))).thenReturn("prompt");
        when(aiService.promptForJson("prompt")).thenReturn(mediumRiskJson);

        service.analyze(SUBMISSION_ID);

        verify(flagService, never()).createFlag(any(), any(), any(), any());
    }

    @Test
    void analyze_lowRisk_doesNotCreateFlag() {
        setupAnalyzeHappyPath();

        String lowRiskJson = """
                {"risk": "LOW", "reasons": [], "rationale": "No issues", "confidence": 0.95}
                """;
        when(promptBuilder.build(any(SubmissionAnalysisContext.class))).thenReturn("prompt");
        when(aiService.promptForJson("prompt")).thenReturn(lowRiskJson);

        service.analyze(SUBMISSION_ID);

        verify(flagService, never()).createFlag(any(), any(), any(), any());
    }

    @Test
    void analyze_aiServiceThrows_gracefulDegradation() {
        setupAnalyzeHappyPath();

        when(promptBuilder.build(any(SubmissionAnalysisContext.class))).thenReturn("prompt");
        when(aiService.promptForJson("prompt")).thenThrow(new RuntimeException("Groq API timeout"));

        service.analyze(SUBMISSION_ID);

        // Should not throw, should persist LOW default
        verify(flagService, never()).createFlag(any(), any(), any(), any());
        ArgumentCaptor<FlaggingRiskAssessment> captor = ArgumentCaptor.forClass(FlaggingRiskAssessment.class);
        verify(riskAssessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getRisk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void analyze_openFlagExists_skipsAnalysis() {
        when(flagRepository.existsBySubmissionIdAndStatusIn(eq(SUBMISSION_ID), anyList())).thenReturn(true);

        service.analyze(SUBMISSION_ID);

        verify(aiService, never()).promptForJson(any());
        verify(submissionRepository, never()).findById(any());
        verify(riskAssessmentRepository, never()).save(any());
    }

    @Test
    void analyze_persistsRiskAssessment() {
        setupAnalyzeHappyPath();

        String highRiskJson = """
                {"risk": "HIGH", "reasons": ["AI_GENERATED_CONTENT"], "rationale": "AI text detected", "confidence": 0.88}
                """;
        when(promptBuilder.build(any(SubmissionAnalysisContext.class))).thenReturn("prompt");
        when(aiService.promptForJson("prompt")).thenReturn(highRiskJson);

        service.analyze(SUBMISSION_ID);

        ArgumentCaptor<FlaggingRiskAssessment> captor = ArgumentCaptor.forClass(FlaggingRiskAssessment.class);
        // save is called twice: once for persist, once for flag-created update
        verify(riskAssessmentRepository, atLeastOnce()).save(captor.capture());
        FlaggingRiskAssessment saved = captor.getAllValues().get(0);
        assertThat(saved.getSubmissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(saved.getRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(saved.getConfidence()).isEqualTo(0.88);
        assertThat(saved.getRationale()).isEqualTo("AI text detected");
        assertThat(saved.getReasons()).contains("AI_GENERATED_CONTENT");
        assertThat(saved.getPromptVersion()).isNotBlank();
    }
}
