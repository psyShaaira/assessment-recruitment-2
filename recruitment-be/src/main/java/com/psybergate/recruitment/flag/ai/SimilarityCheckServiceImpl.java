package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.domain.SubmissionStatus;
import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.repository.CandidateAnswerRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimilarityCheckServiceImpl implements SimilarityCheckService {

    private final CandidateSubmissionRepository submissionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final AiFlaggingProperties properties;

    private static final List<SubmissionStatus> COMPLETED_STATUSES = List.of(
            SubmissionStatus.SUBMITTED, SubmissionStatus.AUTO_SUBMITTED
    );

    @Override
    public SimilarityResult check(SubmissionAnalysisContext context) {
        // 1. Find other completed submissions for the same assessment
        List<CandidateSubmission> otherSubmissions = submissionRepository
                .findByAssessmentIdAndStatusInAndIdNot(
                        context.assessmentId(), COMPLETED_STATUSES, context.submissionId());

        if (otherSubmissions.isEmpty()) {
            return new SimilarityResult(RiskLevel.LOW, null, 0.0, "No other submissions to compare");
        }

        // 2. Load current submission's TEXT/CODE answers (keyed by questionId)
        Map<UUID, String> currentAnswers = loadTextCodeAnswers(context.submissionId());

        if (currentAnswers.isEmpty()) {
            return new SimilarityResult(RiskLevel.LOW, null, 0.0, "No text/code answers to compare");
        }

        // 3. Compare against each other submission's answers
        double maxSimilarity = 0.0;

        for (CandidateSubmission otherSubmission : otherSubmissions) {
            Map<UUID, String> otherAnswers = loadTextCodeAnswers(otherSubmission.getId());

            for (Map.Entry<UUID, String> entry : currentAnswers.entrySet()) {
                UUID questionId = entry.getKey();
                String currentText = entry.getValue();

                String otherText = otherAnswers.get(questionId);
                if (otherText == null || otherText.isBlank()) {
                    continue;
                }

                double similarity = jaccardSimilarity(currentText, otherText);
                maxSimilarity = Math.max(maxSimilarity, similarity);

                if (maxSimilarity >= properties.similarityThreshold()) {
                    String rationale = String.format(
                            "Answer similarity %.2f exceeds threshold %.2f for question %s",
                            maxSimilarity, properties.similarityThreshold(), questionId);
                    return new SimilarityResult(RiskLevel.HIGH, FlagReason.COPIED_ANSWERS, maxSimilarity, rationale);
                }
            }
        }

        return new SimilarityResult(RiskLevel.LOW, null, maxSimilarity, "No significant similarity");
    }

    private Map<UUID, String> loadTextCodeAnswers(UUID submissionId) {
        return answerRepository.findBySubmissionId(submissionId).stream()
                .filter(a -> a.getTextContent() != null && !a.getTextContent().isBlank())
                .collect(Collectors.toMap(
                        CandidateAnswer::getQuestionId,
                        CandidateAnswer::getTextContent,
                        (existing, replacement) -> existing
                ));
    }

    double jaccardSimilarity(String textA, String textB) {
        Set<String> wordsA = normalize(textA);
        Set<String> wordsB = normalize(textB);

        if (wordsA.isEmpty() && wordsB.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    Set<String> normalize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        // Lowercase, strip punctuation, split on whitespace
        String cleaned = text.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(word -> !word.isBlank())
                .collect(Collectors.toSet());
    }
}
