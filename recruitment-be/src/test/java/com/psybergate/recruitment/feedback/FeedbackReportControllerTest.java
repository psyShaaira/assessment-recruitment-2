package com.psybergate.recruitment.feedback;

import com.psybergate.recruitment.feedback.dto.FeedbackReportContent;
import com.psybergate.recruitment.feedback.dto.FeedbackReportResponse;
import com.psybergate.recruitment.security.JwtService;
import com.psybergate.recruitment.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedbackReportController.class)
@Import(SecurityConfig.class)
class FeedbackReportControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean FeedbackReportService feedbackReportService;
    @MockitoBean JwtService jwtService;

    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final String STAFF_USER_ID = "00000000-0000-0000-0000-000000000001";

    // ── authorization ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = STAFF_USER_ID, roles = "RECRUITER")
    void generate_recruiterRole_returns200() throws Exception {
        when(feedbackReportService.generate(any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = STAFF_USER_ID, roles = "ADMIN")
    void generate_adminRole_returns200() throws Exception {
        when(feedbackReportService.generate(any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CANDIDATE")
    void generate_candidateRole_returns403() throws Exception {
        mockMvc.perform(post("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void generate_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RECRUITER")
    void getExisting_recruiterRole_returns200() throws Exception {
        when(feedbackReportService.getExisting(any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CANDIDATE")
    void getExisting_candidateRole_returns403() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}/feedback-report", SUBMISSION_ID))
                .andExpect(status().isForbidden());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private FeedbackReportResponse sampleResponse() {
        FeedbackReportContent content = new FeedbackReportContent(
                "Good performance",
                List.of(),
                List.of("Keep practising")
        );
        return new FeedbackReportResponse(SUBMISSION_ID, content, true, "v1", Instant.now());
    }
}
