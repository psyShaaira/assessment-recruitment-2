package com.psybergate.recruitment.feedback;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.Question;
import com.psybergate.recruitment.domain.Tag;
import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import com.psybergate.recruitment.feedback.dto.FeedbackReportContent;
import com.psybergate.recruitment.feedback.dto.FeedbackReportResponse;
import com.psybergate.recruitment.feedback.repository.SubmissionFeedbackReportRepository;
import com.psybergate.recruitment.marking.SubmissionService;
import com.psybergate.recruitment.marking.dto.ResultQuestionDto;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import com.psybergate.recruitment.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedbackReportServiceImpl implements FeedbackReportService {

    static final String PROMPT_VERSION = "v1";

    private final SubmissionService submissionService;
    private final AiService aiService;
    private final SubmissionFeedbackReportRepository reportRepository;
    private final QuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public FeedbackReportResponse generate(UUID submissionId, UUID requestedBy) {
        ResultSummaryResponse result = submissionService.getResult(submissionId);

        // Fetch tags for all leaf questions (PII-safe: only IDs and tags, not candidate identity)
        List<UUID> questionIds = flattenQuestionIds(result.questions());
        Map<UUID, Set<String>> tagsByQuestionId = fetchTags(questionIds);

        String prompt = buildPrompt(result, tagsByQuestionId);
        String rawJson = aiService.promptForJson(prompt);

        FeedbackReportContent content = parseContent(rawJson);

        // Upsert: one report per submission, overwrite on regenerate
        SubmissionFeedbackReport report = reportRepository.findBySubmissionId(submissionId)
                .orElseGet(SubmissionFeedbackReport::new);
        report.setSubmissionId(submissionId);
        report.setContent(rawJson);
        report.setAiGenerated(true);
        report.setPromptVersion(PROMPT_VERSION);
        report.setGeneratedAt(Instant.now());
        report.setGeneratedBy(requestedBy);
        report = reportRepository.save(report);

        return new FeedbackReportResponse(
                submissionId, content, report.isAiGenerated(),
                report.getPromptVersion(), report.getGeneratedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackReportResponse getExisting(UUID submissionId) {
        SubmissionFeedbackReport report = reportRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No feedback report found for this submission"));

        FeedbackReportContent content = parseContent(report.getContent());
        return new FeedbackReportResponse(
                submissionId, content, report.isAiGenerated(),
                report.getPromptVersion(), report.getGeneratedAt()
        );
    }

    // ── prompt building ───────────────────────────────────────────────────────

    private String buildPrompt(ResultSummaryResponse result, Map<UUID, Set<String>> tagsByQuestionId) {
        List<ResultQuestionDto> allQuestions = flattenQuestions(result.questions());
        long unscoredCount = allQuestions.stream().filter(q -> q.score() == null).count();
        long totalCount = allQuestions.size();

        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert technical recruiter providing structured feedback on a candidate's assessment.\n");
        sb.append("Return ONLY a valid JSON object matching this exact schema — no markdown, no extra text:\n");
        sb.append("""
                {
                  "overallSummary": "<string>",
                  "topics": [
                    { "topic": "<string>", "strengths": "<string>", "weaknesses": "<string>" }
                  ],
                  "nextSteps": ["<string>"]
                }
                """);
        sb.append("\n");
        sb.append("Assessment: ").append(result.assessmentTitle()).append("\n");
        sb.append("Total score: ").append(result.totalScore()).append(" / ").append(result.maxScore()).append("\n");
        if (unscoredCount > 0) {
            sb.append("Note: ").append(unscoredCount).append(" of ").append(totalCount)
              .append(" questions are not yet scored — base feedback only on the scored responses below.\n");
        }
        sb.append("\nQuestions and answers:\n");

        for (ResultQuestionDto q : allQuestions) {
            if (q.score() == null) continue; // skip unscored — don't fabricate commentary
            Set<String> tags = tagsByQuestionId.getOrDefault(q.questionId(), Set.of());
            sb.append("- Question: ").append(q.questionTitle()).append("\n");
            if (!tags.isEmpty()) {
                sb.append("  Tags: ").append(String.join(", ", tags)).append("\n");
            }
            sb.append("  Candidate answer: ").append(q.candidateAnswer() != null ? q.candidateAnswer() : "(no answer)").append("\n");
            sb.append("  Score: ").append(q.score()).append(" / ").append(q.maxScore()).append("\n");
        }

        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<ResultQuestionDto> flattenQuestions(List<ResultQuestionDto> questions) {
        return questions.stream()
                .flatMap(q -> q.subQuestions() != null && !q.subQuestions().isEmpty()
                        ? q.subQuestions().stream()
                        : java.util.stream.Stream.of(q))
                .toList();
    }

    private List<UUID> flattenQuestionIds(List<ResultQuestionDto> questions) {
        return flattenQuestions(questions).stream()
                .map(ResultQuestionDto::questionId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Map<UUID, Set<String>> fetchTags(List<UUID> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        return questionRepository.findAllById(questionIds).stream()
                .collect(Collectors.toMap(
                        Question::getId,
                        q -> q.getTags().stream().map(Tag::getName).collect(Collectors.toSet())
                ));
    }

    private FeedbackReportContent parseContent(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, FeedbackReportContent.class);
        } catch (JacksonException e) {
            throw new AiResponseException("The AI provider returned a response that could not be parsed as structured feedback");
        }
    }
}
