package com.psybergate.recruitment.take.clarify.dto;

public record ClarificationResponse(
        String clarification,
        int remainingForQuestion,
        int remainingForAssessment,
        boolean degraded
) {
    private static final String DEGRADED_MESSAGE =
            "Clarification is temporarily unavailable — please answer to the best of your understanding.";

    public static ClarificationResponse degraded(int remainingForQuestion, int remainingForAssessment) {
        return new ClarificationResponse(DEGRADED_MESSAGE, remainingForQuestion, remainingForAssessment, true);
    }
}
