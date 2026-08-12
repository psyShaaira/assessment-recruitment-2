package com.psybergate.recruitment.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroqChatRequest(
        String model,
        List<GroqMessage> messages,
        double temperature,
        @JsonProperty("response_format") Map<String, String> responseFormat
) {
    /** Convenience constructor for plain text responses (no response_format constraint). */
    public GroqChatRequest(String model, List<GroqMessage> messages, double temperature) {
        this(model, messages, temperature, null);
    }

    /** Returns a copy of this request constrained to JSON object output. */
    public GroqChatRequest withJsonObjectFormat() {
        return new GroqChatRequest(model, messages, temperature, Map.of("type", "json_object"));
    }
}
