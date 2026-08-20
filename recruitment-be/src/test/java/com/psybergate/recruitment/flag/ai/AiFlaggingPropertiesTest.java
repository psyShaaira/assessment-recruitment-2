package com.psybergate.recruitment.flag.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiFlaggingPropertiesTest {

    @Test
    void shouldUseExplicitValuesWhenProvided() {
        var props = new AiFlaggingProperties(true, 0.9, 0.6, 0.7, 45);

        assertThat(props.aiEnabled()).isTrue();
        assertThat(props.highThreshold()).isEqualTo(0.9);
        assertThat(props.mediumThreshold()).isEqualTo(0.6);
        assertThat(props.similarityThreshold()).isEqualTo(0.7);
        assertThat(props.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    void shouldApplyDefaultsWhenValuesAreZero() {
        var props = new AiFlaggingProperties(false, 0, 0, 0, 0);

        assertThat(props.aiEnabled()).isFalse();
        assertThat(props.highThreshold()).isEqualTo(0.8);
        assertThat(props.mediumThreshold()).isEqualTo(0.5);
        assertThat(props.similarityThreshold()).isEqualTo(0.8);
        assertThat(props.timeoutSeconds()).isEqualTo(30);
    }

    @Test
    void shouldApplyDefaultsWhenValuesAreNegative() {
        var props = new AiFlaggingProperties(true, -1, -0.5, -2, -10);

        assertThat(props.highThreshold()).isEqualTo(0.8);
        assertThat(props.mediumThreshold()).isEqualTo(0.5);
        assertThat(props.similarityThreshold()).isEqualTo(0.8);
        assertThat(props.timeoutSeconds()).isEqualTo(30);
    }
}
