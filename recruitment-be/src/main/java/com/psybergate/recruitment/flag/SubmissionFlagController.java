package com.psybergate.recruitment.flag;

import com.psybergate.recruitment.domain.FlagStatus;
import com.psybergate.recruitment.flag.ai.AiFlaggingService;
import com.psybergate.recruitment.flag.ai.dto.RiskAssessmentResponse;
import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
@RequiredArgsConstructor
public class SubmissionFlagController {

    private final SubmissionFlagService flagService;
    private final AiFlaggingService aiFlaggingService;

    /** 4.1 — Create flag */
    @PostMapping("/api/submissions/{submissionId}/flags")
    public ResponseEntity<FlagResponse> createFlag(
            @PathVariable UUID submissionId,
            @RequestBody @Valid CreateFlagRequest request,
            Authentication auth) {
        UUID actorId = UUID.fromString(auth.getName());
        String actorUsername = actorId.toString();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flagService.createFlag(submissionId, request.reason(), actorId, actorUsername));
    }

    /** 4.2 — Transition flag */
    @PatchMapping("/api/submissions/{submissionId}/flags/{flagId}")
    public ResponseEntity<FlagResponse> transitionFlag(
            @PathVariable UUID submissionId,
            @PathVariable UUID flagId,
            @RequestBody @Valid TransitionFlagRequest request,
            Authentication auth) {
        UUID actorId = UUID.fromString(auth.getName());
        String actorUsername = actorId.toString();
        return ResponseEntity.ok(
                flagService.transitionFlag(submissionId, flagId, request.status(),
                        request.resolutionNotes(), actorId, actorUsername));
    }

    /** 4.3 — Audit trail */
    @GetMapping("/api/submissions/{submissionId}/flags/{flagId}/audit")
    public ResponseEntity<List<FlagAuditResponse>> getAuditTrail(
            @PathVariable UUID submissionId,
            @PathVariable UUID flagId) {
        return ResponseEntity.ok(flagService.getAuditTrail(submissionId, flagId));
    }

    /** 4.4 — Candidate flag history */
    @GetMapping("/api/candidates/{candidateId}/flags")
    public ResponseEntity<List<FlagListItemResponse>> getCandidateFlags(@PathVariable UUID candidateId) {
        return ResponseEntity.ok(flagService.getFlagsForCandidate(candidateId));
    }

    /** 4.5 — All flags with filters */
    @GetMapping("/api/flags")
    public ResponseEntity<List<FlagListItemResponse>> getAllFlags(
            @RequestParam(required = false) FlagReason reason,
            @RequestParam(required = false) UUID assessmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(flagService.getAllFlags(reason, assessmentId, fromDate, toDate));
    }

    /** 5.1 — AI risk assessment for a submission */
    @GetMapping("/api/submissions/{submissionId}/risk-assessment")
    public ResponseEntity<RiskAssessmentResponse> getRiskAssessment(@PathVariable UUID submissionId) {
        return aiFlaggingService.getRiskAssessment(submissionId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No risk assessment found for submission " + submissionId));
    }
}
