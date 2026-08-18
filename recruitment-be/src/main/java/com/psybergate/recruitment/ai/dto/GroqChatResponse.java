package com.psybergate.recruitment.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroqChatResponse(List<GroqChoice> choices) {
}
