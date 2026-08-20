package com.psybergate.recruitment.flag.ai;

import org.springframework.stereotype.Component;

/**
 * Builds the plain-text prompt sent to {@code AiService} for integrity analysis
 * of a completed candidate submission.
 * <p>
 * Stateless — reads only from the {@link SubmissionAnalysisContext} argument.
 * No repository or service access; no candidate PII ever included.
 */
@Component
public class AiFlaggingPromptBuilder {

    public static final String PROMPT_VERSION = "v1";

    public String build(SubmissionAnalysisContext context) {
        StringBuilder sb = new StringBuilder();

        // 1. System instruction
        sb.append("You are an integrity analysis system. Analyze the following candidate assessment submission ")
          .append("for potential academic integrity issues.\n\n");

        // 2. Assessment metadata
        sb.append("=== ASSESSMENT METADATA ===\n");
        sb.append("Title: ").append(context.assessmentTitle()).append('\n');
        sb.append("Time limit: ").append(context.timeLimitMinutes()).append(" minutes\n");
        sb.append("Question count: ").append(context.questionCount()).append('\n');
        sb.append("Actual duration: ").append(context.actualDurationSeconds()).append(" seconds\n\n");

        // 3. Per-answer timeline
        sb.append("=== ANSWER TIMELINE ===\n");
        for (AnswerContext answer : context.answers()) {
            sb.append("- [").append(answer.secondsSinceStart()).append("s] ")
              .append(answer.questionType())
              .append(" (").append(answer.difficulty()).append(") ")
              .append("\"").append(answer.questionTitle()).append("\" ")
              .append("content_length=").append(contentLength(answer.answerContent()))
              .append('\n');
        }
        sb.append('\n');

        // 4. Full TEXT/CODE answer contents (skip MCQ)
        sb.append("=== ANSWER CONTENTS (TEXT/CODE ONLY) ===\n");
        for (AnswerContext answer : context.answers()) {
            if ("MCQ".equals(answer.questionType())) {
                continue;
            }
            sb.append("--- ").append(answer.questionTitle())
              .append(" [").append(answer.questionType())
              .append(", ").append(answer.difficulty())
              .append(", max_score=").append(answer.maxScore()).append("] ---\n");
            sb.append(answer.answerContent() != null ? answer.answerContent() : "(no answer)");
            sb.append("\n\n");
        }

        // 5. Evaluation criteria
        sb.append("=== EVALUATION CRITERIA ===\n");
        sb.append("Analyze the submission for the following indicators:\n\n");
        sb.append("Timing anomaly indicators:\n");
        sb.append("- Completed way too fast relative to the time limit and question count/difficulty\n");
        sb.append("- Burst-save patterns (all answers saved within an implausibly short window)\n");
        sb.append("- Quality inconsistent with speed (complex answers produced in seconds)\n\n");
        sb.append("AI-content indicators:\n");
        sb.append("- Formulaic structure across multiple answers\n");
        sb.append("- Unnaturally uniform quality across answers of varying difficulty\n");
        sb.append("- Generic phrasing lacking specificity\n");
        sb.append("- Excessive hedging language\n\n");
        sb.append("Suspicious patterns:\n");
        sb.append("- Answer quality inconsistent with completion speed\n");
        sb.append("- All answers arriving in a single rapid burst after extended idle\n\n");

        // 6. Response schema instruction
        sb.append("=== RESPONSE FORMAT ===\n");
        sb.append("Respond with ONLY a JSON object in the following format, with no additional text:\n");
        sb.append("{\"risk\": \"HIGH|MEDIUM|LOW\", \"reasons\": [\"TIMING_ANOMALY\", \"AI_GENERATED_CONTENT\", ")
          .append("\"SUSPICIOUS_BEHAVIOUR\"], \"rationale\": \"...\", \"confidence\": 0.0-1.0}\n\n");
        sb.append("- risk: overall risk level (HIGH = strong evidence of integrity violation, ")
          .append("MEDIUM = some concerning indicators, LOW = no significant issues)\n");
        sb.append("- reasons: array of applicable reason codes (include only those that apply)\n");
        sb.append("- rationale: brief explanation of your assessment\n");
        sb.append("- confidence: your confidence in this assessment from 0.0 to 1.0\n");

        return sb.toString();
    }

    private int contentLength(String content) {
        return content != null ? content.length() : 0;
    }
}
