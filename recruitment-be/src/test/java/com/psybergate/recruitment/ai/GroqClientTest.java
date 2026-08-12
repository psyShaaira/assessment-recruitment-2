package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.ai.client.GroqClient;
import com.psybergate.recruitment.ai.dto.GroqChatResponse;
import com.psybergate.recruitment.ai.dto.GroqChoice;
import com.psybergate.recruitment.ai.dto.GroqMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GroqClientTest {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String API_KEY = "test-secret-key";
    private static final String MODEL = "llama3-8b-8192";

    private MockRestServiceServer mockServer;
    private GroqClient groqClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiProperties props() {
        return new AiProperties(API_KEY, BASE_URL, MODEL, 0.7, 30);
    }

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new org.springframework.web.util.DefaultUriBuilderFactory(BASE_URL));
        mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.create(restTemplate);
        groqClient = new GroqClient(props(), restClient);
    }

    private String validResponseBody(String content) throws Exception {
        GroqChatResponse response = new GroqChatResponse(
                List.of(new GroqChoice(new GroqMessage("assistant", content)))
        );
        return objectMapper.writeValueAsString(response);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void sendPrompt_200WithContent_returnsContent() throws Exception {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess(validResponseBody("Hello!"), MediaType.APPLICATION_JSON));

        String result = groqClient.sendPrompt("hi");

        assertThat(result).isEqualTo("Hello!");
        mockServer.verify();
    }

    // ── error status mapping ──────────────────────────────────────────────────

    @Test
    void sendPrompt_401_throwsAiAuthenticationException() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiAuthenticationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("401");
    }

    @Test
    void sendPrompt_429_throwsAiRateLimitException() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiRateLimitException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("429");
    }

    @Test
    void sendPrompt_500_throwsAiCommunicationException() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiCommunicationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("500");
    }

    @Test
    void sendPrompt_503_throwsAiCommunicationException() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiCommunicationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("503");
    }

    // ── content validation ────────────────────────────────────────────────────

    @Test
    void sendPrompt_nullContent_throwsAiResponseException() throws Exception {
        GroqChatResponse response = new GroqChatResponse(
                List.of(new GroqChoice(new GroqMessage("assistant", null)))
        );
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    @Test
    void sendPrompt_blankContent_throwsAiResponseException() throws Exception {
        GroqChatResponse response = new GroqChatResponse(
                List.of(new GroqChoice(new GroqMessage("assistant", "   ")))
        );
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    // ── blank API key ─────────────────────────────────────────────────────────

    @Test
    void sendPrompt_blankApiKey_throwsAiAuthenticationExceptionBeforeHttpCall() {
        AiProperties noKey = new AiProperties("", BASE_URL, MODEL, 0.7, 30);
        GroqClient clientNoKey = new GroqClient(noKey, RestClient.create());

        assertThatThrownBy(() -> clientNoKey.sendPrompt("hi"))
                .isInstanceOf(AiAuthenticationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq");

        mockServer.verify();
    }

    // ── message safety ────────────────────────────────────────────────────────

    @Test
    void sendPrompt_401_messageDoesNotContainApiKey() {
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> groqClient.sendPrompt("hi"))
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain(API_KEY);
    }
}
