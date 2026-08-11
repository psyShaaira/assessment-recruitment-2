package com.psybergate.recruitment.ai.client;

import com.psybergate.recruitment.ai.AiAuthenticationException;
import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiProperties;
import com.psybergate.recruitment.ai.AiRateLimitException;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiTimeoutException;
import com.psybergate.recruitment.ai.dto.GroqChatRequest;
import com.psybergate.recruitment.ai.dto.GroqChatResponse;
import com.psybergate.recruitment.ai.dto.GroqMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class GroqClient implements AiClient {

    private final AiProperties properties;
    private final RestClient restClient;

    // Production constructor — builds RestClient from properties
    @Autowired
    public GroqClient(AiProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    // Package-private constructor for unit tests — accepts a pre-built RestClient
    public GroqClient(AiProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public String sendPrompt(String prompt) {
        // Fail at point of use — avoids breaking context startup when key is not configured
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AiAuthenticationException("AI provider is not configured: missing API key");
        }

        long startTime = System.currentTimeMillis();
        log.info("AI request initiated — provider: Groq, model: {}", properties.model());

        GroqChatRequest request = new GroqChatRequest(
                properties.model(),
                List.of(new GroqMessage("user", prompt)),
                properties.temperature()
        );

        try {
            GroqChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiAuthenticationException("AI provider rejected the request due to an authentication failure");
                    })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new AiRateLimitException("AI provider is temporarily unavailable due to rate limiting");
                    })
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        log.error("AI request failed — provider returned server error status: {}", res.getStatusCode().value());
                        throw new AiCommunicationException("The AI provider is currently unavailable");
                    })
                    .body(GroqChatResponse.class);

            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()
                    || response.choices().get(0).message() == null) {
                long elapsed = System.currentTimeMillis() - startTime;
                AiResponseException ex = new AiResponseException("The AI provider returned an unrecognised response structure");
                log.error("AI request failed — type: {}, elapsed: {}ms", ex.getClass().getSimpleName(), elapsed);
                throw ex;
            }

            String content = response.choices().get(0).message().content();
            if (content == null || content.isBlank()) {
                long elapsed = System.currentTimeMillis() - startTime;
                AiResponseException ex = new AiResponseException("The AI provider returned an empty response");
                log.error("AI request failed — type: {}, elapsed: {}ms", ex.getClass().getSimpleName(), elapsed);
                throw ex;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AI request succeeded — provider: Groq, model: {}, elapsed: {}ms", properties.model(), elapsed);
            return content;

        } catch (AiAuthenticationException | AiRateLimitException | AiCommunicationException | AiResponseException ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("AI request failed — type: {}, elapsed: {}ms", ex.getClass().getSimpleName(), elapsed);
            throw ex;
        } catch (ResourceAccessException ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            RuntimeException mapped;
            if (ex.getCause() instanceof SocketTimeoutException) {
                mapped = new AiTimeoutException("The AI provider did not respond in time");
            } else {
                mapped = new AiCommunicationException("Unable to reach the AI provider");
            }
            log.error("AI request failed — type: {}, cause: {}, elapsed: {}ms",
                    mapped.getClass().getSimpleName(), ex.getClass().getSimpleName(), elapsed);
            throw mapped;
        }
    }
}
