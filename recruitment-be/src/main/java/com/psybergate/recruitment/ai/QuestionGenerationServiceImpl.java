package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.question.dto.GenerateQuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionOptionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a Groq prompt from generation params, parses the response into a {@link QuestionRequest}
 * draft, and validates it against the rules from the ATR2-10 quality spike before returning it.
 * Drafts are never persisted here — saving reuses the existing {@code QuestionServiceImpl.create}
 * path once a recruiter reviews and submits the (possibly edited) draft.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionGenerationServiceImpl implements QuestionGenerationService {

    // Piston only has the Java runtime installed — verified live in the ATR2-10 spike that
    // pinning this in the prompt (not just validating after the fact) reliably prevents Groq
    // defaulting to Python for generic algorithm topics.
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java");
    private static final int MAX_TITLE_LENGTH = 500;

    private final AiService aiService;
    private final ObjectMapper objectMapper;

    @Override
    public List<QuestionRequest> generate(GenerateQuestionRequest request) {
        if (request.type() == QuestionType.GROUP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GROUP questions can't be generated directly — assemble them from existing questions instead");
        }

        List<QuestionRequest> drafts = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            drafts.add(generateOne(request));
        }
        return drafts;
    }

    private QuestionRequest generateOne(GenerateQuestionRequest request) {
        try {
            String raw = aiService.prompt(buildPrompt(request, null));
            return parseAndValidate(raw, request);
        } catch (AiGenerationValidationException firstFailure) {
            log.warn("Generated {} question failed validation, retrying once: {}",
                    request.type(), firstFailure.getMessage());
            String retryRaw = aiService.prompt(buildPrompt(request, firstFailure.getMessage()));
            return parseAndValidate(retryRaw, request);
        }
    }

    private String buildPrompt(GenerateQuestionRequest request, String correctiveFeedback) {
        StringBuilder sb = new StringBuilder()
                .append("You are generating a single ").append(request.type())
                .append(" assessment question for a technical recruitment platform.\n");

        if (request.type() == QuestionType.CODE_SUBMISSION) {
            sb.append("This platform's code execution sandbox ONLY supports Java. ")
                    .append("The languageHint field MUST always be \"java\", regardless of the topic.\n");
        }

        sb.append("Respond with ONLY a single JSON object matching this exact shape ")
                .append("(no markdown fences, no commentary):\n")
                .append(schemaFor(request.type()));

        if (correctiveFeedback != null) {
            sb.append("\n\nThe previous attempt was rejected for this reason: \"")
                    .append(correctiveFeedback)
                    .append("\". Fix this and try again.");
        }

        sb.append("\n\nTopic: ").append(request.topic())
                .append("\nTarget difficulty: ").append(request.difficulty());

        return sb.toString();
    }

    private String schemaFor(QuestionType type) {
        return switch (type) {
            case MCQ -> "{\"title\": string, \"body\": string, "
                    + "\"options\": [{\"text\": string, \"correct\": boolean}, ...]} "
                    + "(at least 2 options, exactly one with correct: true)";
            case TEXT -> "{\"title\": string, \"body\": string}";
            case CODE_SUBMISSION -> "{\"title\": string, \"body\": string, \"languageHint\": string}";
            case GROUP -> throw new IllegalStateException("GROUP is rejected before reaching this point");
        };
    }

    private QuestionRequest parseAndValidate(String raw, GenerateQuestionRequest request) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiGenerationValidationException("AI response was not valid JSON");
        }

        String title = textOrNull(node, "title");
        if (title == null || title.isBlank()) {
            throw new AiGenerationValidationException("Generated question is missing a title");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new AiGenerationValidationException("Generated title exceeds " + MAX_TITLE_LENGTH + " characters");
        }

        String body = textOrNull(node, "body");
        if (body == null || body.isBlank()) {
            throw new AiGenerationValidationException("Generated question is missing a body");
        }

        List<String> tags = List.of(request.topic().trim().toLowerCase());
        List<QuestionOptionRequest> options = null;
        String languageHint = null;

        if (request.type() == QuestionType.MCQ) {
            options = parseAndValidateMcqOptions(node);
        } else if (request.type() == QuestionType.CODE_SUBMISSION) {
            languageHint = parseAndValidateLanguageHint(node);
        }

        return new QuestionRequest(request.type(), title, body, tags, options, languageHint,
                null, null, request.difficulty());
    }

    private List<QuestionOptionRequest> parseAndValidateMcqOptions(JsonNode node) {
        JsonNode optionsNode = node.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() < 2) {
            throw new AiGenerationValidationException("MCQ requires at least 2 options");
        }

        List<QuestionOptionRequest> options = new ArrayList<>();
        int correctCount = 0;
        for (JsonNode opt : optionsNode) {
            String text = textOrNull(opt, "text");
            if (text == null || text.isBlank()) {
                throw new AiGenerationValidationException("MCQ option text must not be blank");
            }
            boolean correct = opt.path("correct").asBoolean(false);
            if (correct) correctCount++;
            options.add(new QuestionOptionRequest(text, correct));
        }
        if (correctCount != 1) {
            throw new AiGenerationValidationException(
                    "MCQ must have exactly 1 correct option, got " + correctCount);
        }
        return options;
    }

    private String parseAndValidateLanguageHint(JsonNode node) {
        String hint = textOrNull(node, "languageHint");
        String normalized = hint == null ? null : hint.trim().toLowerCase();
        if (normalized == null || !SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new AiGenerationValidationException(
                    "languageHint must be one of " + SUPPORTED_LANGUAGES + ", got: " + hint);
        }
        return normalized;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
