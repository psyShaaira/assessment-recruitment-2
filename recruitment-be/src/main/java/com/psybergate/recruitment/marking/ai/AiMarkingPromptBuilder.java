package com.psybergate.recruitment.marking.ai;

import com.psybergate.recruitment.domain.CandidateAnswer;
import com.psybergate.recruitment.domain.CodeSubmissionQuestion;
import com.psybergate.recruitment.domain.Question;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the plain-text prompt sent to {@code AiService} for a single
 * {@link Question} and its associated {@link CandidateAnswer}.
 * <p>
 * Reads only from the {@code question} and {@code answer} arguments passed
 * in — no repository or service access — so that no data beyond the
 * question and answer being evaluated can ever be included in the prompt.
 */
@Component
public class AiMarkingPromptBuilder {

    public String build(Question question, CandidateAnswer answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are assisting a recruiter in marking a candidate's answer to an assessment question.\n\n");
        sb.append("Question title: ").append(question.getTitle()).append('\n');
        sb.append("Question body: ").append(question.getBody()).append('\n');
        sb.append("Maximum score: ").append(question.getMaxScore()).append('\n');

        if (question instanceof CodeSubmissionQuestion csq
                && StringUtils.hasText(csq.getLanguageHint())) {
            sb.append("Language hint: ").append(csq.getLanguageHint()).append('\n');
        }

        sb.append("Candidate's answer: ").append(answer.getTextContent()).append("\n\n");
        sb.append("Evaluate the candidate's answer against the question and award a score between 0 and ")
                .append(question.getMaxScore()).append(" (inclusive).\n");
        sb.append("Reply strictly in the following format, with no additional text before or after it:\n");
        sb.append("SCORE: <integer>\n");
        sb.append("RATIONALE: <text>\n");

        return sb.toString();
    }
}
