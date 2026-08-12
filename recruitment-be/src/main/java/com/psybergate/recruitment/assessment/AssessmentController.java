package com.psybergate.recruitment.assessment;

import com.psybergate.recruitment.assessment.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/assessments")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @PostMapping
    public ResponseEntity<AssessmentDetailResponse> create(@RequestBody @Valid AssessmentRequest request,
                                                           Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        return ResponseEntity.status(HttpStatus.CREATED).body(assessmentService.create(request, userId));
    }

    @GetMapping
    public ResponseEntity<List<AssessmentSummaryResponse>> list() {
        return ResponseEntity.ok(assessmentService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AssessmentDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(assessmentService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AssessmentDetailResponse> update(@PathVariable UUID id,
                                                           @RequestBody @Valid AssessmentRequest request) {
        return ResponseEntity.ok(assessmentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        assessmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/publish")
    public ResponseEntity<AssessmentDetailResponse> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(assessmentService.publish(id));
    }

    @PostMapping("/{id}/questions")
    public ResponseEntity<AssessmentDetailResponse> addQuestion(@PathVariable UUID id,
                                                                @RequestBody @Valid AddAssessmentQuestionRequest request) {
        AddQuestionResult result = assessmentService.addQuestion(id, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.assessment());
    }

    @PutMapping("/{id}/questions/order")
    public ResponseEntity<AssessmentDetailResponse> reorderQuestions(@PathVariable UUID id,
                                                                      @RequestBody @Valid ReorderAssessmentQuestionsRequest request) {
        return ResponseEntity.ok(assessmentService.reorderQuestions(id, request));
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> removeQuestion(@PathVariable UUID id, @PathVariable UUID questionId) {
        assessmentService.removeQuestion(id, questionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/suggest-questions")
    public ResponseEntity<AssemblySuggestionResponse> suggestQuestions(
            @RequestBody @Valid AssemblySuggestionRequest request) {
        return ResponseEntity.ok(assessmentService.suggestQuestions(request));
    }

    @GetMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER','CANDIDATE')")
    public ResponseEntity<AssessmentPreviewResponse> preview(@PathVariable UUID id,
                                                              Authentication auth) {
        // Candidates may only preview the specific assessment in their session token
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE"))) {
            Object credentials = auth.getCredentials();
            String tokenAssessmentId = credentials != null ? credentials.toString() : null;
            if (!id.toString().equals(tokenAssessmentId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        return ResponseEntity.ok(assessmentService.getPreview(id));
    }
}
