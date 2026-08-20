package com.psybergate.recruitment.flag.repository;

import com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlaggingRiskAssessmentRepository extends JpaRepository<FlaggingRiskAssessment, UUID> {

    Optional<FlaggingRiskAssessment> findBySubmissionId(UUID submissionId);

    List<FlaggingRiskAssessment> findByRiskIn(List<RiskLevel> riskLevels);

    List<FlaggingRiskAssessment> findBySubmissionIdIn(Collection<UUID> submissionIds);
}
