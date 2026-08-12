package com.psybergate.recruitment.feedback.dto;

import java.util.List;

public record FeedbackReportContent(
        String overallSummary,
        List<FeedbackTopicDto> topics,
        List<String> nextSteps
) {}
