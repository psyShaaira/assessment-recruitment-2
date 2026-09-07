package com.psybergate.recruitment.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the near-duplicate/normalize helpers extracted into {@link McqOptionValidation}
 * (mcq-distractor-generation tasks 1-2). Lives in the same package to access the
 * package-visible {@code final} utility and its static methods.
 */
class McqOptionValidationTest {

    @Test
    void assertNoNearDuplicates_throwsForWordPrefixDuplicate() {
        assertThatThrownBy(() -> McqOptionValidation.assertNoNearDuplicates(
                List.of("Java", "Java Virtual Machine")))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("too similar");
    }

    @Test
    void assertNoNearDuplicates_throwsForWordPrefixDuplicate_cityCountry() {
        assertThatThrownBy(() -> McqOptionValidation.assertNoNearDuplicates(
                List.of("Paris", "Paris France")))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("too similar");
    }

    @Test
    void assertNoNearDuplicates_passesForDistinctSet() {
        assertThatCode(() -> McqOptionValidation.assertNoNearDuplicates(
                List.of("London", "Berlin", "Madrid")))
                .doesNotThrowAnyException();
    }

    @Test
    void assertNoNearDuplicates_passesForSingleElement() {
        assertThatCode(() -> McqOptionValidation.assertNoNearDuplicates(
                List.of("London")))
                .doesNotThrowAnyException();
    }

    @Test
    void assertNoNearDuplicates_passesForEmptyList() {
        assertThatCode(() -> McqOptionValidation.assertNoNearDuplicates(
                List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizeOptionText_lowercasesStripsPunctuationCollapsesWhitespace() {
        assertThat(McqOptionValidation.normalizeOptionText("  Hello, World!  "))
                .isEqualTo("hello world");
    }

    @Test
    void isDuplicateOrWordPrefix_trueWhenShorterIsPrefixOfLonger() {
        assertThat(McqOptionValidation.isDuplicateOrWordPrefix(
                Arrays.asList("java"),
                Arrays.asList("java", "virtual", "machine")))
                .isTrue();
    }

    @Test
    void isDuplicateOrWordPrefix_falseForDistinctWordLists() {
        assertThat(McqOptionValidation.isDuplicateOrWordPrefix(
                Arrays.asList("london"),
                Arrays.asList("berlin")))
                .isFalse();
    }
}
