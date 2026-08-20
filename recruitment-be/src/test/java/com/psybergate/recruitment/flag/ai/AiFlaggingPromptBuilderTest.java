package com.psybergate.recruitment.flag.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiFlaggingPromptBuilderTest {

    private AiFlaggingPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new AiFlaggingPromptBuilder();
    }

    private SubmissionAnalysisContext buildTestContext() {
        Instant baseTime = Instant.parse("2026-06-01T10:00:00Z");
        List<AnswerContext> answers = List.of(
            new AnswerContext(
                "What is polymorphism?",
                "TEXT",
                "MEDIUM",
                10,
                "Polymorphism is the ability of objects to take many forms.",
                baseTime.plusSeconds(120),
                120
            ),
            new AnswerContext(
                "FizzBuzz implementation",
                "CODE_SUBMISSION",
                "HARD",
                20,
                "public class FizzBuzz { public static void main(String[] args) { } }",
                baseTime.plusSeconds(300),
                300
            ),
            new AnswerContext(
                "Which keyword declares a class?",
                "MCQ",
                "EASY",
                5,
                null,
                baseTime.plusSeconds(30),
                30
            )
        );

        return new SubmissionAnalysisContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Java Developer Assessment",
            60,
            480,
            3,
            answers
        );
    }

    @Test
    void promptContainsAssessmentMetadata() {
        String prompt = promptBuilder.build(buildTestContext());

        assertThat(prompt)
            .contains("Java Developer Assessment")
            .contains("60 minutes")
            .contains("Question count: 3")
            .contains("Actual duration: 480 seconds");
    }

    @Test
    void promptContainsPerAnswerTimeline() {
        String prompt = promptBuilder.build(buildTestContext());

        assertThat(prompt)
            .contains("[120s]")
            .contains("[300s]")
            .contains("[30s]")
            .contains("TEXT")
            .contains("CODE_SUBMISSION")
            .contains("MCQ")
            .contains("(MEDIUM)")
            .contains("(HARD)")
            .contains("(EASY)")
            .contains("content_length=");
    }

    @Test
    void promptContainsFullTextAndCodeAnswerContents() {
        String prompt = promptBuilder.build(buildTestContext());

        assertThat(prompt)
            .contains("Polymorphism is the ability of objects to take many forms.")
            .contains("public class FizzBuzz { public static void main(String[] args) { } }");
    }

    @Test
    void promptDoesNotContainMcqAnswerContent() {
        String prompt = promptBuilder.build(buildTestContext());

        // MCQ appears in timeline but not in the full answer contents section
        String answerContentSection = prompt.substring(
            prompt.indexOf("=== ANSWER CONTENTS (TEXT/CODE ONLY) ===")
        );
        assertThat(answerContentSection)
            .doesNotContain("Which keyword declares a class?");
    }

    @Test
    void promptContainsJsonResponseSchema() {
        String prompt = promptBuilder.build(buildTestContext());

        assertThat(prompt)
            .contains("RESPONSE FORMAT")
            .contains("\"risk\": \"HIGH|MEDIUM|LOW\"")
            .contains("\"reasons\":")
            .contains("TIMING_ANOMALY")
            .contains("AI_GENERATED_CONTENT")
            .contains("SUSPICIOUS_BEHAVIOUR")
            .contains("\"rationale\":")
            .contains("\"confidence\": 0.0-1.0");
    }

    @Test
    void promptDoesNotContainCandidatePii() {
        // SubmissionAnalysisContext has no candidate-identifying fields by design.
        // Verify the prompt output has no candidate-related markers.
        String prompt = promptBuilder.build(buildTestContext());

        assertThat(prompt)
            .doesNotContain("candidate name")
            .doesNotContain("email")
            .doesNotContain("candidateId")
            .doesNotContain("candidate_id");

        // Also verify the context record itself has no candidate fields —
        // the record only holds submissionId, assessmentId, assessmentTitle,
        // timeLimitMinutes, actualDurationSeconds, questionCount, answers.
        SubmissionAnalysisContext ctx = buildTestContext();
        assertThat(ctx.getClass().getRecordComponents())
            .extracting("name")
            .doesNotContain("candidateName", "candidateEmail", "candidateId");
    }

    @Test
    void promptContainsEvaluationCriteria() {
        String prompt = promptBuilder.build(buildTestContext());

        // Timing anomaly indicators
        assertThat(prompt)
            .contains("Timing anomaly indicators")
            .contains("too fast")
            .contains("Burst-save patterns");

        // AI-content indicators
        assertThat(prompt)
            .contains("AI-content indicators")
            .contains("Formulaic structure")
            .contains("Unnaturally uniform quality");

        // Suspicious patterns
        assertThat(prompt)
            .contains("Suspicious patterns")
            .contains("inconsistent with completion speed");
    }
}
