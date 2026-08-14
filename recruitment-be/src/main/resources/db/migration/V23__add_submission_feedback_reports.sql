-- Feedback report generated per candidate submission via AI

CREATE TABLE submission_feedback_reports (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    submission_id   UUID        NOT NULL REFERENCES candidate_submissions(id) ON DELETE CASCADE,
    content         TEXT        NOT NULL,
    ai_generated    BOOLEAN     NOT NULL DEFAULT TRUE,
    prompt_version  VARCHAR     NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL,
    generated_by    UUID        NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_submission_feedback_reports_submission_id
    ON submission_feedback_reports (submission_id);
