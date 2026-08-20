package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.take.SubmissionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiFlaggingListener {

    private final AiFlaggingService aiFlaggingService;
    private final AiFlaggingProperties properties;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmissionCompleted(SubmissionCompletedEvent event) {
        if (!properties.aiEnabled()) {
            log.debug("AI flagging disabled — skipping analysis for submission {}", event.submissionId());
            return;
        }
        try {
            aiFlaggingService.analyze(event.submissionId());
        } catch (Exception e) {
            log.error("AI flagging analysis failed for submission {}: {}", event.submissionId(), e.getMessage(), e);
        }
    }
}
