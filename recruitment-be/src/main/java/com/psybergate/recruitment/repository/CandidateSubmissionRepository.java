package com.psybergate.recruitment.repository;

import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.domain.SubmissionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateSubmissionRepository extends JpaRepository<CandidateSubmission, UUID> {

    Optional<CandidateSubmission> findByCandidateIdAndAssessmentId(UUID candidateId, UUID assessmentId);
    boolean existsByCandidateIdAndAssessmentIdAndStatusIn(UUID candidateId, UUID assessmentId, List<SubmissionStatus> statuses);
    Optional<CandidateSubmission> findByInvitationId(UUID invitationId);

    List<CandidateSubmission> findByAssessmentId(UUID assessmentId);

    List<CandidateSubmission> findByCandidateId(UUID candidateId);

    List<CandidateSubmission> findAll();

    List<CandidateSubmission> findByStatusIn(List<SubmissionStatus> statuses);

    long countByStatus(SubmissionStatus status);

    @Query("""
            SELECT COUNT(cs) FROM CandidateSubmission cs
            WHERE cs.status IN :statuses
            AND EXISTS (
                SELECT ca FROM CandidateAnswer ca
                WHERE ca.submissionId = cs.id
                AND NOT EXISTS (SELECT s FROM AnswerScore s WHERE s.candidateAnswerId = ca.id)
            )
            """)
    long countPendingReviews(@Param("statuses") List<SubmissionStatus> statuses);

    @Query("""
            SELECT COUNT(cs) FROM CandidateSubmission cs
            WHERE cs.status IN :statuses
            AND EXISTS (SELECT ca FROM CandidateAnswer ca WHERE ca.submissionId = cs.id)
            AND NOT EXISTS (
                SELECT ca FROM CandidateAnswer ca
                WHERE ca.submissionId = cs.id
                AND NOT EXISTS (SELECT s FROM AnswerScore s WHERE s.candidateAnswerId = ca.id)
            )
            """)
    long countCompleted(@Param("statuses") List<SubmissionStatus> statuses);

    @Query("SELECT cs FROM CandidateSubmission cs WHERE cs.status IN :statuses ORDER BY cs.createdAt DESC")
    List<CandidateSubmission> findRecentByStatusIn(@Param("statuses") List<SubmissionStatus> statuses, Pageable pageable);

    @Query("SELECT cs FROM CandidateSubmission cs WHERE cs.assessmentId = :assessmentId AND cs.status IN :statuses AND cs.id <> :excludeId")
    List<CandidateSubmission> findByAssessmentIdAndStatusInAndIdNot(
            @Param("assessmentId") UUID assessmentId,
            @Param("statuses") List<SubmissionStatus> statuses,
            @Param("excludeId") UUID excludeId);
}
