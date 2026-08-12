package com.psybergate.recruitment.assessment;

import com.psybergate.recruitment.assessment.dto.*;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.repository.AssessmentQuestionRepository;
import com.psybergate.recruitment.repository.AssessmentRepository;
import com.psybergate.recruitment.repository.QuestionRepository;
import com.psybergate.recruitment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class AssessmentServiceImpl implements AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public AssessmentDetailResponse create(AssessmentRequest req, UUID createdById) {
        User creator = userRepository.findById(createdById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        Assessment assessment = new Assessment();
        assessment.setTitle(req.title());
        assessment.setDescription(req.description());
        assessment.setTimeLimitMinutes(req.timeLimitMinutes());
        assessment.setCreatedBy(creator);
        applyPassword(assessment, req.accessPassword());
        applyRandomisation(assessment, req);

        return toDetailResponse(assessmentRepository.save(assessment));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssessmentSummaryResponse> findAll() {
        return assessmentRepository.findAll().stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentDetailResponse findById(UUID id) {
        return toDetailResponse(requireAssessment(id));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyAccessPassword(UUID assessmentId, String rawPassword) {
        Assessment assessment = requireAssessment(assessmentId);
        if (assessment.getAccessPasswordHash() == null) {
            return true;
        }
        return passwordEncoder.matches(rawPassword, assessment.getAccessPasswordHash());
    }

    @Override
    public AssessmentDetailResponse update(UUID id, AssessmentRequest req) {
        Assessment assessment = requireAssessment(id);
        assessment.setTitle(req.title());
        assessment.setDescription(req.description());
        assessment.setTimeLimitMinutes(req.timeLimitMinutes());
        applyPassword(assessment, req.accessPassword());
        applyRandomisation(assessment, req);
        return toDetailResponse(assessmentRepository.save(assessment));
    }

    @Override
    public void delete(UUID id) {
        assessmentRepository.delete(requireAssessment(id));
    }

    @Override
    public AssessmentDetailResponse publish(UUID id) {
        Assessment assessment = requireAssessment(id);
        if (assessment.getStatus() == AssessmentStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assessment is already published");
        }
        assessment.setStatus(AssessmentStatus.PUBLISHED);
        return toDetailResponse(assessmentRepository.save(assessment));
    }

    @Override
    public AddQuestionResult addQuestion(UUID assessmentId, AddAssessmentQuestionRequest req) {
        Assessment assessment = requireAssessment(assessmentId);

        Question question = questionRepository.findById(req.questionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found"));

        boolean[] created = {false};
        assessmentQuestionRepository.findByAssessmentIdAndQuestionId(assessmentId, req.questionId())
                .ifPresentOrElse(
                        existing -> existing.setDisplayOrder(req.displayOrder()),
                        () -> {
                            assertNoGroupOverlap(assessmentId, question);
                            AssessmentQuestion aq = new AssessmentQuestion();
                            aq.setAssessment(assessment);
                            aq.setQuestion(question);
                            aq.setDisplayOrder(req.displayOrder());
                            assessmentQuestionRepository.save(aq);
                            assessment.getQuestions().add(aq);
                            created[0] = true;
                        }
                );

        return new AddQuestionResult(toDetailResponse(assessment), created[0]);
    }

    /**
     * A question that's both a standalone entry and a member of a GROUP question on the
     * same assessment would have its score counted twice when results are computed, so
     * reject the combination at the source rather than relying on scoring code to dedupe.
     */
    private void assertNoGroupOverlap(UUID assessmentId, Question question) {
        List<AssessmentQuestion> existing = assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId);
        Question rawQuestion = (Question) Hibernate.unproxy(question);

        if (rawQuestion instanceof GroupQuestion gq) {
            Set<UUID> memberIds = gq.getMembers().stream()
                    .map(m -> m.getQuestion().getId())
                    .collect(Collectors.toSet());
            boolean conflict = existing.stream().anyMatch(aq -> memberIds.contains(aq.getQuestion().getId()));
            if (conflict) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "One or more of this group's questions are already on the assessment individually");
            }
        } else {
            boolean conflict = existing.stream()
                    .map(aq -> (Question) Hibernate.unproxy(aq.getQuestion()))
                    .filter(GroupQuestion.class::isInstance)
                    .map(GroupQuestion.class::cast)
                    .flatMap(g -> g.getMembers().stream())
                    .anyMatch(m -> m.getQuestion().getId().equals(rawQuestion.getId()));
            if (conflict) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This question is already included in a group question on this assessment");
            }
        }
    }

    @Override
    public AssessmentDetailResponse reorderQuestions(UUID assessmentId, ReorderAssessmentQuestionsRequest request) {
        Assessment assessment = requireAssessment(assessmentId);
        List<AssessmentQuestion> existing = assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId);
        var existingIds = existing.stream().map(aq -> aq.getQuestion().getId()).collect(java.util.stream.Collectors.toSet());

        for (ReorderAssessmentQuestionsRequest.QuestionOrderItem item : request.questions()) {
            if (!existingIds.contains(item.questionId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Question " + item.questionId() + " is not part of this assessment");
            }
        }

        for (ReorderAssessmentQuestionsRequest.QuestionOrderItem item : request.questions()) {
            existing.stream()
                    .filter(aq -> aq.getQuestion().getId().equals(item.questionId()))
                    .findFirst()
                    .ifPresent(aq -> aq.setDisplayOrder(item.displayOrder()));
        }

        return toDetailResponse(assessment);
    }

    @Override
    public void removeQuestion(UUID assessmentId, UUID questionId) {
        requireAssessment(assessmentId);
        AssessmentQuestion item = assessmentQuestionRepository
                .findByAssessmentIdAndQuestionId(assessmentId, questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Question is not part of this assessment"));
        assessmentQuestionRepository.delete(item);
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentPreviewResponse getPreview(UUID assessmentId) {
        Assessment assessment = requireAssessment(assessmentId);

        List<PreviewQuestionDto> questions = assessment.getQuestions().stream()
                .sorted(Comparator.comparingInt(AssessmentQuestion::getDisplayOrder))
                .map(aq -> toPreviewQuestion(aq.getQuestion()))
                .toList();

        List<RandomisationQuotaDto> quotaDtos = toQuotaDtos(assessment);

        return new AssessmentPreviewResponse(
                assessment.getId(),
                assessment.getTitle(),
                assessment.getDescription(),
                assessment.getTimeLimitMinutes(),
                assessment.getAccessPasswordHash() != null,
                assessment.isRandomiseQuestions(),
                quotaDtos,
                questions
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AssemblySuggestionResponse suggestQuestions(AssemblySuggestionRequest request) {
        Set<UUID> alreadySuggested = new HashSet<>();
        List<AssemblyQuotaOutcome> outcomes = new ArrayList<>();

        for (AssemblyQuotaDto quota : request.quotas()) {
            String tagName = quota.tag() != null ? quota.tag().trim().toLowerCase() : null;
            List<Question> pool = questionRepository.findByTagNameAndDifficulty(tagName, quota.difficulty())
                    .stream()
                    .filter(q -> !alreadySuggested.contains(q.getId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(pool);

            List<Question> picked = pool.stream().limit(quota.count()).toList();
            picked.forEach(q -> alreadySuggested.add(q.getId()));

            List<SuggestedAssemblyQuestionDto> suggested = picked.stream().map(this::toSuggestedDto).toList();
            int shortfall = quota.count() - suggested.size();
            outcomes.add(new AssemblyQuotaOutcome(quota, suggested, shortfall));
        }

        return new AssemblySuggestionResponse(outcomes);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Assessment requireAssessment(UUID id) {
        return assessmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found"));
    }

    private void applyPassword(Assessment assessment, String rawPassword) {
        if (rawPassword != null && !rawPassword.isBlank()) {
            assessment.setAccessPasswordHash(passwordEncoder.encode(rawPassword));
            assessment.setAccessPassword(rawPassword);
        } else if (rawPassword != null) {
            // Explicitly blank → clear any existing password
            assessment.setAccessPasswordHash(null);
            assessment.setAccessPassword(null);
        }
        // null → leave unchanged
    }

    private AssessmentSummaryResponse toSummaryResponse(Assessment a) {
        return new AssessmentSummaryResponse(
                a.getId(), a.getTitle(), a.getDescription(), a.getTimeLimitMinutes(),
                a.getStatus(), a.getQuestions().size(),
                a.getAccessPasswordHash() != null,
                a.getCreatedAt(), a.getUpdatedAt()
        );
    }

    private AssessmentDetailResponse toDetailResponse(Assessment a) {
        List<AssessmentQuestionItemResponse> questions = a.getQuestions().stream()
                .sorted(Comparator.comparingInt(AssessmentQuestion::getDisplayOrder))
                .map(aq -> {
                    Question q = (Question) Hibernate.unproxy(aq.getQuestion());
                    int subCount = (q instanceof GroupQuestion gq) ? gq.getMembers().size() : 0;
                    return new AssessmentQuestionItemResponse(
                            q.getId(), q.getTitle(), q.getType(), aq.getDisplayOrder(), subCount, q.getDifficulty()
                    );
                })
                .toList();

        return new AssessmentDetailResponse(
                a.getId(), a.getTitle(), a.getDescription(), a.getTimeLimitMinutes(),
                a.getStatus(), questions,
                a.getAccessPasswordHash() != null,
                a.isRandomiseQuestions(),
                toQuotaDtos(a),
                a.getCreatedAt(), a.getUpdatedAt()
        );
    }

    private void applyRandomisation(Assessment assessment, AssessmentRequest req) {
        assessment.setRandomiseQuestions(req.randomiseQuestions());
        assessment.getRandomisationQuotas().clear();
        if (req.randomiseQuestions()) {
            List<RandomisationQuotaDto> quotas = req.randomisationQuotas();
            if (quotas == null || quotas.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "At least one quota is required when randomiseQuestions is true");
            }
            for (RandomisationQuotaDto dto : quotas) {
                RandomisationQuota quota = new RandomisationQuota();
                quota.setAssessment(assessment);
                quota.setQuestionType(dto.questionType());
                quota.setCount(dto.count());
                assessment.getRandomisationQuotas().add(quota);
            }
        }
    }

    private List<RandomisationQuotaDto> toQuotaDtos(Assessment a) {
        return a.getRandomisationQuotas().stream()
                .map(q -> new RandomisationQuotaDto(q.getQuestionType(), q.getCount()))
                .collect(Collectors.toList());
    }

    private SuggestedAssemblyQuestionDto toSuggestedDto(Question q) {
        Question unproxied = (Question) Hibernate.unproxy(q);
        List<String> tags = unproxied.getTags().stream().map(Tag::getName).sorted().toList();
        return new SuggestedAssemblyQuestionDto(
                unproxied.getId(), unproxied.getType(), unproxied.getTitle(), unproxied.getDifficulty(), tags
        );
    }

    private PreviewQuestionDto toPreviewQuestion(Question q) {
        // Unproxy required: JOINED-inheritance @ManyToOne(LAZY) produces a Question proxy;
        // instanceof checks against the proxy type always fail for subclasses.
        Question unproxied = (Question) Hibernate.unproxy(q);
        List<PreviewOptionDto> options = null;
        String languageHint = null;
        List<PreviewQuestionDto> subQuestions = null;

        if (unproxied instanceof McqQuestion mcq) {
            options = mcq.getOptions().stream()
                    .map(o -> new PreviewOptionDto(o.getId(), o.getOptionText()))
                    .toList();
        } else if (unproxied instanceof CodeSubmissionQuestion csq) {
            languageHint = csq.getLanguageHint();
        } else if (unproxied instanceof GroupQuestion gq) {
            // GROUP question: body is the preamble; members become sub-questions.
            // Sub-questions are rendered using their native type (MCQ/TEXT/CODE_SUBMISSION).
            subQuestions = gq.getMembers().stream()
                    .map(m -> toPreviewQuestion((Question) Hibernate.unproxy(m.getQuestion())))
                    .toList();
        }

        return new PreviewQuestionDto(q.getId(), q.getType(), q.getBody(), options, languageHint, subQuestions);
    }
}
