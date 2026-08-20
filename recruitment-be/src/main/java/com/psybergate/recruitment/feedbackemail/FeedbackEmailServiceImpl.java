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
        String body = renderBody(content, result, candidate.getFirstName());

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
     * Renders a {@link FeedbackReportContent} into the plain-text email body addressed to the
     * candidate (Req 2.6), consisting of exactly the following elements, in order, with no
     * separate narrative paragraph synthesized from {@code overallSummary} or anything else
     * (Req 2.19):
     * <ol>
     *   <li>a first-name greeting line (Req 2.7)</li>
     *   <li>the fixed introductory line (Req 2.8)</li>
     *   <li>a score sentence stating the whole-number percentage (Req 2.9)</li>
     *   <li>a strengths sentence naming every {@code Strong_Topic}, only if non-empty (Req 2.11, 2.12)</li>
     *   <li>a weaknesses sentence naming every {@code Weak_Topic}, only if non-empty (Req 2.13, 2.14, 2.15)</li>
     *   <li>the fixed transition line (Req 2.16)</li>
     *   <li>{@code nextSteps[]} rendered verbatim as bullets (Req 2.17)</li>
     *   <li>an encouraging sign-off sentence immediately before the signature (Req 2.18)</li>
     * </ol>
     */
    private String renderBody(FeedbackReportContent content, ResultSummaryResponse result, String candidateName) {
        long percentage = Math.round((double) result.totalScore() / result.maxScore() * 100);

        List<String> strongTopics = content.topics().stream()
                .filter(topic -> topic.strengths() != null && !topic.strengths().trim().isEmpty())
                .map(FeedbackTopicDto::topic)
                .toList();
        List<String> weakTopics = content.topics().stream()
                .filter(topic -> topic.weaknesses() != null && !topic.weaknesses().trim().isEmpty())
                .map(FeedbackTopicDto::topic)
                .toList();

        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(candidateName).append(",\n\n")
            .append("Here is your feedback on your recent assessment:\n\n")
            .append("You scored ").append(percentage).append("% overall.\n\n");

        if (!strongTopics.isEmpty()) {
            body.append("You demonstrated strong performance in ")
                    .append(joinWithAnd(strongTopics))
                    .append(".\n\n");
        }

        if (!weakTopics.isEmpty()) {
            body.append("There is room for improvement in ")
                    .append(joinWithAnd(weakTopics))
                    .append(".\n\n");
        }

        body.append("Here are some next steps to help you continue improving:\n");
        for (String nextStep : content.nextSteps()) {
            body.append("- ").append(nextStep).append("\n");
        }
        body.append("\n");

        body.append("Keep up the great work, and don't hesitate to reach out if you have any questions about your feedback.\n\n");
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
     * Joins a list of topic names using the shared comma/"and" rule (Req 2.10): comma-separated
     * for three or more items with "and" (no preceding comma) before the last, "X and Y" for
     * exactly two, the bare item for exactly one, and "" for an empty list.
     */
    private String joinWithAnd(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        String allButLast = String.join(", ", items.subList(0, items.size() - 1));
        return allButLast + ", and " + items.get(items.size() - 1);
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
