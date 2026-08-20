package com.psybergate.recruitment.flag.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flagging_risk_assessments")
@Getter
@Setter
@NoArgsConstructor
public class FlaggingRiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false, unique = true)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel risk;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reasons;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rationale;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "prompt_version", nullable = false, length = 10)
    private String promptVersion;

    @Column(name = "flag_created", nullable = false)
    private boolean flagCreated;
}
