package com.psybergate.recruitment.take;

import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.repository.*;
import java.util.ArrayList;
import com.psybergate.recruitment.take.dto.*;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
@RequiredArgsConstructor
public class CandidateTakeServiceImpl implements CandidateTakeService {

    private static final int MAX_TEXT_LENGTH = 65_535;

    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;
    private final CandidateSubmissionRepository submissionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final AnswerScoreRepository answerScoreRepository;
    private final InvitationRepository invitationRepository;
    private final com.psybergate.recruitment.repository.SubmissionQuestionSnapshotRepository snapshotRepository;
    private final com.psybergate.recruitment.marking.MarkingService markingService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public AssessmentTakeResponse loadAssessment(UUID candidateId, UUID assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);

        CandidateSubmission submission = submissionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElse(null);

        if (submission != null && isLocked(submission)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This assessment has already been submitted");
        }

        if (submission == null) {
            CandidateInvitation invitation = invitationRepository
                    .findByCandidate_IdAndAssessment_Id(candidateId, assessmentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "No invitation found for this candidate and assessment"));

            if (invitation.getStatus() == InvitationStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This invitation has been cancelled");
            }

            submission = new CandidateSubmission();
            submission.setCandidateId(candidateId);
            submission.setAssessmentId(assessmentId);
            submission.setInvitationId(invitation.getId());
            submission.setStartedAt(Instant.now());
            submission = submissionRepository.save(submission);
        }

        List<AssessmentQuestion> aqList = resolveQuestions(assessment, submission.getId());

        List<TakeQuestionDto> questions = aqList.stream()
                .map(aq -> toTakeQuestion(aq))
                .toList();

        List<CandidateAnswer> savedAnswers = answerRepository.findBySubmissionId(submission.getId());
        List<TakeAnswerDto> answers = savedAnswers.stream()
                .map(this::toAnswerDto)
                .toList();

        Instant deadline = submission.getStartedAt()
                .plusSeconds((long) assessment.getTimeLimitMinutes() * 60);

        return new AssessmentTakeResponse(
                assessment.getId(),
                assessment.getTitle(),
                assessment.getDescription(),
                aqList.size(),
                submission.getStartedAt(),
                deadline,
                questions,
                answers
        );
    }

    @Override
    @Transactional
    public SaveAnswersResponse saveAnswers(UUID candidateId, UUID assessmentId, SaveAnswersRequest request) {
        CandidateSubmission submission = requireActiveSubmission(candidateId, assessmentId);
        Assessment assessment = requireAssessment(assessmentId);
        checkDeadline(submission, assessment);

        // Collect valid question IDs: top-level IDs plus sub-question IDs from GROUP questions.
        // Candidates answer GROUP sub-questions by sub-question id, not the group id itself.
        Set<UUID> validQuestionIds = new HashSet<>();
        for (AssessmentQuestion aq : assessmentQuestionRepository
                .findByAssessmentIdOrderByDisplayOrder(assessmentId)) {
            Question q = (Question) Hibernate.unproxy(aq.getQuestion());
            validQuestionIds.add(q.getId());
            if (q instanceof GroupQuestion gq) {
                gq.getMembers().forEach(m -> validQuestionIds.add(m.getQuestion().getId()));
            }
        }

        List<TakeAnswerDto> results = new ArrayList<>();

        for (AnswerInput input : request.answers()) {
            if (!validQuestionIds.contains(input.questionId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Question " + input.questionId() + " does not belong to this assessment");
            }

            if (input.textContent() != null && input.textContent().length() > MAX_TEXT_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Answer text exceeds the maximum allowed length of " + MAX_TEXT_LENGTH + " characters");
            }

            if (input.selectedOptionIds() != null && !input.selectedOptionIds().isEmpty()) {
                validateOptionIds(input.questionId(), input.selectedOptionIds());
            }

            CandidateAnswer answer = answerRepository
                    .findBySubmissionIdAndQuestionId(submission.getId(), input.questionId())
                    .orElseGet(() -> {
                        CandidateAnswer a = new CandidateAnswer();
                        a.setSubmissionId(submission.getId());
                        a.setQuestionId(input.questionId());
                        return a;
                    });

            answer.setSelectedOptionIds(serializeOptionIds(input.selectedOptionIds()));
            answer.setTextContent(input.textContent());
            answer.setSavedAt(Instant.now());

            results.add(toAnswerDto(answerRepository.save(answer)));
        }

        return new SaveAnswersResponse(results);
    }

    @Override
    @Transactional
    public SubmitResponse submitAssessment(UUID candidateId, UUID assessmentId, boolean autoSubmitted) {
        Assessment assessment = requireAssessment(assessmentId);

        CandidateSubmission submission = submissionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active submission found"));

        if (isLocked(submission)) {
            return buildSubmitResponse(submission, assessment);
        }

        SubmissionStatus newStatus = autoSubmitted ? SubmissionStatus.AUTO_SUBMITTED : SubmissionStatus.SUBMITTED;
        submission.setStatus(newStatus);
        submission.setSubmittedAt(Instant.now());
        submissionRepository.save(submission);

        invitationRepository.findByCandidate_IdAndAssessment_Id(candidateId, assessmentId)
                .ifPresent(inv -> {
                    inv.setStatus(InvitationStatus.COMPLETED);
                    invitationRepository.save(inv);
                });

        // Auto-mark MCQ answers within the same transaction
        markingService.autoMarkMcq(submission.getId());

        // Zero-score any questions the candidate left unanswered
        scoreUnansweredQuestions(submission.getId(), assessmentId);

        // Publish event for async post-processing (e.g. AI auto-flagging)
        eventPublisher.publishEvent(new SubmissionCompletedEvent(submission.getId(), submission.getAssessmentId()));

        return buildSubmitResponse(submission, assessment);
    }

    @Override
    @Transactional(readOnly = true)
    public ClarificationTarget resolveQuestionForClarification(UUID candidateId, UUID assessmentId, UUID questionId) {
        Assessment assessment = requireAssessment(assessmentId);
        CandidateSubmission submission = requireActiveSubmission(candidateId, assessmentId);
        checkDeadline(submission, assessment);

        List<TakeQuestionDto> questions = resolveQuestions(assessment, submission.getId()).stream()
                .map(this::toTakeQuestion)
                .toList();

        TakeQuestionDto match = findQuestion(questions, questionId);
        if (match == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Question " + questionId + " does not belong to this assessment");
        }

        return new ClarificationTarget(submission.getId(), match);
    }

    private TakeQuestionDto findQuestion(List<TakeQuestionDto> questions, UUID questionId) {
        for (TakeQuestionDto q : questions) {
            if (q.id().equals(questionId)) {
                return q;
            }
            if (q.subQuestions() != null) {
                TakeQuestionDto sub = findQuestion(q.subQuestions(), questionId);
                if (sub != null) {
                    return sub;
                }
            }
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<AssessmentQuestion> resolveQuestions(Assessment assessment, UUID submissionId) {
        List<AssessmentQuestion> allAqs = assessmentQuestionRepository
                .findByAssessmentIdOrderByDisplayOrder(assessment.getId());

        if (!assessment.isRandomiseQuestions()) {
            return allAqs;
        }

        // Check for an existing snapshot (resume path)
        if (snapshotRepository.existsBySubmissionId(submissionId)) {
            List<com.psybergate.recruitment.domain.SubmissionQuestionSnapshot> snapshots =
                    snapshotRepository.findBySubmissionIdOrderByDisplayOrder(submissionId);
            Set<UUID> snappedIds = snapshots.stream()
                    .map(com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getQuestionId)
                    .collect(Collectors.toSet());
            Map<UUID, Integer> orderMap = snapshots.stream().collect(
                    Collectors.toMap(
                            com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getQuestionId,
                            com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getDisplayOrder
                    )
            );
            return allAqs.stream()
                    .filter(aq -> snappedIds.contains(aq.getQuestion().getId()))
                    .sorted(Comparator.comparingInt(aq -> orderMap.getOrDefault(aq.getQuestion().getId(), aq.getDisplayOrder())))
                    .toList();
        }

        // First load: draw random subset per quota
        Map<com.psybergate.recruitment.domain.QuestionType, List<AssessmentQuestion>> byType = allAqs.stream()
                .collect(Collectors.groupingBy(aq -> {
                    Question q = (Question) Hibernate.unproxy(aq.getQuestion());
                    return q.getType();
                }));

        List<AssessmentQuestion> selected = new ArrayList<>();
        for (com.psybergate.recruitment.domain.RandomisationQuota quota : assessment.getRandomisationQuotas()) {
            List<AssessmentQuestion> pool = byType.getOrDefault(quota.getQuestionType(), List.of());
            if (quota.getCount() > pool.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Assessment configuration error: quota of " + quota.getCount() +
                        " for type " + quota.getQuestionType() + " exceeds available questions (" + pool.size() + ")");
            }
            List<AssessmentQuestion> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled);
            selected.addAll(shuffled.subList(0, quota.getCount()));
        }

        // Persist snapshot
        int order = 0;
        for (AssessmentQuestion aq : selected) {
            com.psybergate.recruitment.domain.SubmissionQuestionSnapshot snap =
                    new com.psybergate.recruitment.domain.SubmissionQuestionSnapshot();
            snap.setSubmissionId(submissionId);
            snap.setQuestionId(aq.getQuestion().getId());
            snap.setDisplayOrder(order++);
            snapshotRepository.save(snap);
        }

        return selected;
    }

    private Assessment requireAssessment(UUID assessmentId) {
        return assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found"));
    }

    private CandidateSubmission requireActiveSubmission(UUID candidateId, UUID assessmentId) {
        CandidateSubmission submission = submissionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active submission found"));

        if (isLocked(submission)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This assessment has already been submitted");
        }
        return submission;
    }

    private void checkDeadline(CandidateSubmission submission, Assessment assessment) {
        Instant deadline = submission.getStartedAt()
                .plusSeconds((long) assessment.getTimeLimitMinutes() * 60);
        if (Instant.now().isAfter(deadline)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The time limit for this assessment has expired");
        }
    }

    private boolean isLocked(CandidateSubmission submission) {
        return submission.getStatus() == SubmissionStatus.SUBMITTED
                || submission.getStatus() == SubmissionStatus.AUTO_SUBMITTED;
    }

    private void validateOptionIds(UUID questionId, List<UUID> optionIds) {
        Question rawQ = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question not found"));

        Question q = (Question) Hibernate.unproxy(rawQ);
        if (!(q instanceof McqQuestion mcq)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "selectedOptionIds is only valid for MCQ questions");
        }

        Set<UUID> validIds = mcq.getOptions().stream()
                .map(QuestionOption::getId)
                .collect(Collectors.toSet());

        for (UUID optId : optionIds) {
            if (!validIds.contains(optId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Option " + optId + " does not belong to question " + questionId);
            }
        }
    }

    private String serializeOptionIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize option IDs");
        }
    }

    private List<UUID> deserializeOptionIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private TakeQuestionDto toTakeQuestion(AssessmentQuestion aq) {
        Question q = (Question) Hibernate.unproxy(aq.getQuestion());
        return buildTakeQuestionDto(q, aq.getDisplayOrder());
    }

    private TakeQuestionDto buildTakeQuestionDto(Question q, int displayOrder) {
        List<TakeOptionDto> options = null;
        List<TakeQuestionDto> subQuestions = null;

        if (q instanceof McqQuestion mcq) {
            options = mcq.getOptions().stream()
                    .map(o -> new TakeOptionDto(o.getId(), o.getOptionText()))
                    .toList();
        } else if (q instanceof GroupQuestion gq) {
            // GROUP: body is the preamble; members become sub-questions answered individually.
            subQuestions = new ArrayList<>();
            for (int i = 0; i < gq.getMembers().size(); i++) {
                GroupQuestionMember m = gq.getMembers().get(i);
                Question sub = (Question) Hibernate.unproxy(m.getQuestion());
                subQuestions.add(buildTakeQuestionDto(sub, i));
            }
        }

        return new TakeQuestionDto(
                q.getId(),
                displayOrder,
                q.getType(),
                q.getTitle(),
                q.getBody(),
                q.getMaxScore(),
                options,
                subQuestions
        );
    }

    private TakeAnswerDto toAnswerDto(CandidateAnswer answer) {
        return new TakeAnswerDto(
                answer.getQuestionId(),
                deserializeOptionIds(answer.getSelectedOptionIds()),
                answer.getTextContent(),
                answer.getSavedAt()
        );
    }

    private void scoreUnansweredQuestions(UUID submissionId, UUID assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);
        Set<UUID> answeredIds = answerRepository.findQuestionIdsBySubmissionId(submissionId);

        List<AssessmentQuestion> effective = resolveQuestions(assessment, submissionId);
        for (AssessmentQuestion aq : effective) {
            Question q = (Question) Hibernate.unproxy(aq.getQuestion());
            if (q instanceof GroupQuestion gq) {
                for (GroupQuestionMember m : gq.getMembers()) {
                    scoreIfUnanswered(submissionId, m.getQuestion().getId(), answeredIds);
                }
            } else {
                scoreIfUnanswered(submissionId, q.getId(), answeredIds);
            }
        }
    }

    private void scoreIfUnanswered(UUID submissionId, UUID questionId, Set<UUID> answeredIds) {
        if (answeredIds.contains(questionId)) return;

        CandidateAnswer answer = answerRepository
                .findBySubmissionIdAndQuestionId(submissionId, questionId)
                .orElseGet(() -> {
                    CandidateAnswer a = new CandidateAnswer();
                    a.setSubmissionId(submissionId);
                    a.setQuestionId(questionId);
                    a.setSavedAt(Instant.now());
                    a.setDraft(false);
                    return answerRepository.save(a);
                });

        if (answerScoreRepository.findByCandidateAnswerId(answer.getId()).isEmpty()) {
            AnswerScore score = new AnswerScore();
            score.setCandidateAnswerId(answer.getId());
            score.setScore(0);
            score.setFeedback("Not answered");
            score.setAutoMarked(true);
            score.setMarkedAt(Instant.now());
            answerScoreRepository.save(score);
        }
    }

    private SubmitResponse buildSubmitResponse(CandidateSubmission submission, Assessment assessment) {
        int answeredCount = answerRepository.findBySubmissionId(submission.getId()).size();
        int total = resolveQuestions(assessment, submission.getId()).size();
        return new SubmitResponse(
                submission.getId(),
                assessment.getTitle(),
                submission.getStatus(),
                submission.getSubmittedAt(),
                answeredCount,
                total
        );
    }
}
