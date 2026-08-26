package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.domain.Candidate;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.email.EmailService;
import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import com.psybergate.recruitment.feedback.dto.FeedbackReportContent;
import com.psybergate.recruitment.feedback.repository.SubmissionFeedbackReportRepository;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendLogDto;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendResponse;
import com.psybergate.recruitment.feedbackemail.repository.FeedbackEmailSendLogRepository;
import com.psybergate.recruitment.marking.SubmissionService;
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

import java.time.Instant;
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
    private final FeedbackEmailBodyGenerator feedbackEmailBodyGenerator;
    private final EmailService emailService;
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

        // Req 2.6: render the report content into a plain-text email body.
        FeedbackReportContent content = parseContent(report.getContent());
        String body = feedbackEmailBodyGenerator.generateBody(content, result, candidate.getFirstName());

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
        FeedbackEmailSendLog sendLog = new FeedbackEmailSendLog();
        sendLog.setSubmissionId(submissionId);
        sendLog.setSentBy(sentBy);
        sendLog.setStatus(FeedbackEmailSendStatus.SENT);
        try {
            sendLog = feedbackEmailSendLogRepository.save(sendLog);
        } catch (Exception e) {
            // Req 5.3/5.4: the email was already sent successfully at this point, so a failure
            // to persist the SENT row must not surface as an error to the caller — log it and
            // still return a successful response, falling back to the current time for sentAt
            // since the persisted timestamp is unavailable.
            log.warn("Failed to persist SENT feedback email send log for submission {}", submissionId, e);
            sendLog.setSentAt(Instant.now());
        }

        return new FeedbackEmailSendResponse(sendLog.getSubmissionId(), sendLog.getStatus(), sendLog.getSentAt());
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
