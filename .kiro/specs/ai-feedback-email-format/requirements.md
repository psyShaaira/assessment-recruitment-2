# Requirements Document

## Introduction

The candidate feedback email (sent via `FeedbackEmailService.sendFeedbackEmail`) is currently rendered
by `FeedbackEmailServiceImpl.renderBody()` using a fully static, deterministic Java template — there is
no AI call anywhere in the `feedbackemail` package today. This feature replaces that static body with
an AI-generated body as the primary path, keeping the current static template as an automatic fallback.

The intent and required behavior are derived from `feedback_email_format.md`, which describes (via a
Spring AI `ChatClient` / `StructuredOutputValidationAdvisor` example) that the generated email should:
personalize the greeting, acknowledge the candidate's effort and specific achievements, provide 2-3
specific actionable recommendations, and end with encouragement and next steps, produced from the
assessment score, topics covered, strengths, and improvement areas.

That exact mechanism is **not applicable to this codebase**: there is no `spring-ai` dependency, no
`ChatClient`, and no structured-output entity binding anywhere in `recruitment-be`. The project's only
AI abstraction is `com.psybergate.recruitment.ai.AiService`, which exposes plain-text
`prompt(String)`/`promptForJson(String)` methods backed by a Groq HTTP client. This document adapts the
*intent* of `feedback_email_format.md` (personalized, validated, retried AI-generated feedback content)
to that plain-text abstraction, following the existing retry/validation convention already used by
`QuestionGenerationServiceImpl` (prompt → parse/validate → retry once with corrective feedback → give up).

**Documentation discrepancy note**: `.kiro/specs/ai-feedback-email/` (requirements.md, design.md,
tasks.md — all tasks marked complete) and `openspec/changes/ai-feedback-email/` both describe this same
AI-generation work as already implemented (`generateAiBody()`, `buildEmailPrompt()`, `AiService`
injection into `FeedbackEmailServiceImpl`, static template renamed to `renderStaticBody()`). This is
inaccurate — none of that code exists in `recruitment-be/src/main/java/com/psybergate/recruitment/feedbackemail/`
today; `FeedbackEmailServiceImpl` only contains the static `renderBody()` path. This spec supersedes
`.kiro/specs/ai-feedback-email/` as the authoritative source of requirements for this behavior. Neither
the stale Kiro spec nor the stale OpenSpec change directory is modified as part of this workflow phase;
reconciling or archiving them is a follow-up action outside this document's scope.

## Glossary

- **FeedbackEmailService**: The existing service (`FeedbackEmailServiceImpl`) that renders and sends the
  candidate feedback email for a fully-marked submission, gated on submission status and an existing
  `SubmissionFeedbackReport`.
- **AiService**: The existing AI abstraction (`com.psybergate.recruitment.ai.AiService`) providing
  `prompt(String)`, used to obtain a plain-text response from the configured Groq model.
- **Feedback_Prompt**: The plain-text prompt string that FeedbackEmailService builds from the
  candidate's first name, the assessment title, the whole-number score percentage, the topic names with
  their strengths, the topic names with their improvement areas, and the `nextSteps` list from the
  submission's `FeedbackReportContent`, instructing the AI to produce a personalized, encouraging,
  plain-text feedback email body.
- **AI_Body**: The plain-text candidate feedback email body returned by AiService in response to a
  Feedback_Prompt.
- **Static_Body**: The deterministic, template-rendered candidate feedback email body produced by the
  pre-existing `renderBody()` logic (candidate greeting, score sentence, strengths/weaknesses sentences,
  next-steps bullets, sign-off), used unchanged as the fallback body.
- **Generation_Attempt**: One request/response cycle in which FeedbackEmailService sends a
  Feedback_Prompt to AiService and evaluates the resulting AI_Body against the structural validation
  rules in Requirement 3.
- **Candidate_Feedback_Email**: The email ultimately sent to the candidate via
  `EmailService.sendFeedbackReport`, whose body is either a validated AI_Body or, on exhausted retries or
  AI failure, the Static_Body.

## Requirements

### Requirement 1: AI-Generated Body as the Primary Path

**User Story:** As a candidate, I want the feedback email I receive to read like a personalized message
rather than a rigid template, so that the feedback feels relevant and encouraging.

#### Acceptance Criteria

1. WHEN FeedbackEmailService renders the body for a Candidate_Feedback_Email, THE FeedbackEmailService
   SHALL first attempt to obtain an AI_Body from AiService before falling back to the Static_Body.
2. THE Feedback_Prompt SHALL be built from the candidate's first name, the assessment title, the
   whole-number score percentage, the topic names paired with their recorded strengths, the topic names
   paired with their recorded improvement areas, and the `nextSteps` entries from the submission's
   `FeedbackReportContent`.
3. THE Feedback_Prompt SHALL instruct the AI to produce a plain-text email body that opens with a
   personalized greeting, acknowledges the candidate's effort and specific achievements, provides 2 to 3
   specific actionable recommendations, and closes with encouragement and next steps.
4. THE Feedback_Prompt SHALL instruct the AI to return plain text only, with no markdown formatting, and
   to sign off as "The Psybergate Recruitment Team".
5. IF a Generation_Attempt produces an AI_Body that passes the structural validation rules in
   Requirement 3, THEN THE FeedbackEmailService SHALL use that AI_Body as the Candidate_Feedback_Email
   body and SHALL NOT invoke the Static_Body rendering logic.

### Requirement 2: PII Minimization in the Prompt

**User Story:** As a recruiting operations owner, I want candidate personal information sent to the
third-party AI provider to be minimized, so that the platform limits exposure of candidate PII.

#### Acceptance Criteria

1. THE Feedback_Prompt SHALL include the candidate's first name and SHALL NOT include the candidate's
   email address or last name.
2. THE Feedback_Prompt SHALL NOT include any submission identifier, candidate identifier, or other
   internal database identifier.

### Requirement 3: Structural Validation and Retry of AI Output

**User Story:** As a recruiter, I want the system to reject unusable AI output and retry before falling
back, so that candidates receive a well-formed email even when a single AI response is malformed.

#### Acceptance Criteria

1. WHEN a Generation_Attempt returns an AI_Body, THE FeedbackEmailService SHALL reject that AI_Body IF
   it is blank, OR does not contain the candidate's first name, OR does not contain the sign-off text
   "The Psybergate Recruitment Team", OR contains a markdown formatting marker (any of `#`, `*`, `` ` ``,
   or `_` used for emphasis or headings).
2. IF a Generation_Attempt's AI_Body is rejected under Requirement 3.1 AND fewer than 3 total
   Generation_Attempts have been made for the Candidate_Feedback_Email, THEN THE FeedbackEmailService
   SHALL make one additional Generation_Attempt, including the previous rejection reason in the
   retried Feedback_Prompt.
3. IF all 3 Generation_Attempts for a Candidate_Feedback_Email are rejected under Requirement 3.1, THEN
   THE FeedbackEmailService SHALL use the Static_Body as the Candidate_Feedback_Email body.

### Requirement 4: Fallback on AI Failure

**User Story:** As a recruiter, I want the feedback email to still be sent even when the AI provider is
unavailable or errors out, so that a candidate is never left without feedback because of an AI outage.

#### Acceptance Criteria

1. IF a Generation_Attempt raises an exception from AiService (including but not limited to
   AiCommunicationException, AiTimeoutException, AiRateLimitException, AiAuthenticationException, or
   AiResponseException), THEN THE FeedbackEmailService SHALL treat that Generation_Attempt as rejected
   and proceed exactly as it does for a Requirement 3.1 structural rejection (retry, then fall back per
   Requirement 3.2 and 3.3).
2. IF the Static_Body is used as the Candidate_Feedback_Email body due to Requirement 3.3 or 4.1, THEN
   THE FeedbackEmailService SHALL still send the Candidate_Feedback_Email via
   `EmailService.sendFeedbackReport` and SHALL NOT surface an AI-specific error to the caller of
   `sendFeedbackEmail`.
3. THE Static_Body rendering logic SHALL remain behaviorally unchanged from the currently shipped
   `renderBody()` implementation (greeting, fixed intro line, score sentence, conditional
   strengths/weaknesses sentences, fixed transition line, verbatim next-steps bullets, encouraging
   sign-off sentence, signature).

### Requirement 5: Unchanged Send, Gating, Logging, and Subject Behavior

**User Story:** As a recruiter, I want the existing send-eligibility checks, send-history logging, and
email subject to keep working exactly as they do today, so that introducing AI generation does not
regress already-shipped behavior.

#### Acceptance Criteria

1. THE FeedbackEmailService SHALL continue to reject `sendFeedbackEmail` with the existing 404/409/404
   responses for a missing submission, a submission that is not `FULLY_MARKED`, and a missing
   `SubmissionFeedbackReport`, respectively, regardless of whether the body is later generated by AI or
   by the Static_Body fallback.
2. THE Candidate_Feedback_Email SHALL continue to use the fixed subject "Your Assessment Feedback" set by
   `EmailService.sendFeedbackReport`, regardless of whether the body is AI-generated or the Static_Body
   fallback.
3. WHEN a Candidate_Feedback_Email send succeeds, THE FeedbackEmailService SHALL continue to persist a
   `SENT` row in the feedback email send log exactly as it does today, regardless of whether the body was
   AI-generated or the Static_Body fallback.
4. IF the `SENT` row persistence in Requirement 5.3 fails after the Candidate_Feedback_Email was
   successfully delivered via `EmailService.sendFeedbackReport`, THEN THE FeedbackEmailService SHALL
   still treat `sendFeedbackEmail` as successful and SHALL NOT return an error response to the caller.
5. WHEN a Candidate_Feedback_Email send fails at the `EmailService.sendFeedbackReport` call, THE
   FeedbackEmailService SHALL continue to persist a `FAILED` row on a separate transaction and return a
   502 response exactly as it does today, regardless of whether the body was AI-generated or the
   Static_Body fallback.
