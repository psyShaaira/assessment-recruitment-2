package com.psybergate.recruitment.question.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GenerateDistractorsRequest(
        @NotBlank String questionBody,
        @NotBlank String correctAnswer,
        @Min(1) @Max(5) int count
) {}
