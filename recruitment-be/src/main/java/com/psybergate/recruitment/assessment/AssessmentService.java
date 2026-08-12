package com.psybergate.recruitment.assessment;

import com.psybergate.recruitment.assessment.dto.*;

import java.util.List;
import java.util.UUID;

public interface AssessmentService {

    AssessmentDetailResponse create(AssessmentRequest request, UUID createdById);

    List<AssessmentSummaryResponse> findAll();

    AssessmentDetailResponse findById(UUID id);

    AssessmentDetailResponse update(UUID id, AssessmentRequest request);

    void delete(UUID id);

    AssessmentDetailResponse publish(UUID id);

    AddQuestionResult addQuestion(UUID assessmentId, AddAssessmentQuestionRequest request);

    void removeQuestion(UUID assessmentId, UUID questionId);

    AssessmentPreviewResponse getPreview(UUID assessmentId);

    AssessmentDetailResponse reorderQuestions(UUID assessmentId, ReorderAssessmentQuestionsRequest request);

    boolean verifyAccessPassword(UUID assessmentId, String rawPassword);

    AssemblySuggestionResponse suggestQuestions(AssemblySuggestionRequest request);
}
