package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.AbstractIntegrationTest;
import com.psybergate.recruitment.TestDatasourceInitializer;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository;
import com.psybergate.recruitment.repository.*;
import com.psybergate.recruitment.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDatasourceInitializer.class)
class AiFlaggingControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired CandidateRepository candidateRepository;
    @Autowired AssessmentRepository assessmentRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired CandidateSubmissionRepository submissionRepository;
    @Autowired FlaggingRiskAssessmentRepository riskAssessmentRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private User recruiter;
    private CandidateSubmission submission;
    private FlaggingRiskAssessment riskAssessment;
    private String recruiterToken;

    @BeforeEach
    void setUp() {
        recruiter = new User();
        recruiter.setFirstName("AI");
        recruiter.setLastName("Tester");
        recruiter.setEmail("ai-flag-recruiter@integration.dev");
        recruiter.setPasswordHash(passwordEncoder.encode("pass"));
        recruiter.setRole(Role.RECRUITER);
        recruiter = userRepository.save(recruiter);
        recruiterToken = jwtService.generateToken(recruiter.getId().toString(), Role.RECRUITER, 1L);

        Candidate candidate = new Candidate();
        candidate.setFirstName("AI");
        candidate.setLastName("Candidate");
        candidate.setEmail("ai-flag-candidate@integration.dev");
        candidate.setCreatedBy(recruiter);
        candidate = candidateRepository.save(candidate);

        Assessment assessment = new Assessment();
        assessment.setTitle("AI Flag Test Assessment");
        assessment.setDescription("Test");
        assessment.setTimeLimitMinutes(60);
        assessment.setCreatedBy(recruiter);
        assessment = assessmentRepository.save(assessment);

        CandidateInvitation invitation = new CandidateInvitation();
        invitation.setCandidate(candidate);
        invitation.setAssessment(assessment);
        invitation.setInvitationToken("ai-flag-token-" + UUID.randomUUID());
        invitation.setExpiresAt(Instant.now().plusSeconds(86_400));
        invitation = invitationRepository.save(invitation);

        submission = new CandidateSubmission();
        submission.setCandidateId(candidate.getId());
        submission.setAssessmentId(assessment.getId());
        submission.setInvitationId(invitation.getId());
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setStartedAt(Instant.now().minusSeconds(3600));
        submission.setSubmittedAt(Instant.now());
        submission = submissionRepository.save(submission);

        // Seed a risk assessment for the submission
        riskAssessment = new FlaggingRiskAssessment();
        riskAssessment.setSubmissionId(submission.getId());
        riskAssessment.setRisk(RiskLevel.HIGH);
        riskAssessment.setReasons("[\"TIMING_ANOMALY\",\"AI_GENERATED_CONTENT\"]");
        riskAssessment.setRationale("Completed in 2 minutes; text patterns suggest AI generation.");
        riskAssessment.setConfidence(0.92);
        riskAssessment.setAnalyzedAt(Instant.now());
        riskAssessment.setPromptVersion("v1");
        riskAssessment.setFlagCreated(true);
        riskAssessment = riskAssessmentRepository.save(riskAssessment);
    }

    @AfterEach
    void tearDown() {
        riskAssessmentRepository.deleteAll();
        submissionRepository.deleteAll();
        invitationRepository.deleteAll();
        assessmentRepository.deleteAll();
        candidateRepository.deleteAll();
        userRepository.findByEmail("ai-flag-recruiter@integration.dev").ifPresent(userRepository::delete);
    }

    // ── GET /api/submissions/{id}/risk-assessment ─────────────────────────

    @Test
    void getRiskAssessment_validRecruiterToken_returns200WithCorrectBody() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}/risk-assessment", submission.getId())
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submission.getId().toString()))
                .andExpect(jsonPath("$.risk").value("HIGH"))
                .andExpect(jsonPath("$.reasons", hasSize(2)))
                .andExpect(jsonPath("$.reasons[0]").value("TIMING_ANOMALY"))
                .andExpect(jsonPath("$.reasons[1]").value("AI_GENERATED_CONTENT"))
                .andExpect(jsonPath("$.rationale").value(containsString("AI generation")))
                .andExpect(jsonPath("$.confidence").value(0.92))
                .andExpect(jsonPath("$.promptVersion").value("v1"))
                .andExpect(jsonPath("$.flagCreated").value(true));
    }

    @Test
    void getRiskAssessment_noAssessmentExists_returns404() throws Exception {
        UUID randomId = UUID.randomUUID();
        mockMvc.perform(get("/api/submissions/{id}/risk-assessment", randomId)
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRiskAssessment_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}/risk-assessment", submission.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRiskAssessment_withCandidateToken_returns403() throws Exception {
        String candidateToken = jwtService.generateToken(UUID.randomUUID().toString(), Role.CANDIDATE, 1L);
        mockMvc.perform(get("/api/submissions/{id}/risk-assessment", submission.getId())
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }
}
