package com.psybergate.recruitment.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroqMessage(String role, String content) {
}
