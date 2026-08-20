package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.flag.SubmissionFlagService;
import com.psybergate.recruitment.flag.ai.dto.AiFlaggingResult;
import com.psybergate.recruitment.flag.ai.dto.RiskAssessmentResponse;
import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository;
import com.psybergate.recruitment.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AiFlaggingServiceImpl implements AiFlaggingService {

    private final AiService aiService;
    private final AiFlaggingPromptBuilder promptBuilder;
    private final AiFlaggingProperties properties;
    private final SimilarityCheckService similarityCheckService;
    private final FlaggingRiskAssessmentRepository riskAssessmentRepository;
    private final SubmissionFlagService flagService;
    private final SubmissionFlagRepository flagRepository;
    private final CandidateSubmissionRepository submissionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;

    private static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String SYSTEM_ACTOR_USERNAME = "SYSTEM";
    private static final List<FlagStatus> OPEN_STATUSES = List.of(
            FlagStatus.FLAGGED, FlagStatus.UNDER_REVIEW, FlagStatus.ACTION_REQUIRED
    );

    @Override
    public void analyze(UUID submissionId) {
        // 1. Skip if open flag already exists
        if (flagRepository.existsBySubmissionIdAndStatusIn(submissionId, OPEN_STATUSES)) {
            log.info("Skipping AI flagging — open flag already exists for submission {}", submissionId);
            return;
        }

        // 2. Build analysis context
        SubmissionAnalysisContext context = buildContext(submissionId);

        // 3. AI analysis (graceful failure)
        AiFlaggingResult aiResult = runAiAnalysis(context);

        // 4. Cross-submission similarity (independent of AI)
        SimilarityResult similarityResult = similarityCheckService.check(context);

        // 5. Merge results — take highest risk
        AiFlaggingResult merged = mergeResults(aiResult, similarityResult);

        // 6. Persist risk assessment (upsert)
        FlaggingRiskAssessment assessment = persistRiskAssessment(submissionId, merged);

        // 7. Auto-flag if HIGH
        if (merged.risk() == RiskLevel.HIGH) {
            tryCreateFlag(submissionId, merged, assessment);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RiskAssessmentResponse> getRiskAssessment(UUID submissionId) {
        return riskAssessmentRepository.findBySubmissionId(submissionId)
                .map(this::toResponse);
    }

    private SubmissionAnalysisContext buildContext(UUID submissionId) {
        CandidateSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> {
                    log.warn("Submission {} not found during AI flagging analysis", submissionId);
                    return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                            "Submission not found");
                });

        Assessment assessment = assessmentRepository.findById(submission.getAssessmentId())
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "Assessment not found"));

        List<CandidateAnswer> answers = answerRepository.findBySubmissionId(submissionId);

        // Load questions for the assessment to get metadata
        Set<UUID> questionIds = answers.stream()
                .map(CandidateAnswer::getQuestionId)
                .collect(Collectors.toSet());
        Map<UUID, Question> questionMap = questionRepository.findAllById(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        // Calculate actual duration
        long actualDurationSeconds = 0;
        if (submission.getStartedAt() != null && submission.getSubmittedAt() != null) {
            actualDurationSeconds = Duration.between(submission.getStartedAt(), submission.getSubmittedAt()).toSeconds();
        }

        // Build per-answer context
        List<AnswerContext> answerContexts = answers.stream()
                .map(answer -> {
                    Question question = questionMap.get(answer.getQuestionId());
                    long secondsSinceStart = 0;
                    if (submission.getStartedAt() != null && answer.getSavedAt() != null) {
                        secondsSinceStart = Duration.between(submission.getStartedAt(), answer.getSavedAt()).toSeconds();
                    }
                    return new AnswerContext(
                            question != null ? question.getTitle() : "Unknown",
                            question != null ? question.getType().name() : "UNKNOWN",
                            question != null && question.getDifficulty() != null ? question.getDifficulty().name() : "MEDIUM",
                            question != null ? question.getMaxScore() : 0,
                            answer.getTextContent(),
                            answer.getSavedAt(),
                            secondsSinceStart
                    );
                })
                .sorted(Comparator.comparingLong(AnswerContext::secondsSinceStart))
                .toList();

        return new SubmissionAnalysisContext(
                submissionId,
                submission.getAssessmentId(),
                assessment.getTitle(),
                assessment.getTimeLimitMinutes(),
                actualDurationSeconds,
                answerContexts.size(),
                answerContexts
        );
    }

    private AiFlaggingResult runAiAnalysis(SubmissionAnalysisContext context) {
        try {
            String prompt = promptBuilder.build(context);
            String rawJson = aiService.promptForJson(prompt);
            return parseResult(rawJson);
        } catch (Exception e) {
            log.warn("AI flagging analysis failed, skipping: {}", e.getMessage());
            return AiFlaggingResult.LOW_DEFAULT;
        }
    }

    /**
     * Merges the AI analysis result with the similarity check result.
     * Takes the highest risk level between the two. If similarity overrides
     * risk upward, appends its reason and rationale.
     */
    AiFlaggingResult mergeResults(AiFlaggingResult aiResult, SimilarityResult similarityResult) {
        RiskLevel highestRisk = higherRisk(aiResult.risk(), similarityResult.risk());

        List<FlagReason> mergedReasons = new ArrayList<>(aiResult.reasons());
        if (similarityResult.risk() != RiskLevel.LOW && similarityResult.reason() != null) {
            if (!mergedReasons.contains(similarityResult.reason())) {
                mergedReasons.add(similarityResult.reason());
            }
        }

        String mergedRationale = aiResult.rationale();
        if (similarityResult.risk().ordinal() < aiResult.risk().ordinal()
                && similarityResult.rationale() != null && !similarityResult.rationale().isBlank()) {
            // Similarity overrode risk upward — append its rationale
            mergedRationale = mergedRationale.isBlank()
                    ? similarityResult.rationale()
                    : mergedRationale + " | Similarity: " + similarityResult.rationale();
        }

        return new AiFlaggingResult(highestRisk, mergedReasons, mergedRationale, aiResult.confidence());
    }

    /**
     * Returns the higher of two risk levels (HIGH > MEDIUM > LOW).
     * Enum ordinal: HIGH=0, MEDIUM=1, LOW=2, so lower ordinal = higher risk.
     */
    private RiskLevel higherRisk(RiskLevel a, RiskLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /**
     * Parses the JSON response from the AI service into an {@link AiFlaggingResult},
     * then validates the risk level against confidence thresholds from {@link AiFlaggingProperties}.
     * <p>
     * Threshold logic:
     * <ul>
     *   <li>HIGH risk kept only if confidence &ge; highThreshold; otherwise downgraded to MEDIUM</li>
     *   <li>MEDIUM risk kept only if confidence &ge; mediumThreshold; otherwise downgraded to LOW</li>
     *   <li>LOW risk is never upgraded</li>
     * </ul>
     * Malformed/null/empty input defaults to {@link AiFlaggingResult#LOW_DEFAULT}.
     */
    AiFlaggingResult parseResult(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            log.warn("Empty or null AI flagging response, defaulting to LOW");
            return AiFlaggingResult.LOW_DEFAULT;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(rawJson);

            String riskStr = node.path("risk").asText("LOW");
            RiskLevel risk;
            try {
                risk = RiskLevel.valueOf(riskStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown risk level '{}' in AI response, defaulting to LOW", riskStr);
                risk = RiskLevel.LOW;
            }

            List<FlagReason> reasons = new ArrayList<>();
            JsonNode reasonsNode = node.get("reasons");
            if (reasonsNode != null && reasonsNode.isArray()) {
                for (JsonNode reasonNode : reasonsNode) {
                    try {
                        reasons.add(FlagReason.valueOf(reasonNode.asText()));
                    } catch (IllegalArgumentException ignored) {
                        // skip unknown reason codes
                    }
                }
            }

            String rationale = node.path("rationale").asText("");
            double confidence = node.path("confidence").asDouble(0.0);

            // Apply threshold validation — downgrade risk if confidence is insufficient
            risk = applyThresholdValidation(risk, confidence);

            return new AiFlaggingResult(risk, reasons, rationale, confidence);
        } catch (Exception e) {
            log.warn("Failed to parse AI flagging response, defaulting to LOW: {}", e.getMessage());
            return AiFlaggingResult.LOW_DEFAULT;
        }
    }

    /**
     * Downgrades the AI-reported risk level when confidence falls below
     * the configured thresholds.
     */
    private RiskLevel applyThresholdValidation(RiskLevel reported, double confidence) {
        return switch (reported) {
            case HIGH -> confidence >= properties.highThreshold() ? RiskLevel.HIGH : RiskLevel.MEDIUM;
            case MEDIUM -> confidence >= properties.mediumThreshold() ? RiskLevel.MEDIUM : RiskLevel.LOW;
            case LOW -> RiskLevel.LOW;
        };
    }

    private FlaggingRiskAssessment persistRiskAssessment(UUID submissionId, AiFlaggingResult result) {
        FlaggingRiskAssessment assessment = riskAssessmentRepository.findBySubmissionId(submissionId)
                .orElseGet(() -> {
                    FlaggingRiskAssessment newAssessment = new FlaggingRiskAssessment();
                    newAssessment.setSubmissionId(submissionId);
                    return newAssessment;
                });

        assessment.setRisk(result.risk());
        assessment.setReasons(serializeReasons(result.reasons()));
        assessment.setRationale(result.rationale());
        assessment.setConfidence(result.confidence());
        assessment.setAnalyzedAt(Instant.now());
        assessment.setPromptVersion(AiFlaggingPromptBuilder.PROMPT_VERSION);
        assessment.setFlagCreated(false);

        return riskAssessmentRepository.save(assessment);
    }

    private void tryCreateFlag(UUID submissionId, AiFlaggingResult result, FlaggingRiskAssessment assessment) {
        try {
            FlagReason primaryReason = result.reasons().isEmpty()
                    ? FlagReason.SUSPICIOUS_BEHAVIOUR
                    : result.reasons().get(0);
            flagService.createFlag(submissionId, primaryReason, SYSTEM_ACTOR_ID, SYSTEM_ACTOR_USERNAME);
            assessment.setFlagCreated(true);
            riskAssessmentRepository.save(assessment);
        } catch (ResponseStatusException e) {
            // 409 race condition — another flag was created between our check and create
            log.info("Flag creation failed for submission {} (likely race condition): {}", submissionId, e.getMessage());
            assessment.setFlagCreated(false);
            riskAssessmentRepository.save(assessment);
        }
    }

    private RiskAssessmentResponse toResponse(FlaggingRiskAssessment entity) {
        List<FlagReason> reasons = parseReasons(entity.getReasons());
        return new RiskAssessmentResponse(
                entity.getSubmissionId(),
                entity.getRisk(),
                reasons,
                entity.getRationale(),
                entity.getConfidence(),
                entity.getAnalyzedAt(),
                entity.getPromptVersion(),
                entity.isFlagCreated()
        );
    }

    private List<FlagReason> parseReasons(String reasonsJson) {
        if (reasonsJson == null || reasonsJson.isBlank()) {
            return List.of();
        }
        // Handle JSON array format: ["TIMING_ANOMALY","AI_GENERATED_CONTENT"]
        String cleaned = reasonsJson.replaceAll("[\\[\\]\"]", "");
        if (cleaned.isBlank()) {
            return List.of();
        }
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(r -> {
                    try { return FlagReason.valueOf(r); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String serializeReasons(List<FlagReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "[]";
        }
        return reasons.stream()
                .map(r -> "\"" + r.name() + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
