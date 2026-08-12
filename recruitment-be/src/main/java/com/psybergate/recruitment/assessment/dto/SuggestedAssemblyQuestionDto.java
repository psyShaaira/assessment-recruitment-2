package com.psybergate.recruitment.assessment.dto;

import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.QuestionType;

import java.util.List;
import java.util.UUID;

public record SuggestedAssemblyQuestionDto(
        UUID id,
        QuestionType type,
        String title,
        Difficulty difficulty,
        List<String> tags
) {}
