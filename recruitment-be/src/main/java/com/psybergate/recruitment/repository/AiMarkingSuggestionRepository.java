package com.psybergate.recruitment.repository;

import com.psybergate.recruitment.domain.AiMarkingSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiMarkingSuggestionRepository extends JpaRepository<AiMarkingSuggestion, UUID> {

    Optional<AiMarkingSuggestion> findByCandidateAnswerId(UUID candidateAnswerId);
}
