-- AI-generated marking suggestion (score + rationale) for a candidate answer

CREATE TABLE ai_marking_suggestions (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    candidate_answer_id UUID        NOT NULL,
    score               INTEGER     NOT NULL,
    rationale           TEXT        NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ai_marking_suggestions_pk                  PRIMARY KEY (id),
    CONSTRAINT uq_ai_marking_suggestions_candidate_answer UNIQUE (candidate_answer_id),
    CONSTRAINT ai_marking_suggestions_candidate_answer_fk FOREIGN KEY (candidate_answer_id)
        REFERENCES candidate_answers (id) ON DELETE CASCADE,
    CONSTRAINT ai_marking_suggestions_score_non_negative  CHECK (score >= 0)
);
