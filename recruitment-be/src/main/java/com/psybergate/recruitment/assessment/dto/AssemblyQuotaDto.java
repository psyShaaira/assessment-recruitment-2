package com.psybergate.recruitment.assessment.dto;

import com.psybergate.recruitment.domain.Difficulty;
import jakarta.validation.constraints.Min;

public record AssemblyQuotaDto(
        String tag,
        Difficulty difficulty,
        @Min(1) int count
) {}
