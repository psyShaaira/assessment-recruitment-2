package com.psybergate.recruitment.ai.support;

import com.psybergate.recruitment.ai.AiProperties;
import com.psybergate.recruitment.ai.client.GroqClient;
import com.psybergate.recruitment.ai.dto.GroqChatResponse;
import com.psybergate.recruitment.ai.dto.GroqChoice;
import com.psybergate.recruitment.ai.dto.GroqMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reusable {@link GroqClient} test double backed by Spring's {@link MockRestServiceServer}, so any
 * AI-feature test that needs to exercise the real HTTP boundary doesn't re-wire the mock server itself.
 * Most feature tests (question generation, marking, feedback report) mock {@code AiService} directly
 * with Mockito, which is the right altitude and doesn't need this harness at all — this is only for
 * tests exercising {@link GroqClient} itself, or a future {@code AiClient} implementation.
 * <p>
 * Usage:
 * <pre>
 *   GroqTestHarness harness = GroqTestHarness.create();
 *   harness.stubValidCompletion("Hello!");
 *   String result = harness.client().sendPrompt("hi");
 *   harness.verify();
 * </pre>
 * The base URL is always bound to the mock server, so no test using this harness can reach
 * {@code api.groq.com}.
 */
public final class GroqTestHarness {

    public static final String BASE_URL = "https://api.groq.com/openai/v1";
    public static final String API_KEY = "test-secret-key";
    public static final String MODEL = "llama3-8b-8192";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MockRestServiceServer mockServer;
    private final GroqClient client;

    private GroqTestHarness(MockRestServiceServer mockServer, GroqClient client) {
        this.mockServer = mockServer;
        this.client = client;
    }

    public static GroqTestHarness create() {
        return create(new AiProperties(API_KEY, BASE_URL, MODEL, 0.7, 30));
    }

    public static GroqTestHarness create(AiProperties properties) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(BASE_URL));
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        GroqClient client = new GroqClient(properties, RestClient.create(restTemplate));
        return new GroqTestHarness(mockServer, client);
    }

    public GroqClient client() {
        return client;
    }

    public void verify() {
        mockServer.verify();
    }

    // ── canned response fixtures ────────────────────────────────────────────

    /** 200 response whose message content is exactly {@code content}. */
    public void stubValidCompletion(String content) {
        expectChatCompletion().andRespond(withSuccess(completionBody(content), MediaType.APPLICATION_JSON));
    }

    /** 200 response whose body is not valid JSON at all. */
    public void stubMalformedJson() {
        expectChatCompletion().andRespond(withSuccess("not json {{{", MediaType.APPLICATION_JSON));
    }

    /** 200 response with an empty choices array — no completion returned. */
    public void stubEmptyResponse() {
        expectChatCompletion().andRespond(
                withSuccess(OBJECT_MAPPER.writeValueAsString(new GroqChatResponse(List.of())), MediaType.APPLICATION_JSON));
    }

    /** 401 Unauthorized. */
    public void stubUnauthorized() {
        expectChatCompletion().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    }

    /** 429 Too Many Requests, as Groq's free-tier rate limiter returns. */
    public void stubRateLimited() {
        expectChatCompletion().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    }

    /** 500 Internal Server Error. */
    public void stubServerError() {
        expectChatCompletion().andRespond(withServerError());
    }

    /** 503 Service Unavailable. */
    public void stubServiceUnavailable() {
        expectChatCompletion().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /** Socket timeout, as GroqClient maps to AiTimeoutException. */
    public void stubTimeout() {
        expectChatCompletion().andRespond(request -> {
            throw new SocketTimeoutException("simulated Groq timeout");
        });
    }

    private ResponseActions expectChatCompletion() {
        return mockServer.expect(requestTo(BASE_URL + "/chat/completions")).andExpect(method(POST));
    }

    private static String completionBody(String content) {
        return OBJECT_MAPPER.writeValueAsString(new GroqChatResponse(
                List.of(new GroqChoice(new GroqMessage("assistant", content)))
        ));
    }
}
