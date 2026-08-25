CREATE TABLE clarification_requests (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id          UUID NOT NULL REFERENCES candidate_submissions(id) ON DELETE CASCADE,
    question_id            UUID NOT NULL,
    candidate_id           UUID NOT NULL,
    candidate_note         TEXT,
    clarification_response TEXT NOT NULL,
    prompt_version         VARCHAR(16) NOT NULL,
    requested_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Rate-limit count queries: total-per-submission and per (submission, question).
CREATE INDEX idx_clarification_submission
    ON clarification_requests(submission_id);
CREATE INDEX idx_clarification_submission_question
    ON clarification_requests(submission_id, question_id);
