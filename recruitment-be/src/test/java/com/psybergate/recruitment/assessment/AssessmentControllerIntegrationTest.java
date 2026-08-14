package com.psybergate.recruitment.assessment;

import tools.jackson.databind.ObjectMapper;
import com.psybergate.recruitment.AbstractIntegrationTest;
import com.psybergate.recruitment.TestDatasourceInitializer;
import com.psybergate.recruitment.assessment.dto.AddAssessmentQuestionRequest;
import com.psybergate.recruitment.assessment.dto.AssemblyQuotaDto;
import com.psybergate.recruitment.assessment.dto.AssemblySuggestionRequest;
import com.psybergate.recruitment.assessment.dto.AssessmentRequest;
import com.psybergate.recruitment.assessment.dto.ReorderAssessmentQuestionsRequest;
import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.domain.Role;
import com.psybergate.recruitment.domain.User;
import com.psybergate.recruitment.question.dto.QuestionOptionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;
import com.psybergate.recruitment.repository.AssessmentRepository;
import com.psybergate.recruitment.repository.QuestionRepository;
import com.psybergate.recruitment.repository.UserRepository;
import com.psybergate.recruitment.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDatasourceInitializer.class)
class AssessmentControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired QuestionRepository questionRepository;
    @Autowired AssessmentRepository assessmentRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private String token;
    private String candidateToken;
    private User recruiter;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail("atest@integration.dev").ifPresent(userRepository::delete);
        userRepository.findByEmail("acandidate@integration.dev").ifPresent(userRepository::delete);

        recruiter = new User();
        recruiter.setFirstName("Test");
        recruiter.setLastName("Recruiter");
        recruiter.setEmail("atest@integration.dev");
        recruiter.setPasswordHash(passwordEncoder.encode("pass"));
        recruiter.setRole(Role.RECRUITER);
        recruiter = userRepository.save(recruiter);
        token = jwtService.generateToken(recruiter.getId().toString(), Role.RECRUITER, 1L);

        User candidate = new User();
        candidate.setFirstName("Test");
        candidate.setLastName("Candidate");
        candidate.setEmail("acandidate@integration.dev");
        candidate.setPasswordHash(passwordEncoder.encode("pass"));
        candidate.setRole(Role.CANDIDATE);
        candidate = userRepository.save(candidate);
        candidateToken = jwtService.generateToken(candidate.getId().toString(), Role.CANDIDATE, 1L);
    }

    @AfterEach
    void tearDown() {
        assessmentRepository.deleteAll();
        questionRepository.deleteAll();
        userRepository.findByEmail("atest@integration.dev").ifPresent(userRepository::delete);
        userRepository.findByEmail("acandidate@integration.dev").ifPresent(userRepository::delete);
    }

    // â”€â”€ CRUD â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void createAssessment_valid_returns201() throws Exception {
        AssessmentRequest req = new AssessmentRequest("Java Backend Assessment", "For senior roles", 60, null, false, java.util.List.of());

        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.title").value("Java Backend Assessment"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.questions", hasSize(0)));
    }

    @Test
    void createAssessment_missingTitle_returns400() throws Exception {
        AssessmentRequest req = new AssessmentRequest("", "desc", 30, null, false, java.util.List.of());

        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAssessment_zeroTimeLimit_returns400() throws Exception {
        AssessmentRequest req = new AssessmentRequest("Title", "desc", 0, null, false, java.util.List.of());

        mockMvc.perform(post("/api/assessments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAssessments_returns200() throws Exception {
        createAssessmentViaApi("Assessment One", 45);

        mockMvc.perform(get("/api/assessments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void getAssessmentById_returns200WithDetail() throws Exception {
        String id = createAssessmentViaApi("Detail Test", 30);

        mockMvc.perform(get("/api/assessments/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.questions", hasSize(0)));
    }

    @Test
    void getAssessmentById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/assessments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateAssessment_returns200() throws Exception {
        String id = createAssessmentViaApi("Original Title", 30);
        AssessmentRequest update = new AssessmentRequest("Updated Title", "new desc", 60, null, false, java.util.List.of());

        mockMvc.perform(put("/api/assessments/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.timeLimitMinutes").value(60));
    }

    @Test
    void deleteAssessment_returns204() throws Exception {
        String id = createAssessmentViaApi("To Delete", 20);

        mockMvc.perform(delete("/api/assessments/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/assessments/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // â”€â”€ Publish â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void publishAssessment_draftToPublished_returns200() throws Exception {
        String id = createAssessmentViaApi("Publish Me", 45);

        mockMvc.perform(put("/api/assessments/" + id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publishAssessment_alreadyPublished_returns409() throws Exception {
        String id = createAssessmentViaApi("Already Published", 45);

        mockMvc.perform(put("/api/assessments/" + id + "/publish")
                        .header("Authorization", "Bearer " + token));

        mockMvc.perform(put("/api/assessments/" + id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // â”€â”€ Question sub-resource â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void addQuestion_valid_returns201() throws Exception {
        String assessmentId = createAssessmentViaApi("Q Sub Test", 30);
        String questionId = createTextQuestionViaApi("What is Java?");

        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.fromString(questionId), 10);

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questions", hasSize(1)))
                .andExpect(jsonPath("$.questions[0].questionId").value(questionId));
    }

    @Test
    void addQuestion_questionsReturnedInDisplayOrder() throws Exception {
        String assessmentId = createAssessmentViaApi("Order Test", 30);
        String q1 = createTextQuestionViaApi("Question One");
        String q2 = createTextQuestionViaApi("Question Two");

        addQuestionToAssessment(assessmentId, q1, 20);
        addQuestionToAssessment(assessmentId, q2, 10);

        mockMvc.perform(get("/api/assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].displayOrder").value(10))
                .andExpect(jsonPath("$.questions[1].displayOrder").value(20));
    }

    @Test
    void addQuestion_idempotent_returns200() throws Exception {
        String assessmentId = createAssessmentViaApi("Idempotent Test", 30);
        String questionId = createTextQuestionViaApi("Idempotent Q");

        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.fromString(questionId), 10);

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", hasSize(1)));
    }

    @Test
    void addQuestion_nonExistentQuestion_returns404() throws Exception {
        String assessmentId = createAssessmentViaApi("404 Q Test", 30);
        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.randomUUID(), 10);

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addQuestion_secondCodeSubmission_returns201() throws Exception {
        String assessmentId = createAssessmentViaApi("Code Limit Test", 30);
        String codeQ1 = createCodeSubmissionQuestionViaApi("Sort implementation");
        String codeQ2 = createCodeSubmissionQuestionViaApi("Graph algorithm");

        addQuestionToAssessment(assessmentId, codeQ1, 10);

        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.fromString(codeQ2), 20);

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void addQuestion_firstCodeSubmission_returns201() throws Exception {
        String assessmentId = createAssessmentViaApi("First Code Test", 30);
        String codeQ = createCodeSubmissionQuestionViaApi("Write a function");

        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.fromString(codeQ), 10);

        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void removeQuestion_returns204_questionRemainsInBank() throws Exception {
        String assessmentId = createAssessmentViaApi("Remove Q Test", 30);
        String questionId = createTextQuestionViaApi("To Remove");

        addQuestionToAssessment(assessmentId, questionId, 10);

        mockMvc.perform(delete("/api/assessments/" + assessmentId + "/questions/" + questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.questions", hasSize(0)));

        mockMvc.perform(get("/api/questions/" + questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void removeQuestion_notLinked_returns404() throws Exception {
        String assessmentId = createAssessmentViaApi("Not Linked", 30);

        mockMvc.perform(delete("/api/assessments/" + assessmentId + "/questions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // â”€â”€ Reorder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void reorderQuestions_happyPath_returns200WithNewOrder() throws Exception {
        String assessmentId = createAssessmentViaApi("Reorder Test", 30);
        String q1 = createTextQuestionViaApi("First question");
        String q2 = createTextQuestionViaApi("Second question");
        addQuestionToAssessment(assessmentId, q1, 1);
        addQuestionToAssessment(assessmentId, q2, 2);

        var items = List.of(
                new ReorderAssessmentQuestionsRequest.QuestionOrderItem(UUID.fromString(q1), 2),
                new ReorderAssessmentQuestionsRequest.QuestionOrderItem(UUID.fromString(q2), 1)
        );
        ReorderAssessmentQuestionsRequest req = new ReorderAssessmentQuestionsRequest(items);

        mockMvc.perform(put("/api/assessments/" + assessmentId + "/questions/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].questionId").value(q2))
                .andExpect(jsonPath("$.questions[1].questionId").value(q1));
    }

    @Test
    void reorderQuestions_unknownQuestionId_returns422() throws Exception {
        String assessmentId = createAssessmentViaApi("Reorder 422 Test", 30);
        String q1 = createTextQuestionViaApi("Real question");
        addQuestionToAssessment(assessmentId, q1, 1);

        var items = List.of(
                new ReorderAssessmentQuestionsRequest.QuestionOrderItem(UUID.randomUUID(), 1)
        );
        ReorderAssessmentQuestionsRequest req = new ReorderAssessmentQuestionsRequest(items);

        mockMvc.perform(put("/api/assessments/" + assessmentId + "/questions/order")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    // â”€â”€ Preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void previewAssessment_draftState_returns200() throws Exception {
        String assessmentId = createAssessmentViaApi("Preview DRAFT", 45);
        String qId = createTextQuestionViaApi("Describe OOP");
        addQuestionToAssessment(assessmentId, qId, 10);

        mockMvc.perform(get("/api/assessments/" + assessmentId + "/preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Preview DRAFT"))
                .andExpect(jsonPath("$.questions", hasSize(1)));
    }

    @Test
    void previewAssessment_publishedState_returns200() throws Exception {
        String assessmentId = createAssessmentViaApi("Preview PUBLISHED", 45);

        mockMvc.perform(put("/api/assessments/" + assessmentId + "/publish")
                .header("Authorization", "Bearer " + token));

        mockMvc.perform(get("/api/assessments/" + assessmentId + "/preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void previewAssessment_mcqOptionsHaveNoIsCorrect() throws Exception {
        String assessmentId = createAssessmentViaApi("MCQ Preview", 30);
        String mcqId = createMcqQuestionViaApi("Which is correct?");
        addQuestionToAssessment(assessmentId, mcqId, 10);

        String body = mockMvc.perform(get("/api/assessments/" + assessmentId + "/preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // isCorrect must not appear in the preview response for MCQ options
        assert !body.contains("isCorrect") : "Preview should not expose isCorrect";
    }

    @Test
    void previewAssessment_codeSubmissionIncludesLanguageHint() throws Exception {
        String assessmentId = createAssessmentViaApi("Code Preview", 60);
        String codeId = createCodeSubmissionQuestionViaApi("Write a sorter");
        addQuestionToAssessment(assessmentId, codeId, 10);

        mockMvc.perform(get("/api/assessments/" + assessmentId + "/preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].languageHint").value("java"));
    }

    @Test
    void previewAssessment_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/assessments/" + UUID.randomUUID() + "/preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewAssessment_candidateRole_returns403() throws Exception {
        String assessmentId = createAssessmentViaApi("Candidate Preview", 30);

        mockMvc.perform(get("/api/assessments/" + assessmentId + "/preview")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAssessments_candidateRole_returns403() throws Exception {
        mockMvc.perform(get("/api/assessments")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void suggestQuestions_matchingTagAndDifficulty_returnsFromBankNoShortfall() throws Exception {
        createTextQuestionViaApi("Java generics", List.of("java"), Difficulty.EASY);
        createTextQuestionViaApi("Java streams", List.of("java"), Difficulty.EASY);
        createTextQuestionViaApi("Java records", List.of("java"), Difficulty.HARD); // wrong difficulty
        createTextQuestionViaApi("SQL joins", List.of("sql"), Difficulty.EASY); // wrong tag

        AssemblyQuotaDto quota = new AssemblyQuotaDto("java", Difficulty.EASY, 2);
        AssemblySuggestionRequest req = new AssemblySuggestionRequest(List.of(quota));

        mockMvc.perform(post("/api/assessments/suggest-questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].suggested", hasSize(2)))
                .andExpect(jsonPath("$.outcomes[0].shortfall").value(0))
                .andExpect(jsonPath("$.outcomes[0].suggested[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$.outcomes[0].suggested[0].tags[0]").value("java"));
    }

    @Test
    void suggestQuestions_notEnoughInBank_returnsShortfallInsteadOf400() throws Exception {
        createTextQuestionViaApi("Only one match", List.of("rare-topic"), Difficulty.HARD);

        AssemblyQuotaDto quota = new AssemblyQuotaDto("rare-topic", Difficulty.HARD, 5);
        AssemblySuggestionRequest req = new AssemblySuggestionRequest(List.of(quota));

        mockMvc.perform(post("/api/assessments/suggest-questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].suggested", hasSize(1)))
                .andExpect(jsonPath("$.outcomes[0].shortfall").value(4));
    }

    @Test
    void suggestQuestions_candidateRole_returns403() throws Exception {
        AssemblyQuotaDto quota = new AssemblyQuotaDto("java", Difficulty.EASY, 1);
        AssemblySuggestionRequest req = new AssemblySuggestionRequest(List.of(quota));

        mockMvc.perform(post("/api/assessments/suggest-questions")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private String createAssessmentViaApi(String title, int minutes) throws Exception {
        AssessmentRequest req = new AssessmentRequest(title, null, minutes, null, false, java.util.List.of());
        String body = mockMvc.perform(post("/api/assessments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createTextQuestionViaApi(String title) throws Exception {
        QuestionRequest req = new QuestionRequest(QuestionType.TEXT, title, "body", null, null, null, null, null, null);
        String body = mockMvc.perform(post("/api/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createTextQuestionViaApi(String title, List<String> tags, Difficulty difficulty) throws Exception {
        QuestionRequest req = new QuestionRequest(QuestionType.TEXT, title, "body", tags, null, null, null, null, difficulty);
        String body = mockMvc.perform(post("/api/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createCodeSubmissionQuestionViaApi(String title) throws Exception {
        QuestionRequest req = new QuestionRequest(QuestionType.CODE_SUBMISSION, title, "body", null, null, "java", null, null, null);
        String body = mockMvc.perform(post("/api/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createMcqQuestionViaApi(String title) throws Exception {
        QuestionRequest req = new QuestionRequest(QuestionType.MCQ, title, "body", null,
                List.of(new QuestionOptionRequest("Option A", true),
                        new QuestionOptionRequest("Option B", false)), null, null, null, null);
        String body = mockMvc.perform(post("/api/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void addQuestionToAssessment(String assessmentId, String questionId, int order) throws Exception {
        AddAssessmentQuestionRequest req = new AddAssessmentQuestionRequest(UUID.fromString(questionId), order);
        mockMvc.perform(post("/api/assessments/" + assessmentId + "/questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)));
    }
}

