package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.Candidate;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.email.EmailService;
import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import com.psybergate.recruitment.feedback.dto.FeedbackReportContent;
import com.psybergate.recruitment.feedback.dto.FeedbackTopicDto;
import com.psybergate.recruitment.feedback.repository.SubmissionFeedbackReportRepository;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendLogDto;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendResponse;
import com.psybergate.recruitment.feedbackemail.repository.FeedbackEmailSendLogRepository;
import com.psybergate.recruitment.marking.SubmissionService;
import com.psybergate.recruitment.marking.dto.ResultQuestionDto;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import com.psybergate.recruitment.repository.CandidateRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackEmailServiceImpl implements FeedbackEmailService {

    private static final String FULLY_MARKED = "FULLY_MARKED";
    private static final String DEFAULT_FAILURE_REASON = "The feedback email could not be sent due to an unknown error";

    private final SubmissionService submissionService;
    private final SubmissionFeedbackReportRepository submissionFeedbackReportRepository;
    private final CandidateSubmissionRepository candidateSubmissionRepository;
    private final CandidateRepository candidateRepository;
    private final FeedbackEmailSendLogRepository feedbackEmailSendLogRepository;
    private final FeedbackEmailSendLogWriter feedbackEmailSendLogWriter;
    private final EmailService emailService;
    private final AiService aiService;
    private final ObjectMapper objectMapper;

    @Override
    public FeedbackEmailSendResponse sendFeedbackEmail(UUID submissionId, UUID sentBy) {
        // Propagates 404 if the submission doesn't exist (Req 2.1, 2.2). No log row is written here.
        ResultSummaryResponse result = submissionService.getResult(submissionId);

        // Req 2.3: reject with 409 unless fully marked. No log row is written here.
        if (!FULLY_MARKED.equals(result.markingStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission is not fully marked");
        }

        // Req 2.4: reject with 404 if no feedback report exists yet. No log row is written here.
        SubmissionFeedbackReport report = submissionFeedbackReportRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback report not found"));

        // Req 2.5: resolve the candidate's name/email from the submission.
        Candidate candidate = resolveCandidate(submissionId);

        // Req 2.6: use AI to generate a candidate-friendly email body from the structured feedback.
        // Falls back to the static template if the AI call fails.
        FeedbackReportContent content = parseContent(report.getContent());
        String body = generateAiBody(content, candidate.getFirstName(), result);

        // Req 2.7: send the rendered email via the shared EmailService abstraction.
        try {
            emailService.sendFeedbackReport(candidate, body);
        } catch (Exception e) {
            // Req 2.9/2.10/6.1/6.2: persist the FAILED row on its own transaction (REQUIRES_NEW)
            // so it commits durably even though this method rethrows as a 502 below. No other
            // table is touched, so isolation holds structurally.
            String failureReason = (e.getMessage() != null && !e.getMessage().isBlank())
                    ? e.getMessage()
                    : DEFAULT_FAILURE_REASON;
            feedbackEmailSendLogWriter.saveFailure(submissionId, sentBy, failureReason);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to send feedback email", e);
        }

        // Req 2.8/2.11: send succeeded — persist a SENT row and return the response.
        FeedbackEmailSendLog log = new FeedbackEmailSendLog();
        log.setSubmissionId(submissionId);
        log.setSentBy(sentBy);
        log.setStatus(FeedbackEmailSendStatus.SENT);
        log = feedbackEmailSendLogRepository.save(log);

        return new FeedbackEmailSendResponse(log.getSubmissionId(), log.getStatus(), log.getSentAt());
    }

    /**
     * Parses the report's stored JSON {@code content} into a {@link FeedbackReportContent},
     * mirroring {@code FeedbackReportServiceImpl.parseContent}.
     */
    private FeedbackReportContent parseContent(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, FeedbackReportContent.class);
        } catch (JacksonException e) {
            throw new AiResponseException("The stored feedback report could not be parsed as structured feedback");
        }
    }

    /**
     * Generates a candidate-friendly email body by sending the structured feedback to
     * the AI service. If the AI call fails for any reason, falls back to the static
     * template so the candidate still receives their feedback.
     */
    private String generateAiBody(FeedbackReportContent content, String candidateName, ResultSummaryResponse result) {
        String resultsSection = buildResultsSection(result);
        try {
            String prompt = buildEmailPrompt(content, candidateName, result);
            String aiFeedback = aiService.prompt(prompt);
            return resultsSection + "\n" + aiFeedback;
        } catch (Exception e) {
            log.warn("AI email generation failed, falling back to static template: {}", e.getMessage());
            return resultsSection + "\n" + renderStaticFeedback(content);
        }
    }

    /**
     * Builds the top section of the email with a greeting, score summary, and
     * question-by-question results table.
     */
    private String buildResultsSection(ResultSummaryResponse result) {
        List<ResultQuestionDto> questions = flattenQuestions(result.questions());

        StringBuilder sb = new StringBuilder();
        sb.append("Assessment: ").append(result.assessmentTitle()).append("\n");
        sb.append("Score: ").append(result.totalScore()).append(" / ").append(result.maxScore()).append("\n\n");
        sb.append("Results:\n");
        sb.append(String.format("%-4s %-50s %s%n", "#", "Question", "Result"));
        sb.append("-".repeat(70)).append("\n");

        int num = 1;
        for (ResultQuestionDto q : questions) {
            String status;
            if (q.score() == null) {
                status = "—";
            } else if (q.score() >= q.maxScore()) {
                status = "Correct";
            } else if (q.score() > 0) {
                status = q.score() + "/" + q.maxScore();
            } else {
                status = "Incorrect";
            }
            String title = q.questionTitle();
            if (title.length() > 48) title = title.substring(0, 45) + "...";
            sb.append(String.format("%-4d %-50s %s%n", num++, title, status));
        }
        sb.append("-".repeat(70)).append("\n");
        return sb.toString();
    }

    /**
     * Builds the prompt that instructs the AI to write a SHORT feedback summary.
     * The results table is prepended separately — the AI only produces the feedback narrative.
     */
    private String buildEmailPrompt(FeedbackReportContent content, String candidateName, ResultSummaryResponse result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write a detailed feedback email (300-400 words) for a candidate's assessment results. ");
        sb.append("Be encouraging but honest. Use a professional, friendly tone. ");
        sb.append("Structure the response exactly as follows, separated by blank lines:\n\n");
        sb.append("1. A greeting paragraph addressing the candidate and acknowledging their effort.\n");
        sb.append("2. A section titled 'What went well' with bullet points (using '-') listing specific strengths demonstrated.\n");
        sb.append("3. A section titled 'Where there is room for improvement' with bullet points listing specific gaps.\n");
        sb.append("4. A section titled 'Next steps you might find helpful' with numbered items (1. 2. 3.) giving concrete, actionable recommendations.\n");
        sb.append("5. A short closing paragraph of encouragement.\n\n");
        sb.append("Use blank lines between sections. Use '-' for bullet points and '1.' style for numbered lists. ");
        sb.append("Do NOT use markdown bold (**) or headers (#). Plain text with bullets and numbers only. ");
        sb.append("Do NOT repeat the exact score — the results table is shown separately above. ");
        sb.append("Do NOT include a subject line. ");
        sb.append("Start with 'Hi ").append(candidateName).append(",' as the greeting. ");
        sb.append("End with 'The Psybergate Recruitment Team' as sign-off.\n\n");

        sb.append("Assessment: ").append(result.assessmentTitle()).append("\n");
        sb.append("Score: ").append(result.totalScore()).append(" / ").append(result.maxScore()).append("\n\n");

        sb.append("Feedback summary: ").append(content.overallSummary()).append("\n\n");

        sb.append("Topics:\n");
        for (FeedbackTopicDto topic : content.topics()) {
            sb.append("- ").append(topic.topic())
                .append(" | Strengths: ").append(topic.strengths())
                .append(" | Improve: ").append(topic.weaknesses()).append("\n");
        }

        sb.append("\nNext steps: ");
        sb.append(String.join("; ", content.nextSteps()));

        return sb.toString();
    }

    /**
     * Static fallback for the feedback narrative when the AI service is unavailable.
     */
    private String renderStaticFeedback(FeedbackReportContent content) {
        StringBuilder body = new StringBuilder();
        body.append(content.overallSummary()).append("\n\n");

        for (FeedbackTopicDto topic : content.topics()) {
            body.append(topic.topic()).append("\n")
                .append("  Strengths: ").append(topic.strengths()).append("\n")
                .append("  Areas for improvement: ").append(topic.weaknesses()).append("\n\n");
        }

        body.append("Suggested next steps:\n");
        for (String nextStep : content.nextSteps()) {
            body.append("- ").append(nextStep).append("\n");
        }
        body.append("\n");
        body.append("The Psybergate Recruitment Team");
        return body.toString();
    }

    private List<ResultQuestionDto> flattenQuestions(List<ResultQuestionDto> questions) {
        return questions.stream()
                .flatMap(q -> q.subQuestions() != null && !q.subQuestions().isEmpty()
                        ? q.subQuestions().stream()
                        : java.util.stream.Stream.of(q))
                .toList();
    }

    /**
     * Resolves the {@link Candidate} that owns the given submission by loading the
     * {@link CandidateSubmission} for its {@code candidateId}, then loading the
     * {@link Candidate} for that ID.
     */
    private Candidate resolveCandidate(UUID submissionId) {
        CandidateSubmission submission = candidateSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        return candidateRepository.findById(submission.getCandidateId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found"));
    }

    @Override
    public List<FeedbackEmailSendLogDto> getSendHistory(UUID submissionId) {
        // Req 4.2: reject with 404 if the submission doesn't exist.
        if (!candidateSubmissionRepository.existsById(submissionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }

        // Req 4.1/4.3: return the submission's send-log rows, newest first, mapped to DTOs.
        return feedbackEmailSendLogRepository.findBySubmissionIdOrderBySentAtDesc(submissionId).stream()
                .map(FeedbackEmailSendLogDto::from)
                .toList();
    }
}
