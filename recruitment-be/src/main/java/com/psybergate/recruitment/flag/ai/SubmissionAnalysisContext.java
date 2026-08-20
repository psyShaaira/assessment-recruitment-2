package com.psybergate.recruitment.flag.ai;

import java.util.List;
import java.util.UUID;

public record SubmissionAnalysisContext(
    UUID submissionId,
    UUID assessmentId,
    String assessmentTitle,
    int timeLimitMinutes,
    long actualDurationSeconds,
    int questionCount,
    List<AnswerContext> answers
) {}
