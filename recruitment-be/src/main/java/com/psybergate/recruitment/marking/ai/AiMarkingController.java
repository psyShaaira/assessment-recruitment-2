package com.psybergate.recruitment.marking.ai;

import com.psybergate.recruitment.marking.ai.dto.AiMarkingSuggestionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")
@RequiredArgsConstructor
public class AiMarkingController {

    private final AiMarkingService aiMarkingService;

    /** Generate (or regenerate) an AI-assisted marking suggestion for an answer */
    @PostMapping("/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion")
    public ResponseEntity<AiMarkingSuggestionResponse> generateSuggestion(
            @PathVariable UUID submissionId,
            @PathVariable UUID questionId) {
        return ResponseEntity.ok(aiMarkingService.generateSuggestion(submissionId, questionId));
    }

    /** Retrieve the most recently generated AI-assisted marking suggestion for an answer */
    @GetMapping("/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion")
    public ResponseEntity<AiMarkingSuggestionResponse> getSuggestion(
            @PathVariable UUID submissionId,
            @PathVariable UUID questionId) {
        return ResponseEntity.ok(aiMarkingService.getSuggestion(submissionId, questionId));
    }
}
