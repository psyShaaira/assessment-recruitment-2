package com.psybergate.recruitment.feedbackemail;

import com.psybergate.recruitment.ai.AiAuthenticationException;
import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiRateLimitException;
import com.psybergate.recruitment.ai.AiResponseException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.ai.AiTimeoutException;
import com.psybergate.recruitment.feedback.dto.FeedbackReportContent;
import com.psybergate.recruitment.feedback.dto.FeedbackTopicDto;
import com.psybergate.recruitment.marking.dto.ResultSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Generates the plain-text feedback email body for a candidate, attempting an AI-generated body
 * first and falling back to a static, template-rendered body when AI generation is unavailable
 * or produces an unusable result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FeedbackEmailBodyGenerator {

    private static final char[] MARKDOWN_MARKERS = {'#', '*', '`', '_'};
    private static final int MAX_ATTEMPTS = 3;

    private final AiService aiService;

    /**
     * Generates the feedback email body for the candidate, attempting an AI_Body first (Req 1.1,
     * 1.5) and falling back to {@link #renderStaticBody(FeedbackReportContent,
     * ResultSummaryResponse, String)} once 3 total attempts have been rejected (Req 3.3, 4.3).
     * Up to 3 total {@link AiService#prompt(String)} calls are made (1 initial + up to 2 retries,
     * Req 3.2); each rejection — whether a structural validation failure or one of the 5 AiService
     * exception types (Req 4.1) — feeds its rejection reason into the next attempt's prompt. This
     * method never throws for an AI-related failure and always returns a non-null, non-blank body
     * (Req 4.2).
     */
    String generateBody(FeedbackReportContent content, ResultSummaryResponse result, String candidateFirstName) {
        GenerationAttempt lastAttempt = null;
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            String previousRejectionReason = lastAttempt == null ? null : lastAttempt.rejectionReason();
            lastAttempt = attempt(content, result, candidateFirstName, previousRejectionReason);
            if (lastAttempt.rejectionReason() == null) {
                return lastAttempt.body();
            }
            log.warn("Feedback email AI generation attempt {} of {} was rejected, retrying: {}",
                    attemptNumber, MAX_ATTEMPTS, lastAttempt.rejectionReason());
        }
        return renderStaticBody(content, result, candidateFirstName);
    }

    /**
     * Performs a single Generation_Attempt: builds the prompt (threading in the previous
     * rejection reason, if any), calls {@link AiService#prompt(String)}, and either returns the
     * accepted AI_Body or a rejection reason — treating any of the 5 listed {@code AiService}
     * exceptions identically to a structural validation rejection (Req 4.1), using the
     * exception's message as the rejection reason.
     */
    private GenerationAttempt attempt(FeedbackReportContent content, ResultSummaryResponse result,
                                       String candidateFirstName, String previousRejectionReason) {
        String prompt = buildPrompt(content, result, candidateFirstName, previousRejectionReason);
        String aiBody;
        try {
            aiBody = aiService.prompt(prompt);
        } catch (AiCommunicationException | AiTimeoutException | AiRateLimitException
                 | AiAuthenticationException | AiResponseException e) {
            return new GenerationAttempt(null, e.getMessage());
        }

        Optional<String> rejectionReason = validate(aiBody, candidateFirstName);
        return rejectionReason
                .map(reason -> new GenerationAttempt(aiBody, reason))
                .orElseGet(() -> new GenerationAttempt(aiBody, null));
    }

    /**
     * Renders a {@link FeedbackReportContent} into the plain-text email body addressed to the
     * candidate (Req 2.6), consisting of exactly the following elements, in order, with no
     * separate narrative paragraph synthesized from {@code overallSummary} or anything else
     * (Req 2.19):
     * <ol>
     *   <li>a first-name greeting line (Req 2.7)</li>
     *   <li>the fixed introductory line (Req 2.8)</li>
     *   <li>a score sentence stating the whole-number percentage (Req 2.9)</li>
     *   <li>a strengths sentence naming every {@code Strong_Topic}, only if non-empty (Req 2.11, 2.12)</li>
     *   <li>a weaknesses sentence naming every {@code Weak_Topic}, only if non-empty (Req 2.13, 2.14, 2.15)</li>
     *   <li>the fixed transition line (Req 2.16)</li>
     *   <li>{@code nextSteps[]} rendered verbatim as bullets (Req 2.17)</li>
     *   <li>an encouraging sign-off sentence immediately before the signature (Req 2.18)</li>
     * </ol>
     */
    private String renderStaticBody(FeedbackReportContent content, ResultSummaryResponse result, String candidateName) {
        long percentage = Math.round((double) result.totalScore() / result.maxScore() * 100);

        List<String> strongTopics = content.topics().stream()
                .filter(topic -> topic.strengths() != null && !topic.strengths().trim().isEmpty())
                .map(FeedbackTopicDto::topic)
                .toList();
        List<String> weakTopics = content.topics().stream()
                .filter(topic -> topic.weaknesses() != null && !topic.weaknesses().trim().isEmpty())
                .map(FeedbackTopicDto::topic)
                .toList();

        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(candidateName).append(",\n\n")
            .append("Here is your feedback on your recent assessment:\n\n")
            .append("You scored ").append(percentage).append("% overall.\n\n");

        if (!strongTopics.isEmpty()) {
            body.append("You demonstrated strong performance in ")
                    .append(joinWithAnd(strongTopics))
                    .append(".\n\n");
        }

        if (!weakTopics.isEmpty()) {
            body.append("There is room for improvement in ")
                    .append(joinWithAnd(weakTopics))
                    .append(".\n\n");
        }

        body.append("Here are some next steps to help you continue improving:\n");
        for (String nextStep : content.nextSteps()) {
            body.append("- ").append(nextStep).append("\n");
        }
        body.append("\n");

        body.append("Keep up the great work, and don't hesitate to reach out if you have any questions about your feedback.\n\n");
        body.append("The Psybergate Recruitment Team");
        return body.toString();
    }

    /**
     * Builds the plain-text Feedback_Prompt sent to {@link AiService} (Req 1.2, 1.3, 1.4, 2.1,
     * 2.2). Contains the candidate's first name, the assessment title when available, the
     * whole-number score percentage, every topic name paired with a non-blank {@code strengths}
     * value, every topic name paired with a non-blank {@code weaknesses} value, and every
     * {@code nextSteps} entry, followed by fixed instructional text directing the AI on tone,
     * content, formatting, and sign-off. Deliberately never references the candidate's last
     * name, email address, submission ID, or candidate ID, none of which are passed into this
     * method. When {@code previousRejectionReason} is non-null (a retry), a corrective-feedback
     * clause naming that reason is appended, mirroring
     * {@code QuestionGenerationServiceImpl.buildPrompt}'s corrective-feedback clause.
     */
    private String buildPrompt(FeedbackReportContent content, ResultSummaryResponse result,
                                String firstName, String previousRejectionReason) {
        long percentage = Math.round((double) result.totalScore() / result.maxScore() * 100);

        StringBuilder sb = new StringBuilder();
        sb.append("You are writing a personalized feedback email for a candidate named ")
                .append(firstName)
                .append(" who just completed a technical assessment");
        if (result.assessmentTitle() != null && !result.assessmentTitle().isBlank()) {
            sb.append(" titled \"").append(result.assessmentTitle()).append("\"");
        }
        sb.append(".\n\n");

        sb.append("They scored ").append(percentage).append("% overall.\n\n");

        List<FeedbackTopicDto> topics = content.topics();

        boolean hasStrengths = topics.stream()
                .anyMatch(topic -> topic.strengths() != null && !topic.strengths().isBlank());
        if (hasStrengths) {
            sb.append("Strengths by topic:\n");
            for (FeedbackTopicDto topic : topics) {
                if (topic.strengths() != null && !topic.strengths().isBlank()) {
                    sb.append("- ").append(topic.topic()).append(": ").append(topic.strengths()).append("\n");
                }
            }
            sb.append("\n");
        }

        boolean hasWeaknesses = topics.stream()
                .anyMatch(topic -> topic.weaknesses() != null && !topic.weaknesses().isBlank());
        if (hasWeaknesses) {
            sb.append("Areas for improvement by topic:\n");
            for (FeedbackTopicDto topic : topics) {
                if (topic.weaknesses() != null && !topic.weaknesses().isBlank()) {
                    sb.append("- ").append(topic.topic()).append(": ").append(topic.weaknesses()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (content.nextSteps() != null && !content.nextSteps().isEmpty()) {
            sb.append("Suggested next steps:\n");
            for (String nextStep : content.nextSteps()) {
                sb.append("- ").append(nextStep).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Write a plain-text email body that:\n")
                .append("- Opens with a personalized greeting using the candidate's first name\n")
                .append("- Acknowledges the candidate's effort and specific achievements\n")
                .append("- Provides 2 to 3 specific, actionable recommendations\n")
                .append("- Closes with encouragement and next steps\n")
                .append("- Contains plain text only, with no markdown formatting\n")
                .append("- Signs off as \"The Psybergate Recruitment Team\"\n");

        if (previousRejectionReason != null) {
            sb.append("\nThe previous attempt was rejected for this reason: \"")
                    .append(previousRejectionReason)
                    .append("\". Fix this and try again.");
        }

        return sb.toString();
    }

    /**
     * Structurally validates an AI_Body against the fixed rules in Requirement 3.1, returning
     * the rejection reason as a non-empty {@link Optional} when the AI_Body should be rejected,
     * or {@link Optional#empty()} when it is accepted. The AI_Body is rejected if it is blank, OR
     * does not contain {@code candidateFirstName}, OR does not contain the sign-off text "The
     * Psybergate Recruitment Team", OR contains any of the markdown formatting markers {@code #},
     * {@code *}, <code>`</code>, or {@code _}.
     */
    private Optional<String> validate(String aiBody, String candidateFirstName) {
        if (aiBody == null || aiBody.isBlank()) {
            return Optional.of("The response was blank.");
        }
        if (!aiBody.contains(candidateFirstName)) {
            return Optional.of("The response did not address the candidate by their first name, \""
                    + candidateFirstName + "\".");
        }
        if (!aiBody.contains("The Psybergate Recruitment Team")) {
            return Optional.of("The response did not sign off as \"The Psybergate Recruitment Team\".");
        }
        for (char marker : MARKDOWN_MARKERS) {
            if (aiBody.indexOf(marker) >= 0) {
                return Optional.of("The response contained markdown formatting (the \"" + marker
                        + "\" character), but plain text with no markdown was required.");
            }
        }
        return Optional.empty();
    }

    /**
     * Joins a list of topic names using the shared comma/"and" rule (Req 2.10): comma-separated
     * for three or more items with "and" (no preceding comma) before the last, "X and Y" for
     * exactly two, the bare item for exactly one, and "" for an empty list.
     */
    private String joinWithAnd(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        String allButLast = String.join(", ", items.subList(0, items.size() - 1));
        return allButLast + ", and " + items.get(items.size() - 1);
    }

    /**
     * Threads a generation attempt's resulting body alongside a rejection reason (when the
     * attempt was rejected by validation) across retries.
     */
    private record GenerationAttempt(String body, String rejectionReason) {
    }
}
