package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.question.dto.GenerateDistractorsRequest;
import com.psybergate.recruitment.question.dto.QuestionOptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistractorGenerationServiceImplTest {

    @Mock
    private AiService aiService;

    private DistractorGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        // One real dependency (ObjectMapper) + one mock (AiService) → instantiate manually
        // rather than @InjectMocks. Spring Boot 4 uses Jackson 3's tools.jackson mapper.
        service = new DistractorGenerationServiceImpl(aiService, new ObjectMapper());
    }

    private GenerateDistractorsRequest request(int count) {
        return new GenerateDistractorsRequest(
                "What is the capital of France?", "Paris", count);
    }

    @Test
    void happyPath_returnsAllCorrectFalse() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\",\"Madrid\"]}");

        List<QuestionOptionRequest> result = service.generateDistractors(request(3));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(QuestionOptionRequest::text)
                .containsExactly("London", "Berlin", "Madrid");
        assertThat(result).allMatch(o -> !o.correct());
        verify(aiService, times(1)).prompt(anyString());
    }

    @Test
    void distractorEqualsCorrectAnswer_retrySucceeds() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"Paris\",\"Berlin\",\"Madrid\"]}")
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\",\"Madrid\"]}");

        List<QuestionOptionRequest> result = service.generateDistractors(request(3));

        assertThat(result).extracting(QuestionOptionRequest::text)
                .containsExactly("London", "Berlin", "Madrid");
        assertThat(result).allMatch(o -> !o.correct());
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void nearDuplicateDistractors_retrySucceeds() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"Java\",\"Java Virtual\",\"Python\"]}")
                .thenReturn("{\"distractors\":[\"Ruby\",\"Python\",\"Go\"]}");

        List<QuestionOptionRequest> result = service.generateDistractors(request(3));

        assertThat(result).extracting(QuestionOptionRequest::text)
                .containsExactly("Ruby", "Python", "Go");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void wrongCount_tooFew_retrySucceeds() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\"]}")
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\",\"Madrid\"]}");

        List<QuestionOptionRequest> result = service.generateDistractors(request(3));

        assertThat(result).hasSize(3);
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void tooMany_trimmedToCount() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\",\"Madrid\",\"Rome\",\"Lisbon\"]}");

        List<QuestionOptionRequest> result = service.generateDistractors(request(3));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(QuestionOptionRequest::text)
                .containsExactly("London", "Berlin", "Madrid");
        verify(aiService, times(1)).prompt(anyString());
    }

    @Test
    void blankText_retryAndPropagate() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"London\",\"   \",\"Madrid\"]}")
                .thenReturn("{\"distractors\":[\"London\",\"\",\"Madrid\"]}");

        assertThatThrownBy(() -> service.generateDistractors(request(3)))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("must not be blank");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void malformedJson_retryAndPropagate() {
        when(aiService.prompt(anyString()))
                .thenReturn("not json")
                .thenReturn("still not json");

        assertThatThrownBy(() -> service.generateDistractors(request(3)))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("not valid JSON");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void firstAttemptValidationFailure_promptCalledTwice() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\"]}")
                .thenReturn("{\"distractors\":[\"London\",\"Berlin\",\"Madrid\"]}");

        service.generateDistractors(request(3));

        verify(aiService, times(2)).prompt(anyString());
    }
}
