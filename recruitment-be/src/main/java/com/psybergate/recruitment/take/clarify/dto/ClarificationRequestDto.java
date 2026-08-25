package com.psybergate.recruitment.take.clarify.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ClarificationRequestDto(
        @NotNull UUID questionId,
        @Size(max = 500) String candidateNote
) {}
