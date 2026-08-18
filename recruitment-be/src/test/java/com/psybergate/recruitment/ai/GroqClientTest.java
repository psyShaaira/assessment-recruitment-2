package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.ai.support.GroqTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroqClientTest {

    private GroqTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = GroqTestHarness.create();
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void sendPrompt_200WithContent_returnsContent() {
        harness.stubValidCompletion("Hello!");

        String result = harness.client().sendPrompt("hi");

        assertThat(result).isEqualTo("Hello!");
        harness.verify();
    }

    // ── error status mapping ──────────────────────────────────────────────────

    @Test
    void sendPrompt_401_throwsAiAuthenticationException() {
        harness.stubUnauthorized();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiAuthenticationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("401");
    }

    @Test
    void sendPrompt_429_throwsAiRateLimitException() {
        harness.stubRateLimited();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiRateLimitException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("429");
    }

    @Test
    void sendPrompt_500_throwsAiCommunicationException() {
        harness.stubServerError();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiCommunicationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("500");
    }

    @Test
    void sendPrompt_503_throwsAiCommunicationException() {
        harness.stubServiceUnavailable();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiCommunicationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq")
                .doesNotContain("503");
    }

    @Test
    void sendPrompt_timeout_throwsAiTimeoutException() {
        harness.stubTimeout();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiTimeoutException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    // ── content validation ────────────────────────────────────────────────────

    @Test
    void sendPrompt_emptyChoices_throwsAiResponseException() {
        harness.stubEmptyResponse();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    @Test
    void sendPrompt_nullContent_throwsAiResponseException() {
        harness.stubValidCompletion(null);

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    @Test
    void sendPrompt_blankContent_throwsAiResponseException() {
        harness.stubValidCompletion("   ");

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank();
    }

    @Test
    void sendPrompt_malformedJson_throwsAiResponseExceptionWithoutLeakingInternals() {
        harness.stubMalformedJson();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .isInstanceOf(AiResponseException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("RestClientException")
                .doesNotContain("GroqChatResponse");
    }

    @Test
    void sendPrompt_realisticResponseWithExtraFields_returnsContent() {
        harness.stubRealisticCompletion("Hello from Groq!");

        String result = harness.client().sendPrompt("hi");

        assertThat(result).isEqualTo("Hello from Groq!");
        harness.verify();
    }

    // ── blank API key ─────────────────────────────────────────────────────────

    @Test
    void sendPrompt_blankApiKey_throwsAiAuthenticationExceptionBeforeHttpCall() {
        AiProperties noKey = new AiProperties("", GroqTestHarness.BASE_URL, GroqTestHarness.MODEL, 0.7, 30);
        var clientNoKey = new com.psybergate.recruitment.ai.client.GroqClient(noKey, RestClient.create());

        assertThatThrownBy(() -> clientNoKey.sendPrompt("hi"))
                .isInstanceOf(AiAuthenticationException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .isNotBlank()
                .doesNotContain("Groq");

        harness.verify();
    }

    // ── message safety ────────────────────────────────────────────────────────

    @Test
    void sendPrompt_401_messageDoesNotContainApiKey() {
        harness.stubUnauthorized();

        assertThatThrownBy(() -> harness.client().sendPrompt("hi"))
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain(GroqTestHarness.API_KEY);
    }
}
