package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.question.dto.GenerateQuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionGenerationServiceImplTest {

    @Mock
    private AiService aiService;

    private QuestionGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QuestionGenerationServiceImpl(aiService, new ObjectMapper());
    }

    @Test
    void generate_mcq_parsesCorrectlyWithDifficultyAndTagMapped() {
        when(aiService.prompt(anyString())).thenReturn("""
                {"title": "Java Generics", "body": "What does <T> mean?",
                 "options": [{"text": "A type parameter", "correct": true},
                             {"text": "A comment", "correct": false}]}
                """);

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.MCQ, "Java", Difficulty.MEDIUM, 1);
        QuestionRequest draft = service.generate(req).get(0);

        assertThat(draft.type()).isEqualTo(QuestionType.MCQ);
        assertThat(draft.title()).isEqualTo("Java Generics");
        assertThat(draft.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(draft.tags()).containsExactly("java");
        assertThat(draft.options()).hasSize(2);
        assertThat(draft.options().stream().filter(o -> o.correct()).count()).isEqualTo(1);
    }

    @Test
    void generate_text_parsesCorrectlyWithNoOptionsOrLanguageHint() {
        when(aiService.prompt(anyString())).thenReturn("""
                {"title": "Explain polymorphism", "body": "Describe polymorphism in OOP."}
                """);

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.TEXT, "OOP", Difficulty.EASY, 1);
        QuestionRequest draft = service.generate(req).get(0);

        assertThat(draft.type()).isEqualTo(QuestionType.TEXT);
        assertThat(draft.options()).isNull();
        assertThat(draft.languageHint()).isNull();
    }

    @Test
    void generate_codeSubmission_parsesCorrectlyWithJavaLanguageHint() {
        when(aiService.prompt(anyString())).thenReturn("""
                {"title": "FizzBuzz", "body": "Print 1 to 100 with Fizz/Buzz rules.", "languageHint": "java"}
                """);

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.CODE_SUBMISSION, "algorithms", Difficulty.EASY, 1);
        QuestionRequest draft = service.generate(req).get(0);

        assertThat(draft.languageHint()).isEqualTo("java");
    }

    @Test
    void generate_codeSubmissionWrongLanguage_retriesOnceThenSucceeds() {
        when(aiService.prompt(anyString()))
                .thenReturn("{\"title\": \"FizzBuzz\", \"body\": \"...\", \"languageHint\": \"python\"}")
                .thenReturn("{\"title\": \"FizzBuzz\", \"body\": \"...\", \"languageHint\": \"java\"}");

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.CODE_SUBMISSION, "algorithms", Difficulty.EASY, 1);
        QuestionRequest draft = service.generate(req).get(0);

        assertThat(draft.languageHint()).isEqualTo("java");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void generate_mcqWrongCorrectCount_retriesOnceThenThrowsIfStillInvalid() {
        when(aiService.prompt(anyString())).thenReturn("""
                {"title": "Bad MCQ", "body": "...",
                 "options": [{"text": "A", "correct": true}, {"text": "B", "correct": true}]}
                """);

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.MCQ, "java", Difficulty.EASY, 1);

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("exactly 1 correct option");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void generate_malformedJson_retriesOnceThenThrowsClearError() {
        when(aiService.prompt(anyString())).thenReturn("this is not json at all");

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.TEXT, "java", Difficulty.EASY, 1);

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOf(AiGenerationValidationException.class)
                .hasMessageContaining("not valid JSON");
        verify(aiService, times(2)).prompt(anyString());
    }

    @Test
    void generate_groupType_rejectedBeforeCallingAi() {
        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.GROUP, "java", Difficulty.EASY, 1);

        assertThatThrownBy(() -> service.generate(req))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void generate_countGreaterThanOne_callsAiOncePerRequestedQuestion() {
        when(aiService.prompt(anyString())).thenReturn("""
                {"title": "Explain X", "body": "Describe X."}
                """);

        GenerateQuestionRequest req = new GenerateQuestionRequest(QuestionType.TEXT, "java", Difficulty.EASY, 3);
        var drafts = service.generate(req);

        assertThat(drafts).hasSize(3);
        verify(aiService, times(3)).prompt(anyString());
    }
}
