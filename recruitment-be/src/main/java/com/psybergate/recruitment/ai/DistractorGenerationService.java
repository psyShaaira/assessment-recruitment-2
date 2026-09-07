package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.question.dto.GenerateDistractorsRequest;
import com.psybergate.recruitment.question.dto.QuestionOptionRequest;

import java.util.List;

public interface DistractorGenerationService {
    List<QuestionOptionRequest> generateDistractors(GenerateDistractorsRequest request);
}
