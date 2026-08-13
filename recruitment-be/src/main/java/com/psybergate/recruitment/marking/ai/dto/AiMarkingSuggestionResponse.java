package com.psybergate.recruitment.marking.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record AiMarkingSuggestionResponse(
        UUID answerId, int score, int maxScore, String rationale, Instant generatedAt) {}
