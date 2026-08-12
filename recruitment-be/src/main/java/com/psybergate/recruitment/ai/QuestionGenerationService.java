package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.question.dto.GenerateQuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;

import java.util.List;

public interface QuestionGenerationService {

    List<QuestionRequest> generate(GenerateQuestionRequest request);
}
