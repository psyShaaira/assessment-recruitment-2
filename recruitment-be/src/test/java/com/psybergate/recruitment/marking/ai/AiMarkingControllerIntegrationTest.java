package com.psybergate.recruitment.marking.ai;

import com.psybergate.recruitment.AbstractIntegrationTest;
import com.psybergate.recruitment.TestDatasourceInitializer;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.marking.dto.ScoreAnswerRequest;
import com.psybergate.recruitment.question.domain.TextQuestion;
import com.psybergate.recruitment.repository.*;
import com.psybergate.recruitment.security.JwtService;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDatasourceInitializer.class)
class AiMarkingControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired CandidateRepository candidateRepository;
    @Autowired AssessmentRepository assessmentRepository;
    @Autowired AssessmentQuestionRepository assessmentQuestionRepository;
    @Autowired QuestionRepository questionRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired CandidateSubmissionRepository submissionRepository;
    @Autowired CandidateAnswerRepository answerRepository;
    @Autowired AnswerScoreRepository scoreRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @MockitoBean AiService aiService;

    private User recruiter;
    private Candidate candidate;
    private Assessment assessment;
    private TextQuestion textQuestion;
    private CandidateSubmission submission;
    private CandidateAnswer answer;
    private String recruiterToken;
    private String candidateStaffToken;

    private static final String WELL_FORMED_AI_RESPONSE = "SCORE: 7\nRATIONALE: Solid explanation of the concept.";

    @BeforeEach
    void setUp() {
        recruiter = new User();
        recruiter.setFirstName("Ai");
        recruiter.setLastName("Recruiter");
        recruiter.setEmail("ai-marking-recruiter@integration.dev");
        recruiter.setPasswordHash(passwordEncoder.encode("pass"));
        recruiter.setRole(Role.RECRUITER);
        recruiter = userRepository.save(recruiter);
        recruiterToken = jwtService.generateToken(recruiter.getId().toString(), Role.RECRUITER, 1L);

        User candidateUser = new User();
        candidateUser.setFirstName("Ai");
        candidateUser.setLastName("Candidate");
        candidateUser.setEmail("ai-marking-candidate-user@integration.dev");
        candidateUser.setPasswordHash(passwordEncoder.encode("pass"));
        candidateUser.setRole(Role.CANDIDATE);
        candidateUser = userRepository.save(candidateUser);
        candidateStaffToken = jwtService.generateToken(candidateUser.getId().toString(), Role.CANDIDATE, 1L);

        candidate = new Candidate();
        candidate.setFirstName("Text");
        candidate.setLastName("Answerer");
        candidate.setEmail("ai-marking-candidate@integration.dev");
        candidate.setCreatedBy(recruiter);
        candidate = candidateRepository.save(candidate);

        assessment = new Assessment();
        assessment.setTitle("AI Marking Test Assessment");
        assessment.setDescription("Test");
        assessment.setTimeLimitMinutes(60);
        assessment.setCreatedBy(recruiter);
        assessment = assessmentRepository.save(assessment);

        textQuestion = new TextQuestion();
        textQuestion.setTitle("Explain polymorphism");
        textQuestion.setBody("Describe polymorphism in OOP.");
        textQuestion.setMaxScore(10);
        textQuestion.setCreatedBy(recruiter);
        textQuestion = (TextQuestion) questionRepository.save(textQuestion);

        AssessmentQuestion aq = new AssessmentQuestion();
        aq.setAssessment(assessment);
        aq.setQuestion(textQuestion);
        aq.setDisplayOrder(1);
        assessmentQuestionRepository.save(aq);

        CandidateInvitation invitation = new CandidateInvitation();
        invitation.setCandidate(candidate);
        invitation.setAssessment(assessment);
        invitation.setInvitationToken("ai-marking-token-" + UUID.randomUUID());
        invitation.setExpiresAt(Instant.now().plusSeconds(86_400));
        invitation = invitationRepository.save(invitation);

        submission = new CandidateSubmission();
        submission.setCandidateId(candidate.getId());
        submission.setAssessmentId(assessment.getId());
        submission.setInvitationId(invitation.getId());
        submission.setStartedAt(Instant.now());
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(Instant.now());
        submission = submissionRepository.save(submission);

        answer = new CandidateAnswer();
        answer.setSubmissionId(submission.getId());
        answer.setQuestionId(textQuestion.getId());
        answer.setTextContent("Polymorphism lets objects of different types respond to the same method call.");
        answer.setDraft(false);
        answer.setSavedAt(Instant.now());
        answer = answerRepository.save(answer);
    }

    @AfterEach
    void tearDown() {
        scoreRepository.deleteAll();
        answerRepository.deleteAll();
        submissionRepository.deleteAll();
        invitationRepository.deleteAll();
        assessmentQuestionRepository.deleteAll();
        questionRepository.deleteAll();
        assessmentRepository.deleteAll();
        candidateRepository.deleteAll();
        userRepository.findByEmail("ai-marking-recruiter@integration.dev").ifPresent(userRepository::delete);
        userRepository.findByEmail("ai-marking-candidate-user@integration.dev").ifPresent(userRepository::delete);
    }

    private String suggestionPath() {
        return "/api/submissions/" + submission.getId() + "/questions/" + textQuestion.getId() + "/ai-suggestion";
    }

    @Test
    void generateSuggestion_recruiterForTextAnswer_returns200WithSuggestionBody() throws Exception {
        when(aiService.prompt(anyString())).thenReturn(WELL_FORMED_AI_RESPONSE);

        mockMvc.perform(post(suggestionPath())
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").value(answer.getId().toString()))
                .andExpect(jsonPath("$.score").value(7))
                .andExpect(jsonPath("$.maxScore").value(10))
                .andExpect(jsonPath("$.rationale").value("Solid explanation of the concept."))
                .andExpect(jsonPath("$.generatedAt", notNullValue()));

        verify(aiService, times(1)).prompt(anyString());
    }

    @Test
    void generateSuggestion_candidateRole_returns403() throws Exception {
        mockMvc.perform(post(suggestionPath())
                        .header("Authorization", "Bearer " + candidateStaffToken))
                .andExpect(status().isForbidden());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateSuggestion_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(suggestionPath()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateThenScoreAnswer_scoreReflectsRecruiterValuesUnaffectedBySuggestion() throws Exception {
        when(aiService.prompt(anyString())).thenReturn(WELL_FORMED_AI_RESPONSE);

        mockMvc.perform(post(suggestionPath())
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(7));

        ScoreAnswerRequest scoreReq = new ScoreAnswerRequest(3, "Recruiter disagrees with AI suggestion");
        mockMvc.perform(put("/api/submissions/" + submission.getId() + "/answers/" + answer.getId() + "/score")
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scoreReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(3))
                .andExpect(jsonPath("$.autoMarked").value(false));

        AnswerScore persisted = scoreRepository.findByCandidateAnswerId(answer.getId()).orElseThrow();
        assertEquals(3, persisted.getScore());
        assertEquals("Recruiter disagrees with AI suggestion", persisted.getFeedback());
    }

    @Test
    void generateSuggestion_createsNoAnswerScoreRow() throws Exception {
        when(aiService.prompt(anyString())).thenReturn(WELL_FORMED_AI_RESPONSE);

        mockMvc.perform(post(suggestionPath())
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isOk());

        assertTrue(scoreRepository.findByCandidateAnswerId(answer.getId()).isEmpty());
    }

    @Test
    void getSuggestion_beforeGeneration_returns404() throws Exception {
        mockMvc.perform(get(suggestionPath())
                        .header("Authorization", "Bearer " + recruiterToken))
                .andExpect(status().isNotFound());
    }
}
