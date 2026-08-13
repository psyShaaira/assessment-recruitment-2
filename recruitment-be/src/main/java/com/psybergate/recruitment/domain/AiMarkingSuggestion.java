package com.psybergate.recruitment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_marking_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class AiMarkingSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "candidate_answer_id", nullable = false, unique = true)
    private UUID candidateAnswerId;

    private int score;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rationale;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
