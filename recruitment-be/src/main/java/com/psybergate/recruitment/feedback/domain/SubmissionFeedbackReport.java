package com.psybergate.recruitment.feedback.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submission_feedback_reports")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionFeedbackReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false, unique = true)
    private UUID submissionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated = true;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by")
    private UUID generatedBy;
}
