package com.psybergate.recruitment.question;

import com.psybergate.recruitment.AbstractIntegrationTest;
import com.psybergate.recruitment.TestDatasourceInitializer;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.Role;
import com.psybergate.recruitment.domain.User;
import com.psybergate.recruitment.question.dto.GenerateDistractorsRequest;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDatasourceInitializer.class)
class DistractorGenerationControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @MockitoBean AiService aiService;

    private static final String PATH = "/api/questions/generate-distractors";
    private static final String VALID_AI_RESPONSE = "{\"distractors\":[\"London\",\"Berlin\",\"Madrid\"]}";

    private String recruiterToken;
    private String candidateToken;

    @BeforeEach
    void setUp() {
        User recruiter = new User();
        recruiter.setFirstName("Distractor");
        recruiter.setLastName("Recruiter");
        recruiter.setEmail("distractor-recruiter@integration.dev");
        recruiter.setPasswordHash(passwordEncoder.encode("pass"));
        recruiter.setRole(Role.RECRUITER);
        recruiter = userRepository.save(recruiter);
        recruiterToken = jwtService.generateToken(recruiter.getId().toString(), Role.RECRUITER, 1L);

        User candidateUser = new User();
        candidateUser.setFirstName("Distractor");
        candidateUser.setLastName("Candidate");
        candidateUser.setEmail("distractor-candidate@integration.dev");
        candidateUser.setPasswordHash(passwordEncoder.encode("pass"));
        candidateUser.setRole(Role.CANDIDATE);
        candidateUser = userRepository.save(candidateUser);
        candidateToken = jwtService.generateToken(candidateUser.getId().toString(), Role.CANDIDATE, 1L);
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail("distractor-recruiter@integration.dev").ifPresent(userRepository::delete);
        userRepository.findByEmail("distractor-candidate@integration.dev").ifPresent(userRepository::delete);
    }

    @Test
    void generateDistractors_asRecruiter_returns200WithCorrectFalseOptions() throws Exception {
        when(aiService.prompt(anyString())).thenReturn(VALID_AI_RESPONSE);

        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", 3);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].correct", everyItem(is(false))))
                .andExpect(jsonPath("$[*].text", containsInAnyOrder("London", "Berlin", "Madrid")));

        verify(aiService, times(1)).prompt(anyString());
    }

    @Test
    void generateDistractors_noAuth_returns401or403() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", 3);

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateDistractors_candidateRole_returns403() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", 3);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateDistractors_blankBody_returns400() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest("   ", "Paris", 3);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateDistractors_blankCorrectAnswer_returns400() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "   ", 3);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateDistractors_badCountZero_returns400() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", 0);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiService);
    }

    @Test
    void generateDistractors_badCountTooHigh_returns400() throws Exception {
        GenerateDistractorsRequest req = new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", 6);

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + recruiterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiService);
    }
}
