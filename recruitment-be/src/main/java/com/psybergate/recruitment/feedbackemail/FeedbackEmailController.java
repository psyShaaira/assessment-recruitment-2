package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendLogDto;
import com.psybergate.recruitment.feedbackemail.dto.FeedbackEmailSendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")
@RequiredArgsConstructor
public class FeedbackEmailController {

    private final FeedbackEmailService feedbackEmailService;

    @PostMapping("/api/submissions/{submissionId}/feedback-report/email")
    public ResponseEntity<FeedbackEmailSendResponse> send(
            @PathVariable UUID submissionId,
            Authentication auth) {
        UUID sentBy = UUID.fromString(auth.getName());
        return ResponseEntity.ok(feedbackEmailService.sendFeedbackEmail(submissionId, sentBy));
    }

    @GetMapping("/api/submissions/{submissionId}/feedback-report/email")
    public ResponseEntity<List<FeedbackEmailSendLogDto>> history(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(feedbackEmailService.getSendHistory(submissionId));
    }
}
