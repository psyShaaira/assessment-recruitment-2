-- Send-history log for candidate feedback-report emails (mirrors reminder_send_log)

CREATE TABLE feedback_email_send_log (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    submission_id   UUID        NOT NULL REFERENCES candidate_submissions (id) ON DELETE CASCADE,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_by         UUID        REFERENCES users (id),
    status          VARCHAR(10) NOT NULL,
    failure_reason  TEXT,
    CONSTRAINT feedback_email_send_log_pk PRIMARY KEY (id),
    CONSTRAINT feedback_email_send_log_status_check CHECK (status IN ('SENT', 'FAILED')),
    CONSTRAINT feedback_email_send_log_failure_reason_check CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL AND failure_reason <> '')
        OR
        (status = 'SENT' AND failure_reason IS NULL)
    )
);

CREATE INDEX idx_feedback_email_send_log_submission_id ON feedback_email_send_log (submission_id);
