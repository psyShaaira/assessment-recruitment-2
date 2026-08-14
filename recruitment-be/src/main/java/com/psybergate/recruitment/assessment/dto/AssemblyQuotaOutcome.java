package com.psybergate.recruitment.assessment.dto;

import java.util.List;

/**
 * {@code shortfall} is {@code quota.count() - suggested.size()} — the number of
 * questions this quota couldn't be filled with because the bank doesn't have enough
 * matching questions. Never rejected outright: v1 is a suggestion, not a hard draw.
 */
public record AssemblyQuotaOutcome(
        AssemblyQuotaDto quota,
        List<SuggestedAssemblyQuestionDto> suggested,
        int shortfall
) {}
