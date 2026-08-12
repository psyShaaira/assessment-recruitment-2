package com.psybergate.recruitment.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.Question;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.domain.Tag;
import com.psybergate.recruitment.feedback.domain.SubmissionFeedbackReport;
import com.psybergate.recruitment.feedback.dto.FeedbackReportResponse;
import com.psybergate.recruitment.feedback.repository.SubmissionFeedbackReportRepository;
import com.psybergate.recruitment.marking.SubmissionService;
import com.psybergate.recruitment.marking.dto.ResultQuestionDto;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import com.psybergate.recruitment.repository.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackReportServiceImplTest {

    @Mock private SubmissionService submissionService;
    @Mock private AiService aiService;
    @Mock private SubmissionFeedbackReportRepository reportRepository;
    @Mock private QuestionRepository questionRepository;

    @InjectMocks
    private FeedbackReportServiceImpl service;

    private UUID submissionId;
    private UUID requestedBy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Inject ObjectMapper via reflection since @InjectMocks doesn't handle it
        var field = FeedbackReportServiceImpl.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(service, objectMapper);

        submissionId = UUID.randomUUID();
        requestedBy = UUID.randomUUID();
    }

    // ── generate ─────────────────────────────────────────────────────────────

    @Test
    void generate_fullyMarkedSubmission_returnsReportWithAllQuestions() throws Exception {
        UUID q1Id = UUID.randomUUID();
        UUID q2Id = UUID.randomUUID();
        ResultSummaryResponse result = fullyMarkedResult(submissionId, q1Id, q2Id);
        String validJson = validFeedbackJson();

        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(questionRepository.findAllById(any())).thenReturn(List.of());
        when(aiService.promptForJson(anyString())).thenReturn(validJson);
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FeedbackReportResponse response = service.generate(submissionId, requestedBy);

        assertThat(response.submissionId()).isEqualTo(submissionId);
        assertThat(response.content().overallSummary()).isEqualTo("Good performance");
        assertThat(response.content().topics()).hasSize(1);
        assertThat(response.content().nextSteps()).hasSize(1);
        assertThat(response.aiGenerated()).isTrue();
        assertThat(response.promptVersion()).isEqualTo(FeedbackReportServiceImpl.PROMPT_VERSION);
    }

    @Test
    void generate_partiallyMarkedSubmission_promptNotesUnscored() throws Exception {
        UUID q1Id = UUID.randomUUID();
        UUID q2Id = UUID.randomUUID();
        ResultSummaryResponse result = partiallyMarkedResult(submissionId, q1Id, q2Id);
        String validJson = validFeedbackJson();

        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(questionRepository.findAllById(any())).thenReturn(List.of());
        when(aiService.promptForJson(anyString())).thenReturn(validJson);
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generate(submissionId, requestedBy);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).promptForJson(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("not yet scored");
    }

    @Test
    void generate_promptNeverContainsCandidateName() throws Exception {
        UUID q1Id = UUID.randomUUID();
        ResultSummaryResponse result = fullyMarkedResult(submissionId, q1Id, UUID.randomUUID());
        String validJson = validFeedbackJson();

        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(questionRepository.findAllById(any())).thenReturn(List.of());
        when(aiService.promptForJson(anyString())).thenReturn(validJson);
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generate(submissionId, requestedBy);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).promptForJson(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // PII check — candidate name must not appear in the prompt
        assertThat(prompt).doesNotContain("Jane Doe");
        assertThat(prompt).doesNotContain("jane.doe@example.com");
    }

    @Test
    void generate_malformedAiJson_throwsAiResponseException() {
        UUID q1Id = UUID.randomUUID();
        ResultSummaryResponse result = fullyMarkedResult(submissionId, q1Id, UUID.randomUUID());

        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(questionRepository.findAllById(any())).thenReturn(List.of());
        when(aiService.promptForJson(anyString())).thenReturn("not json {{{");
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(submissionId, requestedBy))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("JsonProcessingException"); // no stack trace leak
    }

    @Test
    void generate_existingReport_updatesInPlace() throws Exception {
        UUID q1Id = UUID.randomUUID();
        ResultSummaryResponse result = fullyMarkedResult(submissionId, q1Id, UUID.randomUUID());
        String validJson = validFeedbackJson();
        SubmissionFeedbackReport existing = new SubmissionFeedbackReport();
        existing.setSubmissionId(submissionId);

        when(submissionService.getResult(submissionId)).thenReturn(result);
        when(questionRepository.findAllById(any())).thenReturn(List.of());
        when(aiService.promptForJson(anyString())).thenReturn(validJson);
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(existing));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generate(submissionId, requestedBy);

        ArgumentCaptor<SubmissionFeedbackReport> captor = ArgumentCaptor.forClass(SubmissionFeedbackReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing); // same instance = update, not insert
    }

    // ── getExisting ───────────────────────────────────────────────────────────

    @Test
    void getExisting_reportFound_returnsMappedResponse() throws Exception {
        SubmissionFeedbackReport report = new SubmissionFeedbackReport();
        report.setSubmissionId(submissionId);
        report.setContent(validFeedbackJson());
        report.setAiGenerated(true);
        report.setPromptVersion("v1");
        report.setGeneratedAt(Instant.now());

        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(report));

        FeedbackReportResponse response = service.getExisting(submissionId);

        assertThat(response.submissionId()).isEqualTo(submissionId);
        assertThat(response.content().overallSummary()).isEqualTo("Good performance");
    }

    @Test
    void getExisting_noReport_throws404() {
        when(reportRepository.findBySubmissionId(submissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getExisting(submissionId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResultSummaryResponse fullyMarkedResult(UUID submissionId, UUID q1Id, UUID q2Id) {
        return new ResultSummaryResponse(
                submissionId,
                "Jane Doe",            // candidate name — must NOT appear in prompt
                "Java Developer Assessment",
                Instant.now(),
                8, 10, 2,
                "FULLY_MARKED",
                List.of(
                        question(q1Id, "Write a Java method", 4, 5),
                        question(q2Id, "Explain OOP principles", 4, 5)
                )
        );
    }

    private ResultSummaryResponse partiallyMarkedResult(UUID submissionId, UUID q1Id, UUID q2Id) {
        return new ResultSummaryResponse(
                submissionId,
                "Jane Doe",
                "Java Developer Assessment",
                Instant.now(),
                4, 10, 2,
                "PENDING_REVIEW",
                List.of(
                        question(q1Id, "Write a Java method", 4, 5),
                        questionUnscored(q2Id, "Explain OOP principles", 5)
                )
        );
    }

    private ResultQuestionDto question(UUID id, String title, int score, int maxScore) {
        return new ResultQuestionDto(id, UUID.randomUUID(), title,
                QuestionType.TEXT, "some answer", score, maxScore,
                null, false, null, null, null);
    }

    private ResultQuestionDto questionUnscored(UUID id, String title, int maxScore) {
        return new ResultQuestionDto(id, null, title,
                QuestionType.TEXT, null, null, maxScore,
                null, false, null, null, null);
    }

    private String validFeedbackJson() throws Exception {
        return """
                {
                  "overallSummary": "Good performance",
                  "topics": [{"topic": "OOP", "strengths": "Solid", "weaknesses": "Could improve"}],
                  "nextSteps": ["Practice more algorithms"]
                }
                """;
    }
}
