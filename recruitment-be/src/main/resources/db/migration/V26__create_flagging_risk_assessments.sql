CREATE TABLE flagging_risk_assessments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   UUID NOT NULL UNIQUE REFERENCES candidate_submissions(id) ON DELETE CASCADE,
    risk            VARCHAR(10) NOT NULL,
    reasons         TEXT NOT NULL,
    rationale       TEXT NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    analyzed_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    prompt_version  VARCHAR(10) NOT NULL,
    flag_created    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_flagging_risk_assessments_risk
    ON flagging_risk_assessments(risk) WHERE risk IN ('HIGH', 'MEDIUM');
