package com.psybergate.recruitment.take;

import java.util.UUID;

/**
 * Application event published when a candidate submission is completed.
 * Consumed by async listeners (e.g. AI auto-flagging) after the transaction commits.
 */
public record SubmissionCompletedEvent(UUID submissionId, UUID assessmentId) {}
