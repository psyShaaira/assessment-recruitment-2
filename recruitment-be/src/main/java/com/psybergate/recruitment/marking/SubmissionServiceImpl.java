package com.psybergate.recruitment.marking;

import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.flag.domain.FlaggingRiskAssessment;
import com.psybergate.recruitment.flag.domain.RiskLevel;
import com.psybergate.recruitment.flag.repository.FlaggingRiskAssessmentRepository;
import com.psybergate.recruitment.marking.dto.*;
import com.psybergate.recruitment.repository.*;
import com.psybergate.recruitment.repository.SubmissionFlagRepository;
import com.psybergate.recruitment.domain.FlagStatus;
import com.psybergate.recruitment.domain.SubmissionFlag;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SubmissionServiceImpl implements SubmissionService {

    private final CandidateSubmissionRepository submissionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final AnswerScoreRepository scoreRepository;
    private final CandidateRepository candidateRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;
    private final com.psybergate.recruitment.repository.SubmissionFlagRepository submissionFlagRepository;
    private final com.psybergate.recruitment.repository.InvitationRepository invitationRepository;
    private final com.psybergate.recruitment.repository.SubmissionQuestionSnapshotRepository snapshotRepository;
    private final FlaggingRiskAssessmentRepository flaggingRiskAssessmentRepository;

    @Override
    public List<SubmissionSummaryResponse> listSubmissions(UUID assessmentId) {
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found"));

        List<CandidateSubmission> submissions = submissionRepository.findByAssessmentId(assessmentId);
        List<SubmissionSummaryResponse> result = new ArrayList<>(buildSummaries(submissions));
        result.addAll(buildNotStartedSummaries(invitationRepository.findSentWithNoSubmissionByAssessment(assessmentId)));
        return result;
    }

    @Override
    public List<SubmissionSummaryResponse> listAllSubmissions() {
        List<SubmissionSummaryResponse> result = new ArrayList<>(buildSummaries(submissionRepository.findAll()));
        result.addAll(buildNotStartedSummaries(invitationRepository.findSentWithNoSubmission()));
        return result;
    }

    @Override
    public List<SubmissionSummaryResponse> listCompletedSubmissions() {
        List<SubmissionStatus> completedStatuses = List.of(SubmissionStatus.SUBMITTED, SubmissionStatus.AUTO_SUBMITTED);
        return buildSummaries(submissionRepository.findByStatusIn(completedStatuses));
    }

    @Override
    @Transactional
    public AnswerScoreResponse scoreAnswer(UUID submissionId, UUID answerId, int score, String feedback, UUID markerId) {
        submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        CandidateAnswer answer = answerRepository.findById(answerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));

        if (!answer.getSubmissionId().equals(submissionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer does not belong to this submission");
        }

        if (score < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score must be non-negative");
        }

        AnswerScore answerScore = scoreRepository.findByCandidateAnswerId(answerId)
                .orElseGet(AnswerScore::new);

        answerScore.setCandidateAnswerId(answerId);
        answerScore.setScore(score);
        answerScore.setFeedback(feedback);
        answerScore.setMarkedBy(markerId);
        answerScore.setMarkedAt(Instant.now());
        answerScore.setAutoMarked(false);

        answerScore = scoreRepository.save(answerScore);

        return new AnswerScoreResponse(
                answerId, answerScore.getScore(), answerScore.getFeedback(),
                answerScore.isAutoMarked(), answerScore.getMarkedBy(), answerScore.getMarkedAt()
        );
    }

    @Override
    @Transactional
    public AnswerScoreResponse scoreByQuestionId(UUID submissionId, UUID questionId, int score, String feedback, UUID markerId) {
        CandidateSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        if (score < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score must be non-negative");
        }

        // Validate questionId belongs to this submission's assessment (top-level or GROUP sub-question)
        List<AssessmentQuestion> aqList = assessmentQuestionRepository
                .findByAssessmentIdOrderByDisplayOrder(submission.getAssessmentId());
        boolean validQuestion = false;
        for (AssessmentQuestion aq : aqList) {
            Question q = (Question) Hibernate.unproxy(aq.getQuestion());
            if (q.getId().equals(questionId)) { validQuestion = true; break; }
            if (q instanceof GroupQuestion gq) {
                for (GroupQuestionMember m : gq.getMembers()) {
                    if (m.getQuestion().getId().equals(questionId)) { validQuestion = true; break; }
                }
            }
            if (validQuestion) break;
        }
        if (!validQuestion) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found in this assessment");
        }

        // Find or create a CandidateAnswer for this question
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

        AnswerScore answerScore = scoreRepository.findByCandidateAnswerId(answer.getId())
                .orElseGet(AnswerScore::new);

        answerScore.setCandidateAnswerId(answer.getId());
        answerScore.setScore(score);
        answerScore.setFeedback(feedback);
        answerScore.setMarkedBy(markerId);
        answerScore.setMarkedAt(Instant.now());
        answerScore.setAutoMarked(false);

        answerScore = scoreRepository.save(answerScore);

        return new AnswerScoreResponse(
                answer.getId(), answerScore.getScore(), answerScore.getFeedback(),
                answerScore.isAutoMarked(), answerScore.getMarkedBy(), answerScore.getMarkedAt()
        );
    }

    @Override
    public ResultSummaryResponse getResult(UUID submissionId) {
        CandidateSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        Assessment assessment = assessmentRepository.findById(submission.getAssessmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found"));

        Candidate candidate = candidateRepository.findById(submission.getCandidateId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found"));

        // Load questions: use snapshot subset for randomised assessments
        List<AssessmentQuestion> aqList = resolveQuestionsForResult(assessment, submissionId);

        // Load all answers for this submission
        List<CandidateAnswer> answers = answerRepository.findBySubmissionId(submissionId);
        Map<UUID, CandidateAnswer> answerByQuestionId = answers.stream()
                .collect(Collectors.toMap(CandidateAnswer::getQuestionId, Function.identity()));

        // Load all scores for these answers
        Set<UUID> answerIds = answers.stream().map(CandidateAnswer::getId).collect(Collectors.toSet());
        Map<UUID, AnswerScore> scoreByAnswerId = answerIds.isEmpty() ? Map.of() :
                scoreRepository.findByCandidateAnswerIdIn(answerIds).stream()
                        .collect(Collectors.toMap(AnswerScore::getCandidateAnswerId, Function.identity()));

        // A question can end up both as its own standalone entry AND as a member of a
        // GROUP question on the same assessment (should be prevented going forward by
        // AssessmentServiceImpl.addQuestion(), but guards pre-existing data too). Skip the
        // standalone entry in that case so its score/max score isn't counted twice.
        Set<UUID> groupMemberQuestionIds = aqList.stream()
                .map(aq -> (Question) Hibernate.unproxy(aq.getQuestion()))
                .filter(GroupQuestion.class::isInstance)
                .map(GroupQuestion.class::cast)
                .flatMap(gq -> gq.getMembers().stream())
                .map(m -> m.getQuestion().getId())
                .collect(Collectors.toSet());

        // Build per-question DTOs
        List<ResultQuestionDto> questionDtos = new ArrayList<>();
        int totalScore = 0;
        int answeredCount = 0;
        boolean fullyMarked = true;

        for (AssessmentQuestion aq : aqList) {
            Question rawQ = (Question) Hibernate.unproxy(aq.getQuestion());

            if (!(rawQ instanceof GroupQuestion) && groupMemberQuestionIds.contains(rawQ.getId())) {
                continue; // already counted as part of its group above
            }

            if (rawQ instanceof GroupQuestion gq) {
                // Expand GROUP into nested sub-question DTOs
                List<ResultQuestionDto> subDtos = new ArrayList<>();
                for (GroupQuestionMember m : gq.getMembers()) {
                    Question subQ = (Question) Hibernate.unproxy(m.getQuestion());
                    CandidateAnswer subAnswer = answerByQuestionId.get(subQ.getId());

                    String subCandidateAnswer = null;
                    Integer subScore = null;
                    String subFeedback = null;
                    boolean subAutoMarked = false;
                    UUID subMarkedBy = null;
                    Instant subMarkedAt = null;

                    if (subAnswer != null) {
                        answeredCount++;
                        subCandidateAnswer = resolveCandidateAnswer(subAnswer, subQ);
                        AnswerScore subAnswerScore = scoreByAnswerId.get(subAnswer.getId());
                        if (subAnswerScore != null) {
                            subScore = subAnswerScore.getScore();
                            subFeedback = subAnswerScore.getFeedback();
                            subAutoMarked = subAnswerScore.isAutoMarked();
                            subMarkedBy = subAnswerScore.getMarkedBy();
                            subMarkedAt = subAnswerScore.getMarkedAt();
                            totalScore += subScore;
                        } else {
                            fullyMarked = false;
                        }
                    } else {
                        fullyMarked = false; // unanswered sub-question: must be scored via questionId endpoint
                    }

                    subDtos.add(new ResultQuestionDto(
                            subQ.getId(),
                            subAnswer != null ? subAnswer.getId() : null,
                            subQ.getTitle(), subQ.getType(),
                            subCandidateAnswer, subScore, subQ.getMaxScore(),
                            subFeedback, subAutoMarked, subMarkedBy, subMarkedAt,
                            null
                    ));
                }

                questionDtos.add(new ResultQuestionDto(
                        rawQ.getId(), null, rawQ.getTitle(), rawQ.getType(),
                        null, null, rawQ.getMaxScore(), null, false, null, null,
                        subDtos
                ));
            } else {
                CandidateAnswer answer = answerByQuestionId.get(rawQ.getId());

                String candidateAnswerText = null;
                Integer score = null;
                String feedback = null;
                boolean autoMarked = false;
                UUID markedBy = null;
                Instant markedAt = null;

                if (answer != null) {
                    answeredCount++;
                    candidateAnswerText = resolveCandidateAnswer(answer, rawQ);

                    AnswerScore answerScore = scoreByAnswerId.get(answer.getId());
                    if (answerScore != null) {
                        score = answerScore.getScore();
                        feedback = answerScore.getFeedback();
                        autoMarked = answerScore.isAutoMarked();
                        markedBy = answerScore.getMarkedBy();
                        markedAt = answerScore.getMarkedAt();
                        totalScore += score;
                    } else {
                        fullyMarked = false;
                    }
                } else {
                    fullyMarked = false; // unanswered: must be scored via questionId endpoint
                }

                questionDtos.add(new ResultQuestionDto(
                        rawQ.getId(),
                        answer != null ? answer.getId() : null,
                        rawQ.getTitle(), rawQ.getType(),
                        candidateAnswerText, score, rawQ.getMaxScore(), feedback, autoMarked, markedBy, markedAt,
                        null
                ));
            }
        }

        String markingStatus = fullyMarked && !aqList.isEmpty() ? "FULLY_MARKED" : "PENDING_REVIEW";

        int maxScore = aqList.stream()
                .map(aq -> (Question) Hibernate.unproxy(aq.getQuestion()))
                .filter(q -> q instanceof GroupQuestion || !groupMemberQuestionIds.contains(q.getId()))
                .mapToInt(Question::getMaxScore)
                .sum();

        // Resolve AI risk level (only MEDIUM/HIGH surfaced)
        RiskLevel aiRiskLevel = flaggingRiskAssessmentRepository.findBySubmissionId(submissionId)
                .map(FlaggingRiskAssessment::getRisk)
                .filter(r -> r == RiskLevel.HIGH || r == RiskLevel.MEDIUM)
                .orElse(null);

        return new ResultSummaryResponse(
                submissionId,
                candidate.getFirstName() + " " + candidate.getLastName(),
                assessment.getTitle(),
                submission.getSubmittedAt(),
                totalScore,
                maxScore,
                answeredCount,
                markingStatus,
                questionDtos,
                aiRiskLevel
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<SubmissionSummaryResponse> buildSummaries(List<CandidateSubmission> submissions) {
        if (submissions.isEmpty()) return List.of();

        Set<UUID> candidateIds = submissions.stream()
                .map(CandidateSubmission::getCandidateId).collect(Collectors.toSet());
        Map<UUID, Candidate> candidateMap = candidateRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(Candidate::getId, Function.identity()));

        // Load answers and scores for all submissions at once
        Set<UUID> submissionIds = submissions.stream()
                .map(CandidateSubmission::getId).collect(Collectors.toSet());
        List<CandidateAnswer> allAnswers = submissionIds.isEmpty() ? List.of() :
                submissionIds.stream()
                        .flatMap(sid -> answerRepository.findBySubmissionId(sid).stream())
                        .toList();

        Map<UUID, Long> answeredBySubmission = allAnswers.stream()
                .collect(Collectors.groupingBy(CandidateAnswer::getSubmissionId, Collectors.counting()));

        Set<UUID> allAnswerIds = allAnswers.stream().map(CandidateAnswer::getId).collect(Collectors.toSet());
        Map<UUID, UUID> submissionByAnswerId = allAnswers.stream()
                .collect(Collectors.toMap(CandidateAnswer::getId, CandidateAnswer::getSubmissionId));
        // Build questionId lookup for each answerId (needed for slot-based marked count)
        Map<UUID, UUID> questionIdByAnswerId = allAnswers.stream()
                .collect(Collectors.toMap(CandidateAnswer::getId, CandidateAnswer::getQuestionId));
        List<AnswerScore> allScores = allAnswerIds.isEmpty() ? List.of() :
                scoreRepository.findByCandidateAnswerIdIn(allAnswerIds);
        // Build set of scored question IDs per submission for slot-based counting
        Map<UUID, Set<UUID>> scoredQIdsBySubmission = new HashMap<>();
        for (AnswerScore as : allScores) {
            UUID sid = submissionByAnswerId.get(as.getCandidateAnswerId());
            UUID qid = questionIdByAnswerId.get(as.getCandidateAnswerId());
            if (sid != null && qid != null) {
                scoredQIdsBySubmission.computeIfAbsent(sid, k -> new HashSet<>()).add(qid);
            }
        }
        Map<UUID, Integer> totalScoreBySubmission = allScores.stream()
                .collect(Collectors.groupingBy(
                        as -> submissionByAnswerId.get(as.getCandidateAnswerId()),
                        Collectors.summingInt(AnswerScore::getScore)
                ));

        // Batch-load assessments
        Set<UUID> assessmentIds = submissions.stream()
                .map(CandidateSubmission::getAssessmentId).collect(Collectors.toSet());
        Map<UUID, Assessment> assessmentById = assessmentIds.isEmpty() ? Map.of() :
                assessmentRepository.findAllById(assessmentIds).stream()
                        .collect(Collectors.toMap(Assessment::getId, Function.identity()));
        Map<UUID, UUID> assessmentBySubmission = submissions.stream()
                .collect(Collectors.toMap(CandidateSubmission::getId, CandidateSubmission::getAssessmentId));

        // Separate randomised vs non-randomised assessment IDs
        Set<UUID> randomisedAssessmentIds = assessmentById.values().stream()
                .filter(Assessment::isRandomiseQuestions)
                .map(Assessment::getId)
                .collect(Collectors.toSet());

        // Batch-load max score for non-randomised assessments
        Set<UUID> nonRandomisedIds = assessmentIds.stream()
                .filter(id -> !randomisedAssessmentIds.contains(id))
                .collect(Collectors.toSet());
        Map<UUID, Integer> questionCountByAssessment = nonRandomisedIds.isEmpty() ? Map.of() :
                assessmentQuestionRepository.sumMaxScoreGroupByAssessmentId(nonRandomisedIds).stream()
                        .collect(Collectors.toMap(
                                row -> (UUID) row[0],
                                row -> ((Long) row[1]).intValue()
                        ));

        // Build slot list per non-randomised assessment
        Map<UUID, List<UUID>> slotQIdsByAssessment = new HashMap<>();
        Map<UUID, Integer> totalAnswerableByAssessment = new HashMap<>();
        for (UUID assessmentId : nonRandomisedIds) {
            List<AssessmentQuestion> aqItems = assessmentQuestionRepository
                    .findByAssessmentIdOrderByDisplayOrder(assessmentId);
            Set<UUID> groupMemberQIds = aqItems.stream()
                    .map(aqItem -> (Question) Hibernate.unproxy(aqItem.getQuestion()))
                    .filter(GroupQuestion.class::isInstance)
                    .map(GroupQuestion.class::cast)
                    .flatMap(gq -> gq.getMembers().stream())
                    .map(m -> m.getQuestion().getId())
                    .collect(Collectors.toSet());
            List<UUID> slots = new ArrayList<>();
            for (AssessmentQuestion aqItem : aqItems) {
                Question q = (Question) Hibernate.unproxy(aqItem.getQuestion());
                if (q instanceof GroupQuestion gq) {
                    Hibernate.initialize(gq.getMembers());
                    gq.getMembers().forEach(m -> slots.add(m.getQuestion().getId()));
                } else if (!groupMemberQIds.contains(q.getId())) {
                    slots.add(q.getId());
                }
            }
            slotQIdsByAssessment.put(assessmentId, slots);
            totalAnswerableByAssessment.put(assessmentId, slots.size());
        }

        // For randomised assessments: compute per-submission maxScore and slots from snapshot
        Map<UUID, Integer> maxScoreBySubmission = new HashMap<>();
        Map<UUID, List<UUID>> slotsBySubmission = new HashMap<>();
        for (CandidateSubmission sub : submissions) {
            UUID aId = sub.getAssessmentId();
            if (!randomisedAssessmentIds.contains(aId)) continue;
            List<AssessmentQuestion> snapshotAqs = resolveQuestionsForResult(
                    assessmentById.get(aId), sub.getId());
            Set<UUID> groupMemberQIds = snapshotAqs.stream()
                    .map(aqItem -> (Question) Hibernate.unproxy(aqItem.getQuestion()))
                    .filter(GroupQuestion.class::isInstance)
                    .map(GroupQuestion.class::cast)
                    .flatMap(gq -> gq.getMembers().stream())
                    .map(m -> m.getQuestion().getId())
                    .collect(Collectors.toSet());
            List<UUID> slots = new ArrayList<>();
            int maxScore = 0;
            for (AssessmentQuestion aqItem : snapshotAqs) {
                Question q = (Question) Hibernate.unproxy(aqItem.getQuestion());
                if (q instanceof GroupQuestion gq) {
                    maxScore += q.getMaxScore();
                    Hibernate.initialize(gq.getMembers());
                    gq.getMembers().forEach(m -> slots.add(m.getQuestion().getId()));
                } else if (!groupMemberQIds.contains(q.getId())) {
                    maxScore += q.getMaxScore();
                    slots.add(q.getId());
                }
            }
            slotsBySubmission.put(sub.getId(), slots);
            maxScoreBySubmission.put(sub.getId(), maxScore);
        }

        // Load open flags for all submissions
        List<FlagStatus> openStatuses = List.of(FlagStatus.FLAGGED, FlagStatus.UNDER_REVIEW);
        Map<UUID, FlagStatus> flagStatusBySubmission = submissionIds.stream()
                .flatMap(sid -> submissionFlagRepository
                        .findBySubmissionIdAndStatusIn(sid, openStatuses).stream())
                .collect(Collectors.toMap(SubmissionFlag::getSubmissionId, SubmissionFlag::getStatus));

        // Load AI risk assessments for all submissions (only surface MEDIUM/HIGH)
        Map<UUID, RiskLevel> riskLevelBySubmission = flaggingRiskAssessmentRepository
                .findBySubmissionIdIn(submissionIds).stream()
                .filter(ra -> ra.getRisk() == RiskLevel.HIGH || ra.getRisk() == RiskLevel.MEDIUM)
                .collect(Collectors.toMap(FlaggingRiskAssessment::getSubmissionId, FlaggingRiskAssessment::getRisk));

        return submissions.stream()
                .sorted(Comparator
                        .comparing((CandidateSubmission s) -> s.getStatus() == SubmissionStatus.IN_PROGRESS ? 1 : 0)
                        .thenComparing(Comparator.comparing(
                                s -> s.getSubmittedAt() != null ? s.getSubmittedAt() : Instant.MIN,
                                Comparator.reverseOrder()))
                )
                .map(s -> {
                    Candidate c = candidateMap.get(s.getCandidateId());
                    String name = c != null ? c.getFirstName() + " " + c.getLastName() : "Unknown";
                    int answered = answeredBySubmission.getOrDefault(s.getId(), 0L).intValue();
                    // Count scored slots: each slot whose question ID has a score counts once per slot
                    Set<UUID> scoredQIds = scoredQIdsBySubmission.getOrDefault(s.getId(), Set.of());
                    boolean isRandomised = randomisedAssessmentIds.contains(s.getAssessmentId());
                    List<UUID> slots = isRandomised
                            ? slotsBySubmission.getOrDefault(s.getId(), List.of())
                            : slotQIdsByAssessment.getOrDefault(assessmentBySubmission.get(s.getId()), List.of());
                    int marked = (int) slots.stream().filter(scoredQIds::contains).count();
                    int score = totalScoreBySubmission.getOrDefault(s.getId(), 0);
                    int maxScore = isRandomised
                            ? maxScoreBySubmission.getOrDefault(s.getId(), 0)
                            : questionCountByAssessment.getOrDefault(s.getAssessmentId(), 0);
                    int totalAnswerable = isRandomised
                            ? slots.size()
                            : totalAnswerableByAssessment.getOrDefault(s.getAssessmentId(), 0);
                    FlagStatus flagStatus = flagStatusBySubmission.get(s.getId());
                    String assessmentTitle = Optional.ofNullable(assessmentById.get(s.getAssessmentId()))
                            .map(Assessment::getTitle).orElse("");
                    boolean blacklisted = Optional.ofNullable(candidateMap.get(s.getCandidateId()))
                            .map(Candidate::isBlacklisted).orElse(false);
                    return new SubmissionSummaryResponse(
                            s.getId(), s.getInvitationId(), s.getCandidateId(), name,
                            s.getAssessmentId(), assessmentTitle,
                            s.getStatus(), s.getSubmittedAt(), answered, totalAnswerable, marked, score, maxScore, flagStatus, blacklisted,
                            riskLevelBySubmission.get(s.getId())
                    );
                })
                .toList();
    }

    private List<AssessmentQuestion> resolveQuestionsForResult(Assessment assessment, UUID submissionId) {
        List<AssessmentQuestion> allAqs = assessmentQuestionRepository
                .findByAssessmentIdOrderByDisplayOrder(assessment.getId());
        if (!assessment.isRandomiseQuestions()) return allAqs;

        List<com.psybergate.recruitment.domain.SubmissionQuestionSnapshot> snapshots =
                snapshotRepository.findBySubmissionIdOrderByDisplayOrder(submissionId);
        if (snapshots.isEmpty()) return allAqs;

        Set<UUID> snappedIds = snapshots.stream()
                .map(com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getQuestionId)
                .collect(Collectors.toSet());
        Map<UUID, Integer> orderMap = snapshots.stream().collect(Collectors.toMap(
                com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getQuestionId,
                com.psybergate.recruitment.domain.SubmissionQuestionSnapshot::getDisplayOrder
        ));
        return allAqs.stream()
                .filter(aq -> snappedIds.contains(aq.getQuestion().getId()))
                .sorted(Comparator.comparingInt(aq -> orderMap.getOrDefault(aq.getQuestion().getId(), aq.getDisplayOrder())))
                .toList();
    }

    private List<SubmissionSummaryResponse> buildNotStartedSummaries(
            List<com.psybergate.recruitment.domain.CandidateInvitation> invitations) {
        return invitations.stream()
                .map(inv -> {
                    com.psybergate.recruitment.domain.Candidate c = inv.getCandidate();
                    String name = c.getFirstName() + " " + c.getLastName();
                    UUID assessmentId = inv.getAssessment() != null ? inv.getAssessment().getId() : null;
                    String assessmentTitle = inv.getAssessment() != null ? inv.getAssessment().getTitle() : "";
                    return new SubmissionSummaryResponse(
                            null, inv.getId(), c.getId(), name,
                            assessmentId, assessmentTitle,
                            SubmissionStatus.NOT_STARTED, null, 0, 0, 0, 0, 0, null, c.isBlacklisted(),
                            null
                    );
                })
                .toList();
    }

    private String resolveCandidateAnswer(CandidateAnswer answer, Question question) {
        if (answer.getTextContent() != null) return answer.getTextContent();

        if (answer.getSelectedOptionIds() != null && question instanceof McqQuestion mcq) {
            // Resolve first selected option ID to its text
            String raw = answer.getSelectedOptionIds().replace("[", "").replace("]", "").replace("\"", "").trim();
            if (!raw.isBlank()) {
                String firstIdStr = raw.split(",")[0].trim();
                try {
                    UUID optId = UUID.fromString(firstIdStr);
                    return mcq.getOptions().stream()
                            .filter(o -> o.getId().equals(optId))
                            .map(QuestionOption::getOptionText)
                            .findFirst()
                            .orElse(firstIdStr);
                } catch (Exception e) {
                    return raw;
                }
            }
        }
        return null;
    }
}
