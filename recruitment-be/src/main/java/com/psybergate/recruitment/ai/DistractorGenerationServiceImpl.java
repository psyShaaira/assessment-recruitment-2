package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.question.dto.GenerateDistractorsRequest;
import com.psybergate.recruitment.question.dto.QuestionOptionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a guarded Groq prompt from an MCQ stem + recruiter-supplied correct answer, parses the
 * response into a list of distractor drafts, and validates them (non-blank, requested count, not a
 * near-duplicate of the correct answer or of each other) before returning them. All returned options
 * are {@code correct=false} — the recruiter owns the correct answer. Drafts are never persisted here;
 * they are appended to the option list on the frontend for review before saving through the normal
 * question-create/update path.
 *
 * <p>Mirrors {@link QuestionGenerationServiceImpl}: one retry with corrective feedback on a
 * validation failure, then propagate (surfaced as 422 via {@link AiGenerationValidationException}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistractorGenerationServiceImpl implements DistractorGenerationService {

    private final AiService aiService;
    private final ObjectMapper objectMapper;

    @Override
    public List<QuestionOptionRequest> generateDistractors(GenerateDistractorsRequest request) {
        try {
            String raw = aiService.prompt(buildPrompt(request, null));
            return parseAndValidate(raw, request);
        } catch (AiGenerationValidationException firstFailure) {
            log.warn("Distractor generation failed validation, retrying once: {}",
                    firstFailure.getMessage());
            String retryRaw = aiService.prompt(buildPrompt(request, firstFailure.getMessage()));
            return parseAndValidate(retryRaw, request);
        }
    }

    private String buildPrompt(GenerateDistractorsRequest request, String correctiveFeedback) {
        StringBuilder sb = new StringBuilder()
                .append("You are generating plausible but INCORRECT answer options (distractors) ")
                .append("for a multiple-choice question.\n")
                .append("Hard rules:\n")
                .append("- Every option MUST be factually wrong for the question.\n")
                .append("- Every option MUST be plausible and tempting to someone who doesn't know the answer.\n")
                .append("- Do NOT restate or paraphrase the correct answer.\n")
                .append("- Options must be distinct from each other.\n")
                .append("Respond with ONLY a JSON object: {\"distractors\": [string, ...]} ")
                .append("(no markdown fences, no commentary).");

        if (correctiveFeedback != null) {
            sb.append("\n\nThe previous attempt was rejected for this reason: \"")
                    .append(correctiveFeedback)
                    .append("\". Fix this and try again.");
        }

        sb.append("\n\nQuestion: ").append(request.questionBody())
                .append("\nCorrect answer: ").append(request.correctAnswer())
                .append("\nNumber of distractors to generate: ").append(request.count());

        return sb.toString();
    }

    private List<QuestionOptionRequest> parseAndValidate(String raw, GenerateDistractorsRequest request) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiGenerationValidationException("AI response was not valid JSON");
        }

        JsonNode distractorsNode = node.get("distractors");
        if (distractorsNode == null || !distractorsNode.isArray()) {
            throw new AiGenerationValidationException("AI response is missing a \"distractors\" array");
        }

        List<String> texts = new ArrayList<>();
        for (JsonNode d : distractorsNode) {
            String text = (d == null || d.isNull()) ? null : d.asText();
            if (text == null || text.isBlank()) {
                throw new AiGenerationValidationException("Distractor text must not be blank");
            }
            texts.add(text);
        }

        // Too many: trim to the requested count. Too few: validation failure → retry.
        if (texts.size() > request.count()) {
            texts = new ArrayList<>(texts.subList(0, request.count()));
        } else if (texts.size() < request.count()) {
            throw new AiGenerationValidationException(
                    "Expected " + request.count() + " distractors, got " + texts.size());
        }

        // No distractor may equal / near-duplicate the recruiter's correct answer.
        for (String text : texts) {
            McqOptionValidation.assertNoNearDuplicates(List.of(request.correctAnswer(), text));
        }

        // Distractors must not near-duplicate each other.
        McqOptionValidation.assertNoNearDuplicates(texts);

        return texts.stream()
                .map(text -> new QuestionOptionRequest(text, false))
                .toList();
    }
}
