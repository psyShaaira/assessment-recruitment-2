package com.psybergate.recruitment.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroqChoice(GroqMessage message) {
}
