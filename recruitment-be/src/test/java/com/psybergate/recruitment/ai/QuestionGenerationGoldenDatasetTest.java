package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.ai.client.GroqClient;
import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.question.dto.GenerateQuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-dataset regression suite (ATR2-28): runs a curated, fixed set of generation requests
 * against the <b>real</b> Groq API to catch prompt/model drift that a Mockito-mocked {@code
 * AiService} test structurally can't — e.g. a model version that starts ignoring the pinned
 * "languageHint MUST be java" instruction (the ATR2-10 spike's core finding) more often than the
 * one built-in retry can recover from.
 * <p>
 * Excluded from the default {@code mvn test} run (see {@code test.excludedGroups} in pom.xml) —
 * costs real API quota and is inherently less deterministic than a mocked test. Run explicitly
 * with {@code mvn test -Dgroups=golden -Dtest.excludedGroups=}, or via the scheduled
 * {@code golden-dataset.yml} GitHub Actions workflow. Skips itself (rather than failing) when
 * {@code GROQ_API_KEY} isn't set, so a normal contributor checkout is unaffected.
 * <p>
 * <b>To add a golden case:</b> add a row to {@link #cases()}. Prefer topics distinct from the
 * existing set so failures aren't masked by another case exercising the same prompt path;
 * CODE_SUBMISSION cases in particular should keep varying topic wording, since the languageHint
 * drift the ATR2-10 spike found was topic-dependent (generic algorithm topics were more likely to
 * default to Python).
 */
@Tag("golden")
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class QuestionGenerationGoldenDatasetTest {

    private QuestionGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties(
                System.getenv("GROQ_API_KEY"),
                envOrDefault("GROQ_BASE_URL", "https://api.groq.com/openai/v1"),
                envOrDefault("GROQ_MODEL", "llama-3.3-70b-versatile"),
                0.7,
                30
        );
        AiService aiService = new AiServiceImpl(new GroqClient(properties));
        service = new QuestionGenerationServiceImpl(aiService, new ObjectMapper());
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    static Stream<GenerateQuestionRequest> cases() {
        return Stream.of(
                new GenerateQuestionRequest(QuestionType.MCQ, "Java collections framework", Difficulty.MEDIUM, 1),
                new GenerateQuestionRequest(QuestionType.MCQ, "SQL joins", Difficulty.EASY, 1),
                new GenerateQuestionRequest(QuestionType.TEXT, "REST vs SOAP", Difficulty.MEDIUM, 1),
                new GenerateQuestionRequest(QuestionType.TEXT, "CAP theorem", Difficulty.HARD, 1),
                new GenerateQuestionRequest(QuestionType.CODE_SUBMISSION, "array manipulation", Difficulty.EASY, 1),
                new GenerateQuestionRequest(QuestionType.CODE_SUBMISSION, "recursion", Difficulty.HARD, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generatedQuestionMeetsQualityBar(GenerateQuestionRequest request) {
        // schema validity + no-near-duplicate-MCQ-options are enforced by QuestionGenerationServiceImpl
        // itself (with one retry) — reaching an assertion at all means that bar was cleared.
        QuestionRequest draft = service.generate(request).get(0);

        assertThat(draft.title()).isNotBlank();
        assertThat(draft.body()).isNotBlank();
        assertThat(draft.difficulty()).isEqualTo(request.difficulty());

        if (request.type() == QuestionType.MCQ) {
            assertThat(draft.options()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(draft.options().stream().filter(o -> o.correct()).count()).isEqualTo(1);
        }

        if (request.type() == QuestionType.CODE_SUBMISSION) {
            // The single hard finding from the ATR2-10 spike: without pinning, Groq defaulted
            // to Python for generic algorithm topics. This is the regression guard for that.
            assertThat(draft.languageHint()).isEqualTo("java");
        }
    }
}
