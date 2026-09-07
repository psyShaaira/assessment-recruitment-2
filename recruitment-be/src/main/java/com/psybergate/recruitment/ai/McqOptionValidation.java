package com.psybergate.recruitment.ai;

import java.util.Arrays;
import java.util.List;

/**
 * Shared near-duplicate/normalize helpers for MCQ option text, extracted from
 * {@link QuestionGenerationServiceImpl} so both full-question generation and distractor
 * generation share one implementation (avoids divergence — see mcq-distractor-generation design §4.3).
 *
 * <p>ATR2-10 spike found near-duplicate distractors (options differing only by a trailing
 * clause) in live Groq output — confusable to a candidate even though structurally valid.
 * ponytail: word-prefix heuristic, not real text similarity — flags "X" vs "X, sort of" but
 * not paraphrases. Upgrade to an edit-distance/embedding check if false negatives show up
 * in the golden-dataset suite (ATR2-28).
 */
final class McqOptionValidation {

    private McqOptionValidation() {
    }

    static String normalizeOptionText(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ").trim();
    }

    static boolean isDuplicateOrWordPrefix(List<String> a, List<String> b) {
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? b : a;
        return !shorter.isEmpty() && longer.subList(0, shorter.size()).equals(shorter);
    }

    static void assertNoNearDuplicates(List<String> texts) {
        List<List<String>> words = texts.stream()
                .map(t -> Arrays.asList(normalizeOptionText(t).split(" ")))
                .toList();
        for (int i = 0; i < words.size(); i++) {
            for (int j = i + 1; j < words.size(); j++) {
                if (isDuplicateOrWordPrefix(words.get(i), words.get(j))) {
                    throw new AiGenerationValidationException(
                            "MCQ options are too similar to distinguish: \""
                                    + texts.get(i) + "\" vs \"" + texts.get(j) + "\"");
                }
            }
        }
    }
}
