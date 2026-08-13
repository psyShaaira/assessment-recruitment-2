package com.psybergate.recruitment.marking.ai;

import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.AiMarkingSuggestion;
import com.psybergate.recruitment.domain.AssessmentQuestion;
import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CandidateSubmission;
import com.psybergate.recruitment.domain.GroupQuestion;
import com.psybergate.recruitment.domain.GroupQuestionMember;
import com.psybergate.recruitment.domain.Question;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.marking.ai.dto.AiMarkingSuggestionResponse;
import com.psybergate.recruitment.repository.AiMarkingSuggestionRepository;
import com.psybergate.recruitment.repository.AssessmentQuestionRepository;
import com.psybergate.recruitment.repository.CandidateAnswerRepository;
import com.psybergate.recruitment.repository.CandidateSubmissionRepository;
import com.psybergate.recruitment.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
@RequiredArgsConstructor
public class AiMarkingServiceImpl implements AiMarkingService {

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("SCORE:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATIONALE_PATTERN =
            Pattern.compile("RATIONALE:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final AiService aiService;
    private final AiMarkingPromptBuilder promptBuilder;
    private final AiMarkingSuggestionRepository aiMarkingSuggestionRepository;
    private final CandidateAnswerRepository candidateAnswerRepository;
    private final CandidateSubmissionRepository candidateSubmissionRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;

    @Override
    public AiMarkingSuggestionResponse generateSuggestion(UUID submissionId, UUID questionId) {
        CandidateSubmission submission = candidateSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        validateQuestionBelongsToAssessment(submission.getAssessmentId(), questionId);

        Question question = (Question) Hibernate.unproxy(
                questionRepository.findById(questionId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found in this assessment")));

        if (question.getType() == QuestionType.MCQ || question.getType() == QuestionType.GROUP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Question type is not eligible for AI-assisted marking");
        }

        CandidateAnswer answer = candidateAnswerRepository
                .findBySubmissionIdAndQuestionId(submissionId, questionId)
                .orElse(null);

        if (answer == null || !StringUtils.hasText(answer.getTextContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No answer content to evaluate");
        }

        String promptText = promptBuilder.build(question, answer);

        String aiResponse = aiService.prompt(promptText);

        int parsedScore = parseScore(aiResponse);
        String rationale = parseRationale(aiResponse);

        int clampedScore = clamp(parsedScore, 0, question.getMaxScore());

        AiMarkingSuggestion suggestion = aiMarkingSuggestionRepository
                .findByCandidateAnswerId(answer.getId())
                .orElseGet(AiMarkingSuggestion::new);

        suggestion.setCandidateAnswerId(answer.getId());
        suggestion.setScore(clampedScore);
        suggestion.setRationale(rationale);
        suggestion.setGeneratedAt(Instant.now());

        suggestion = aiMarkingSuggestionRepository.save(suggestion);

        return new AiMarkingSuggestionResponse(
                answer.getId(), suggestion.getScore(), question.getMaxScore(),
                suggestion.getRationale(), suggestion.getGeneratedAt()
        );
    }

    @Override
    public AiMarkingSuggestionResponse getSuggestion(UUID submissionId, UUID questionId) {
        CandidateSubmission submission = candidateSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));

        validateQuestionBelongsToAssessment(submission.getAssessmentId(), questionId);

        CandidateAnswer answer = candidateAnswerRepository
                .findBySubmissionIdAndQuestionId(submissionId, questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No suggestion exists for this answer"));

        AiMarkingSuggestion suggestion = aiMarkingSuggestionRepository
                .findByCandidateAnswerId(answer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No suggestion exists for this answer"));

        Question question = (Question) Hibernate.unproxy(
                questionRepository.findById(questionId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found in this assessment")));

        return new AiMarkingSuggestionResponse(
                answer.getId(), suggestion.getScore(), question.getMaxScore(),
                suggestion.getRationale(), suggestion.getGeneratedAt()
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void validateQuestionBelongsToAssessment(UUID assessmentId, UUID questionId) {
        List<AssessmentQuestion> aqList = assessmentQuestionRepository
                .findByAssessmentIdOrderByDisplayOrder(assessmentId);
        boolean validQuestion = false;
        for (AssessmentQuestion aq : aqList) {
            Question q = (Question) Hibernate.unproxy(aq.getQuestion());
            if (q.getId().equals(questionId)) {
                validQuestion = true;
                break;
            }
            if (q instanceof GroupQuestion gq) {
                for (GroupQuestionMember m : gq.getMembers()) {
                    if (m.getQuestion().getId().equals(questionId)) {
                        validQuestion = true;
                        break;
                    }
                }
            }
            if (validQuestion) break;
        }
        if (!validQuestion) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found in this assessment");
        }
    }

    private int parseScore(String aiResponse) {
        Matcher matcher = SCORE_PATTERN.matcher(aiResponse);
        if (!matcher.find()) {
            throw new AiMarkingResponseException("Could not parse SCORE from AI response");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new AiMarkingResponseException("Could not parse SCORE from AI response");
        }
    }

    private String parseRationale(String aiResponse) {
        Matcher matcher = RATIONALE_PATTERN.matcher(aiResponse);
        if (!matcher.find()) {
            throw new AiMarkingResponseException("Could not parse RATIONALE from AI response");
        }
        String rationale = matcher.group(1).trim();
        if (!StringUtils.hasText(rationale)) {
            throw new AiMarkingResponseException("Could not parse RATIONALE from AI response");
        }
        return rationale;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
