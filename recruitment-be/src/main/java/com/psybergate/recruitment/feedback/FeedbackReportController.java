package com.psybergate.recruitment.feedback;

import com.psybergate.recruitment.feedback.dto.FeedbackReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")
@RequiredArgsConstructor
public class FeedbackReportController {

    private final FeedbackReportService feedbackReportService;

    @PostMapping("/api/submissions/{submissionId}/feedback-report")
    public ResponseEntity<FeedbackReportResponse> generate(
            @PathVariable UUID submissionId,
            Authentication auth) {
        UUID requestedBy = UUID.fromString(auth.getName());
        return ResponseEntity.ok(feedbackReportService.generate(submissionId, requestedBy));
    }

    @GetMapping("/api/submissions/{submissionId}/feedback-report")
    public ResponseEntity<FeedbackReportResponse> getExisting(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(feedbackReportService.getExisting(submissionId));
    }
}
