package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.domain.Candidate;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.email.EmailService;
import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import com.psybergate.recruitment.feedback.repository.SubmissionFeedbackReportRepository;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendLog;
import com.psybergate.recruitment.feedbackemail.domain.FeedbackEmailSendStatus;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendResponse;
import com.psybergate.recruitment.feedbackemail.repository.FeedbackEmailSendLogRepository;
import com.psybergate.recruitment.marking.SubmissionService;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import com.psybergate.recruitment.repository.CandidateRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackEmailServiceImplTest {

    @Mock private SubmissionService submissionService;
    @Mock private SubmissionFeedbackReportRepository submissionFeedbackReportRepository;
    @Mock private CandidateSubmissionRepository candidateSubmissionRepository;
    @Mock private CandidateRepository candidateRepository;
    @Mock private FeedbackEmailSendLogRepository feedbackEmailSendLogRepository;
    @Mock private FeedbackEmailSendLogWriter feedbackEmailSendLogWriter;
    @Mock private FeedbackEmailBodyGenerator feedbackEmailBodyGenerator;
    @Mock private EmailService emailService;

    @InjectMocks
    private FeedbackEmailServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID submissionId;
    private UUID sentBy;
    private Candidate candidate;
    private CandidateSubmission submission;

    @BeforeEach
    void setUp() throws Exception {
        var field = FeedbackEmailServiceImpl.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(service, objectMapper);

        submissionId = UUID.randomUUID();
        sentBy = UUID.randomUUID();

        candidate = new Candidate();
        candidate.setId(UUID.randomUUID());
        candidate.setFirstName("Jane");
        candidate.setLastName("Doe");
        candidate.setEmail("jane.doe@example.com");

        submission = new CandidateSubmission();
        submission.setId(submissionId);
        submission.setCandidateId(candidate.getId());
    }

    // ── happy path: body from the FeedbackEmailBodyGenerator collaborator (Req 1.1, 5.1) ──────

    @Test
    void sendFeedbackEmail_success_sendsGeneratedBodyAndReturnsSent() {
        setupHappyPath();
        String cannedBody = "Hi Jane,\n\nCanned AI body.\n\nThe Psybergate Recruitment Team";
        when(feedbackEmailBodyGenerator.generateBody(any(), any(), eq("Jane"))).thenReturn(cannedBody);

        FeedbackEmailSendResponse response = service.sendFeedbackEmail(submissionId, sentBy);

        verify(emailService).sendFeedbackReport(eq(candidate), eq(cannedBody));
        assertThat(response.status()).isEqualTo(FeedbackEmailSendStatus.SENT);
    }

    @Test
    void sendFeedbackEmail_success_delegatesBodyGenerationWithParsedContentAndResult() {
        setupHappyPath();
        when(feedbackEmailBodyGenerator.generateBody(any(), any(), any())).thenReturn("canned body");

        service.sendFeedbackEmail(submissionId, sentBy);

        ArgumentCaptor<ResultSummaryResponse> resultCaptor = ArgumentCaptor.forClass(ResultSummaryResponse.class);
        verify(feedbackEmailBodyGenerator).generateBody(any(), resultCaptor.capture(), eq("Jane"));
        assertThat(resultCaptor.getValue().markingStatus()).isEqualTo("FULLY_MARKED");
    }

    // ── validation: submission not fully marked ──────────────────────────────

    @Test
    void sendFeedbackEmail_notFullyMarked_throws409() {
        ResultSummaryResponse result = resultWithStatus("PENDING_REVIEW");
        when(submissionService.getResult(submissionId)).thenReturn(result);

        assertThatThrownBy(() -> service.sendFeedbackEmail(submissionId, sentBy))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);

        verifyNoInteractions(emailService);
    }

    // ── validation: no feedback report ───────────────────────────────────────

    @Test
    void sendFeedbackEmail_noFeedbackReport_throws404() {
        ResultSummaryResponse result = resultWithStatus("FULLY_MARKED");
        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(submissionFeedbackReportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendFeedbackEmail(submissionId, sentBy))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);

        verifyNoInteractions(emailService);
    }

    // ── email send failure: logs FAILED and throws 502 ───────────────────────

    @Test
    void sendFeedbackEmail_emailSendFails_logsFailureAndThrows502() {
        ResultSummaryResponse result = resultWithStatus("FULLY_MARKED");
        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(submissionFeedbackReportRepository.findBySubmissionId(submissionId))
                .thenReturn(Optional.of(feedbackReport()));
        when(candidateSubmissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission));
        when(candidateRepository.findById(candidate.getId()))
                .thenReturn(Optional.of(candidate));
        when(feedbackEmailBodyGenerator.generateBody(any(), any(), any())).thenReturn("canned body");
        doThrow(new RuntimeException("SMTP timeout")).when(emailService).sendFeedbackReport(any(), anyString());

        assertThatThrownBy(() -> service.sendFeedbackEmail(submissionId, sentBy))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(502);

        verify(feedbackEmailSendLogWriter).saveFailure(submissionId, sentBy, "SMTP timeout");
    }

    // ── Req 5.4: SENT row save fails after a successful send ─────────────────

    @Test
    void sendFeedbackEmail_sentRowSaveFails_stillReturnsSuccessfully() {
        setupHappyPathWithoutLogSave();
        when(feedbackEmailBodyGenerator.generateBody(any(), any(), any())).thenReturn("canned body");
        doThrow(new RuntimeException("DB connection lost")).when(feedbackEmailSendLogRepository).save(any());

        FeedbackEmailSendResponse response = service.sendFeedbackEmail(submissionId, sentBy);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(FeedbackEmailSendStatus.SENT);
        assertThat(response.submissionId()).isEqualTo(submissionId);
        verify(emailService).sendFeedbackReport(eq(candidate), anyString());
        verifyNoInteractions(feedbackEmailSendLogWriter);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setupHappyPath() {
        setupHappyPathWithReport(feedbackReport());
        when(feedbackEmailSendLogRepository.save(any())).thenAnswer(inv -> {
            FeedbackEmailSendLog log = inv.getArgument(0);
            log.setSentAt(Instant.now());
            return log;
        });
    }

    private void setupHappyPathWithoutLogSave() {
        setupHappyPathWithReport(feedbackReport());
    }

    private void setupHappyPathWithReport(SubmissionFeedbackReport report) {
        ResultSummaryResponse result = resultWithStatus("FULLY_MARKED");
        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(submissionFeedbackReportRepository.findBySubmissionId(submissionId))
                .thenReturn(Optional.of(report));
        when(candidateSubmissionRepository.findById(submissionId))
                .thenReturn(Optional.of(submission));
        when(candidateRepository.findById(candidate.getId()))
                .thenReturn(Optional.of(candidate));
    }

    private ResultSummaryResponse resultWithStatus(String markingStatus) {
        return new ResultSummaryResponse(
                submissionId, "Jane Doe", "Java Developer Assessment",
                Instant.now(), 8, 10, 2, markingStatus, List.of(), null
        );
    }

    private SubmissionFeedbackReport feedbackReport() {
        return feedbackReportWithTopics(validFeedbackJson());
    }

    private SubmissionFeedbackReport feedbackReportWithTopics(String json) {
        SubmissionFeedbackReport report = new SubmissionFeedbackReport();
        report.setSubmissionId(submissionId);
        report.setContent(json);
        report.setAiGenerated(true);
        report.setPromptVersion("v1");
        report.setGeneratedAt(Instant.now());
        return report;
    }

    private String validFeedbackJson() {
        return """
                {
                  "overallSummary": "Good performance",
                  "topics": [{"topic": "OOP", "strengths": "Solid understanding", "weaknesses": "Could improve encapsulation"}],
                  "nextSteps": ["Practice design patterns"]
                }
                """;
    }
}
