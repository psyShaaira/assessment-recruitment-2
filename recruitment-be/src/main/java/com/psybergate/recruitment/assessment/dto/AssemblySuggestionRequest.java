package com.psybergate.recruitment.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssemblySuggestionRequest(
        @NotEmpty @Valid List<AssemblyQuotaDto> quotas
) {}
