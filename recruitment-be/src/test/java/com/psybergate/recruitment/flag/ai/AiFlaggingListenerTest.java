package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.take.SubmissionCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiFlaggingListenerTest {

    @Mock
    private AiFlaggingService aiFlaggingService;

    @Mock
    private AiFlaggingProperties properties;

    @InjectMocks
    private AiFlaggingListener listener;

    @Test
    void onSubmissionCompleted_whenAiEnabled_callsAnalyze() {
        UUID submissionId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        var event = new SubmissionCompletedEvent(submissionId, assessmentId);

        when(properties.aiEnabled()).thenReturn(true);

        listener.onSubmissionCompleted(event);

        verify(aiFlaggingService).analyze(submissionId);
    }

    @Test
    void onSubmissionCompleted_whenAiDisabled_doesNotCallAnalyze() {
        UUID submissionId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        var event = new SubmissionCompletedEvent(submissionId, assessmentId);

        when(properties.aiEnabled()).thenReturn(false);

        listener.onSubmissionCompleted(event);

        verifyNoInteractions(aiFlaggingService);
    }

    @Test
    void onSubmissionCompleted_whenAnalyzeThrows_doesNotPropagate() {
        UUID submissionId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        var event = new SubmissionCompletedEvent(submissionId, assessmentId);

        when(properties.aiEnabled()).thenReturn(true);
        doThrow(new RuntimeException("Groq timeout")).when(aiFlaggingService).analyze(submissionId);

        // Should not throw — exception is caught and logged
        listener.onSubmissionCompleted(event);

        verify(aiFlaggingService).analyze(submissionId);
    }
}
