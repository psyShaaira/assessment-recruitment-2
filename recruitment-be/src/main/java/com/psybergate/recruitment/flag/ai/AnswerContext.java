package com.psybergate.recruitment.flag.ai;

import java.time.Instant;

public record AnswerContext(
    String questionTitle,
    String questionType,
    String difficulty,
    int maxScore,
    String answerContent,
    Instant savedAt,
    long secondsSinceStart
) {}
