package com.psybergate.recruitment.question.dto;

import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.QuestionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GenerateQuestionRequest(
        @NotNull QuestionType type,
        @NotBlank String topic,
        @NotNull Difficulty difficulty,
        @Min(1) @Max(5) int count
) {}
