package com.psybergate.recruitment.take.clarify;

import com.psybergate.recruitment.ai.AiCommunicationException;
import com.psybergate.recruitment.ai.AiService;
import com.psybergate.recruitment.domain.QuestionType;
import com.psybergate.recruitment.take.CandidateTakeService;
import com.psybergate.recruitment.take.CandidateTakeService.ClarificationTarget;
import com.psybergate.recruitment.take.clarify.domain.ClarificationRequest;
import com.psybergate.recruitment.take.clarify.dto.ClarificationRequestDto;
import com.psybergate.recruitment.take.clarify.dto.ClarificationResponse;
import com.psybergate.recruitment.take.clarify.repository.ClarificationRequestRepository;
import com.psybergate.recruitment.take.dto.TakeQuestionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClarificationServiceImplTest {

    @Mock private CandidateTakeService takeService;
    @Mock private ClarificationRequestRepository requestRepository;
    @Mock private ClarificationPromptBuilder promptBuilder;
    @Mock private AiService aiService;

    private final UUID candidateId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID questionId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    private ClarificationRequestDto request;
    private TakeQuestionDto question;

    @BeforeEach
    void setUp() {
        request = new ClarificationRequestDto(questionId, "what does this mean?");
        question = new TakeQuestionDto(questionId, 0, QuestionType.TEXT,
                "Title", "Body", 10, null, null);
    }

    private ClarificationServiceImpl serviceWith(boolean enabled, int maxPerQuestion, int maxPerAssessment) {
        ClarificationProperties props = new ClarificationProperties(enabled, maxPerQuestion, maxPerAssessment);
        return new ClarificationServiceImpl(takeService, requestRepository, promptBuilder, props, aiService);
    }

    private void stubResolve() {
        when(takeService.resolveQuestionForClarification(candidateId, assessmentId, questionId))
                .thenReturn(new ClarificationTarget(submissionId, question));
    }

    @Test
    void happyPathPersistsAndReturnsDecrementedQuota() {
        ClarificationServiceImpl service = serviceWith(true, 3, 15);
        stubResolve();
        when(requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId)).thenReturn(0L);
        when(requestRepository.countBySubmissionId(submissionId)).thenReturn(0L);
        when(promptBuilder.build(question, "what does this mean?")).thenReturn("PROMPT");
        when(aiService.prompt("PROMPT")).thenReturn("This question asks you to explain the concept.");

        ClarificationResponse response = service.clarify(candidateId, assessmentId, request);

        assertThat(response.degraded()).isFalse();
        assertThat(response.clarification()).isEqualTo("This question asks you to explain the concept.");
        assertThat(response.remainingForQuestion()).isEqualTo(2);
        assertThat(response.remainingForAssessment()).isEqualTo(14);

        ArgumentCaptor<ClarificationRequest> captor = ArgumentCaptor.forClass(ClarificationRequest.class);
        verify(requestRepository).save(captor.capture());
        ClarificationRequest saved = captor.getValue();
        assertThat(saved.getSubmissionId()).isEqualTo(submissionId);
        assertThat(saved.getQuestionId()).isEqualTo(questionId);
        assertThat(saved.getCandidateId()).isEqualTo(candidateId);
        assertThat(saved.getCandidateNote()).isEqualTo("what does this mean?");
        assertThat(saved.getClarificationResponse()).isEqualTo("This question asks you to explain the concept.");
        assertThat(saved.getPromptVersion()).isEqualTo(ClarificationPromptBuilder.PROMPT_VERSION);
    }

    @Test
    void guardExceptionPropagates() {
        ClarificationServiceImpl service = serviceWith(true, 3, 15);
        when(takeService.resolveQuestionForClarification(candidateId, assessmentId, questionId))
                .thenThrow(new IllegalStateException("out of scope"));

        assertThatThrownBy(() -> service.clarify(candidateId, assessmentId, request))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(aiService);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void perQuestionLimitReachedThrowsAndSkipsAi() {
        ClarificationServiceImpl service = serviceWith(true, 3, 15);
        stubResolve();
        when(requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId)).thenReturn(3L);
        when(requestRepository.countBySubmissionId(submissionId)).thenReturn(3L);

        assertThatThrownBy(() -> service.clarify(candidateId, assessmentId, request))
                .isInstanceOf(ClarificationRateLimitException.class)
                .hasMessageContaining("per-question");

        verifyNoInteractions(aiService);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void perAssessmentLimitReachedThrowsAndSkipsAi() {
        ClarificationServiceImpl service = serviceWith(true, 3, 15);
        stubResolve();
        when(requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId)).thenReturn(0L);
        when(requestRepository.countBySubmissionId(submissionId)).thenReturn(15L);

        assertThatThrownBy(() -> service.clarify(candidateId, assessmentId, request))
                .isInstanceOf(ClarificationRateLimitException.class)
                .hasMessageContaining("per-assessment");

        verifyNoInteractions(aiService);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void aiFailureReturnsDegradedWithoutPersistingOrConsumingQuota() {
        ClarificationServiceImpl service = serviceWith(true, 3, 15);
        stubResolve();
        when(requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId)).thenReturn(1L);
        when(requestRepository.countBySubmissionId(submissionId)).thenReturn(2L);
        when(promptBuilder.build(any(), anyString())).thenReturn("PROMPT");
        when(aiService.prompt("PROMPT")).thenThrow(new AiCommunicationException("provider down"));

        ClarificationResponse response = service.clarify(candidateId, assessmentId, request);

        assertThat(response.degraded()).isTrue();
        // Quota reflects current usage, NOT decremented (nothing consumed)
        assertThat(response.remainingForQuestion()).isEqualTo(2);   // 3 - 1
        assertThat(response.remainingForAssessment()).isEqualTo(13); // 15 - 2
        verify(requestRepository, never()).save(any());
    }

    @Test
    void disabledReturnsDegradedWithoutCallingAi() {
        ClarificationServiceImpl service = serviceWith(false, 3, 15);
        stubResolve();
        when(requestRepository.countBySubmissionIdAndQuestionId(submissionId, questionId)).thenReturn(0L);
        when(requestRepository.countBySubmissionId(submissionId)).thenReturn(0L);

        ClarificationResponse response = service.clarify(candidateId, assessmentId, request);

        assertThat(response.degraded()).isTrue();
        verifyNoInteractions(aiService, promptBuilder);
        verify(requestRepository, never()).save(any());
    }
}
