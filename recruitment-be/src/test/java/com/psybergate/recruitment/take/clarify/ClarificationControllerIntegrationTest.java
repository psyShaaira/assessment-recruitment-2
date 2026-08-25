package com.psybergate.recruitment.take.clarify;

import com.psybergate.recruitment.AbstractIntegrationTest;
import com.psybergate.recruitment.TestDatasourceInitializer;
import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.repository.*;
import com.psybergate.recruitment.security.JwtService;
import com.psybergate.recruitment.take.clarify.dto.ClarificationRequestDto;
import com.psybergate.recruitment.take.clarify.repository.ClarificationRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDatasourceInitializer.class)
class ClarificationControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired CandidateRepository candidateRepository;
    @Autowired AssessmentRepository assessmentRepository;
    @Autowired AssessmentQuestionRepository assessmentQuestionRepository;
    @Autowired QuestionRepository questionRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired CandidateSubmissionRepository submissionRepository;
    @Autowired ClarificationRequestRepository clarificationRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @MockitoBean AiService aiService;

    private User recruiter;
    private Candidate candidate;
    private Assessment assessment;
    private McqQuestion mcqQuestion;
    private String candidateSessionToken;

    @BeforeEach
    void setUp() {
        recruiter = new User();
        recruiter.setFirstName("Test");
        recruiter.setLastName("Recruiter");
        recruiter.setEmail("clarify-recruiter@integration.dev");
        recruiter.setPasswordHash(passwordEncoder.encode("pass"));
        recruiter.setRole(Role.RECRUITER);
        recruiter = userRepository.save(recruiter);

        candidate = new Candidate();
        candidate.setFirstName("Jane");
        candidate.setLastName("Tester");
        candidate.setEmail("clarify-candidate@integration.dev");
        candidate.setCreatedBy(recruiter);
        candidate = candidateRepository.save(candidate);

        assessment = new Assessment();
        assessment.setTitle("Clarify Test Assessment");
        assessment.setDescription("Test");
        assessment.setTimeLimitMinutes(60);
        assessment.setCreatedBy(recruiter);
        assessment = assessmentRepository.save(assessment);

        mcqQuestion = new McqQuestion();
        mcqQuestion.setTitle("Q1");
        mcqQuestion.setBody("Which keyword restricts visibility to the declaring class?");
        mcqQuestion.setCreatedBy(recruiter);
        mcqQuestion = (McqQuestion) questionRepository.save(mcqQuestion);

        QuestionOption a = new QuestionOption();
        a.setOptionText("private");
        a.setCorrect(true);
        a.setMcqQuestion(mcqQuestion);
        QuestionOption b = new QuestionOption();
        b.setOptionText("public");
        b.setCorrect(false);
        b.setMcqQuestion(mcqQuestion);
        mcqQuestion.getOptions().addAll(List.of(a, b));
        mcqQuestion = (McqQuestion) questionRepository.save(mcqQuestion);

        AssessmentQuestion aq = new AssessmentQuestion();
        aq.setAssessment(assessment);
        aq.setQuestion(mcqQuestion);
        aq.setDisplayOrder(1);
        assessmentQuestionRepository.save(aq);

        CandidateInvitation invitation = new CandidateInvitation();
        invitation.setCandidate(candidate);
        invitation.setAssessment(assessment);
        invitation.setInvitationToken("clarify-invitation-token-" + UUID.randomUUID());
        invitation.setExpiresAt(Instant.now().plusSeconds(86_400));
        invitationRepository.save(invitation);

        candidateSessionToken = jwtService.generateCandidateSessionToken(
                candidate.getId().toString(),
                assessment.getId().toString()
        );

        // Establish an active submission (mirrors a candidate having loaded the assessment)
        mustLoadAssessment();
    }

    @AfterEach
    void tearDown() {
        clarificationRequestRepository.deleteAll();
        submissionRepository.deleteAll();
        invitationRepository.deleteAll();
        assessmentQuestionRepository.deleteAll();
        questionRepository.deleteAll();
        assessmentRepository.deleteAll();
        candidateRepository.deleteAll();
        userRepository.findByEmail("clarify-recruiter@integration.dev").ifPresent(userRepository::delete);
    }

    private void mustLoadAssessment() {
        try {
            mockMvc.perform(get("/api/take/assessment")
                            .header("Authorization", "Bearer " + candidateSessionToken))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String body(UUID questionId, String note) throws Exception {
        return objectMapper.writeValueAsString(new ClarificationRequestDto(questionId, note));
    }

    @Test
    void clarify_validQuestion_returns200WithClarificationAndPersists() throws Exception {
        when(aiService.prompt(anyString()))
                .thenReturn("This question asks which access modifier limits visibility to the same class.");

        mockMvc.perform(post("/api/take/clarify")
                        .header("Authorization", "Bearer " + candidateSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(mcqQuestion.getId(), "I don't understand the wording")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.clarification", containsString("access modifier")))
                .andExpect(jsonPath("$.remainingForQuestion").value(2))
                .andExpect(jsonPath("$.remainingForAssessment").value(14));

        org.junit.jupiter.api.Assertions.assertEquals(1, clarificationRequestRepository.count());
    }

    @Test
    void clarify_outOfScopeQuestion_returns403() throws Exception {
        mockMvc.perform(post("/api/take/clarify")
                        .header("Authorization", "Bearer " + candidateSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(aiService);
        org.junit.jupiter.api.Assertions.assertEquals(0, clarificationRequestRepository.count());
    }

    @Test
    void clarify_noCandidateJwt_returns401or403() throws Exception {
        mockMvc.perform(post("/api/take/clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(mcqQuestion.getId(), null)))
                .andExpect(status().is(anyOf(equalTo(401), equalTo(403))));

        verifyNoInteractions(aiService);
    }

    @Test
    void clarify_exceedingPerQuestionLimit_returns429() throws Exception {
        when(aiService.prompt(anyString())).thenReturn("clarification text");

        // Default max-per-question = 3 — consume all three
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/take/clarify")
                            .header("Authorization", "Bearer " + candidateSessionToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(mcqQuestion.getId(), null)))
                    .andExpect(status().isOk());
        }

        // 4th request on the same question is rate limited
        mockMvc.perform(post("/api/take/clarify")
                        .header("Authorization", "Bearer " + candidateSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(mcqQuestion.getId(), null)))
                .andExpect(status().isTooManyRequests());

        org.junit.jupiter.api.Assertions.assertEquals(3, clarificationRequestRepository.count());
    }

    @Test
    void clarify_aiUnavailable_returnsDegraded200AndDoesNotPersist() throws Exception {
        when(aiService.prompt(anyString())).thenThrow(new AiCommunicationException("provider down"));

        mockMvc.perform(post("/api/take/clarify")
                        .header("Authorization", "Bearer " + candidateSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(mcqQuestion.getId(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));

        org.junit.jupiter.api.Assertions.assertEquals(0, clarificationRequestRepository.count());
    }
}
