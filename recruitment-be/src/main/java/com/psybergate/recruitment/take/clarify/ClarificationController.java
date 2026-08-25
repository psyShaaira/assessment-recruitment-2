package com.psybergate.recruitment.take.clarify;

import com.psybergate.recruitment.take.clarify.dto.ClarificationRequestDto;
import com.psybergate.recruitment.take.clarify.dto.ClarificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/take")
@PreAuthorize("hasRole('CANDIDATE')")
@RequiredArgsConstructor
public class ClarificationController {

    private final ClarificationService clarificationService;

    @PostMapping("/clarify")
    public ResponseEntity<ClarificationResponse> clarify(
            @RequestBody @Valid ClarificationRequestDto request,
            Authentication auth) {
        UUID candidateId = UUID.fromString(auth.getName());
        UUID assessmentId = UUID.fromString((String) auth.getCredentials());
        return ResponseEntity.ok(clarificationService.clarify(candidateId, assessmentId, request));
    }
}
