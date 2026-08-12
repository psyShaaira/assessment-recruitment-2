package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.ai.client.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceImplTest {

    @Mock
    private AiClient aiClient;

    private AiServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiServiceImpl(aiClient);
    }

    @Test
    void prompt_validPrompt_delegatesToClientAndReturnsResult() {
        when(aiClient.sendPrompt("Hello")).thenReturn("Hi there");

        String result = service.prompt("Hello");

        assertThat(result).isEqualTo("Hi there");
        verify(aiClient).sendPrompt("Hello");
    }

    @Test
    void prompt_nullPrompt_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.prompt(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prompt must not be null or blank");
        verifyNoInteractions(aiClient);
    }

    @Test
    void prompt_blankPromptSpaces_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.prompt("   "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }

    @Test
    void prompt_blankPromptTabs_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.prompt("\t\t"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }

    @Test
    void prompt_blankPromptNewlines_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.prompt("\n\n"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }

    @Test
    void prompt_clientThrowsAiAuthenticationException_propagatesUnchanged() {
        AiAuthenticationException ex = new AiAuthenticationException("auth failed");
        when(aiClient.sendPrompt("test")).thenThrow(ex);

        assertThatThrownBy(() -> service.prompt("test"))
                .isSameAs(ex);
    }

    @Test
    void prompt_clientThrowsAiCommunicationException_propagatesUnchanged() {
        AiCommunicationException ex = new AiCommunicationException("connection error");
        when(aiClient.sendPrompt("test")).thenThrow(ex);

        assertThatThrownBy(() -> service.prompt("test"))
                .isSameAs(ex);
    }

    @Test
    void prompt_clientThrowsAiTimeoutException_propagatesUnchanged() {
        AiTimeoutException ex = new AiTimeoutException("timed out");
        when(aiClient.sendPrompt("test")).thenThrow(ex);

        assertThatThrownBy(() -> service.prompt("test"))
                .isSameAs(ex);
    }

    @Test
    void prompt_clientThrowsAiRateLimitException_propagatesUnchanged() {
        AiRateLimitException ex = new AiRateLimitException("rate limited");
        when(aiClient.sendPrompt("test")).thenThrow(ex);

        assertThatThrownBy(() -> service.prompt("test"))
                .isSameAs(ex);
    }

    @Test
    void prompt_clientThrowsAiResponseException_propagatesUnchanged() {
        AiResponseException ex = new AiResponseException("bad response");
        when(aiClient.sendPrompt("test")).thenThrow(ex);

        assertThatThrownBy(() -> service.prompt("test"))
                .isSameAs(ex);
    }

    // ── promptForJson ─────────────────────────────────────────────────────────

    @Test
    void promptForJson_validPrompt_delegatesToClientSendPromptForJson() {
        when(aiClient.sendPromptForJson("Hello")).thenReturn("{\"result\":\"ok\"}");

        String result = service.promptForJson("Hello");

        assertThat(result).isEqualTo("{\"result\":\"ok\"}");
        verify(aiClient).sendPromptForJson("Hello");
        verify(aiClient, never()).sendPrompt(anyString());
    }

    @Test
    void promptForJson_nullPrompt_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.promptForJson(null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }

    @Test
    void promptForJson_blankPrompt_throwsIllegalArgumentExceptionWithoutCallingClient() {
        assertThatThrownBy(() -> service.promptForJson("  "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }
}
