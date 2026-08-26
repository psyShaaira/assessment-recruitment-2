# Bugfix Requirements Document

## Introduction

The candidate feedback email's primary body-generation path — the AI-generated body produced by
`FeedbackEmailBodyGenerator.generateBody(...)` (see `.kiro/specs/ai-feedback-email-format/`) — can
be sent to a candidate without ever stating or highlighting their percentage mark for the
assessment. The percentage is included as context in the `Feedback_Prompt` ("They scored X%
overall."), but the instructional part of the prompt never tells the AI to include that figure in
the email it writes, and `validate(String aiBody, String candidateFirstName)` never checks whether
the returned `AI_Body` actually mentions the score. As a result, an `AI_Body` that omits the
percentage still passes structural validation and is sent as-is.

This is a regression relative to the deterministic `Static_Body` fallback
(`renderStaticBody(...)`), which always includes a score sentence ("You scored X% overall.") per
`.kiro/specs/candidate-feedback-email/requirements.md` (Requirement 2.9). The fix must ensure every
sent candidate feedback email — whether AI-generated or the static fallback — clearly states the
candidate's percentage mark, by (a) explicitly instructing the AI to include it in the
`Feedback_Prompt`, and (b) extending structural validation to reject an `AI_Body` that omits it,
so an omission is retried and, if still omitted after all attempts, falls back to the
`Static_Body` exactly as any other validation failure does today. All other AI-generation, retry,
fallback, gating, logging, and subject behavior described in
`.kiro/specs/ai-feedback-email-format/requirements.md` must remain unchanged.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `FeedbackEmailBodyGenerator.buildPrompt(...)` builds the `Feedback_Prompt`, THE system
includes the candidate's whole-number score percentage as context ("They scored X% overall.") but
does not instruct the AI to state or highlight that percentage in the email body it produces.

1.2 WHEN a `Generation_Attempt` returns an `AI_Body` that does not contain the candidate's
whole-number score percentage, THE system's `validate(String aiBody, String candidateFirstName)`
does not check for the percentage's presence, so the `AI_Body` can still pass validation (if it
is non-blank, contains the candidate's first name, contains the sign-off, and contains no
markdown markers) and is used as the `Candidate_Feedback_Email` body even though it omits the
score.

### Expected Behavior (Correct)

2.1 WHEN `FeedbackEmailBodyGenerator.buildPrompt(...)` builds the `Feedback_Prompt`, THE system
SHALL explicitly instruct the AI to state the candidate's whole-number score percentage in the
email body it produces.

2.2 WHEN a `Generation_Attempt` returns an `AI_Body` that does not contain the candidate's
whole-number score percentage (rendered as a percentage figure, e.g. "42%"), THE system SHALL
reject that `AI_Body` via `validate(...)`, using a rejection reason that names the missing
percentage, and SHALL treat the rejection identically to any other Requirement 3.1 structural
rejection from `.kiro/specs/ai-feedback-email-format/requirements.md` (eligible for retry per
Requirement 3.2, or fallback to the `Static_Body` per Requirement 3.3 once 3 total attempts are
exhausted).

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a `Generation_Attempt`'s `AI_Body` contains the candidate's whole-number score
percentage AND otherwise satisfies the existing structural validation rules (non-blank, contains
the candidate's first name, contains the sign-off "The Psybergate Recruitment Team", and contains
no markdown formatting marker), THE system SHALL CONTINUE TO accept that `AI_Body` and use it as
the `Candidate_Feedback_Email` body without triggering an additional retry.

3.2 WHEN the system falls back to the `Static_Body` (per Requirement 3.3 of
`ai-feedback-email-format`), THE system SHALL CONTINUE TO include the score sentence ("You scored
X% overall.") in the `Static_Body`, exactly as it does today.

3.3 WHEN a `Generation_Attempt`'s `AI_Body` is rejected for any of the pre-existing reasons
(blank, missing candidate first name, missing sign-off, or containing a markdown marker), THE
system SHALL CONTINUE TO treat it as rejected and retry or fall back exactly as it does today,
regardless of whether the `AI_Body` also happens to omit the percentage.

3.4 WHEN fewer than 3 total `Generation_Attempts` have been made for a `Candidate_Feedback_Email`
and an attempt is rejected (for any reason, including a missing percentage), THE system SHALL
CONTINUE TO make exactly one additional `Generation_Attempt`, including the previous rejection
reason in the retried `Feedback_Prompt`, per Requirement 3.2 of `ai-feedback-email-format`.

3.5 WHEN all 3 `Generation_Attempts` for a `Candidate_Feedback_Email` are rejected, THE system
SHALL CONTINUE TO use the `Static_Body` as the `Candidate_Feedback_Email` body and SHALL NOT
throw, per Requirements 3.3 and 4.2 of `ai-feedback-email-format`.

3.6 THE `Feedback_Prompt` SHALL CONTINUE TO include the candidate's whole-number score percentage
as context ("They scored X% overall."), in addition to now being explicitly instructed to restate
it in the produced email body.

3.7 THE `Feedback_Prompt` SHALL CONTINUE TO include the candidate's first name, assessment title
(when available), topic names paired with strengths, topic names paired with weaknesses, and
`nextSteps` entries, and SHALL CONTINUE TO exclude the candidate's last name, email address,
submission identifier, and candidate identifier, per Requirements 1.2, 2.1, and 2.2 of
`ai-feedback-email-format`.

3.8 THE existing 404/409 send-eligibility gating, `SENT`/`FAILED` send-log persistence, the fixed
email subject "Your Assessment Feedback", and the 502 path on `EmailService` failure SHALL
CONTINUE TO behave exactly as described in Requirement 5 of `ai-feedback-email-format`, regardless
of this fix.
