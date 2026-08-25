package com.psybergate.recruitment.take.clarify.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clarification_requests")
@Getter
@Setter
@NoArgsConstructor
public class ClarificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "candidate_note", columnDefinition = "TEXT")
    private String candidateNote;

    @Column(name = "clarification_response", columnDefinition = "TEXT", nullable = false)
    private String clarificationResponse;

    @Column(name = "prompt_version", nullable = false, length = 16)
    private String promptVersion;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
}
