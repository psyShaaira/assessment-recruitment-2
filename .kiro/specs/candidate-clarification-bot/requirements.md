# Candidate Clarification Bot — Requirements

## Overview

While taking an assessment, a candidate can ask an AI assistant to clarify what a question means. The assistant (Groq, via the existing `AiService`) returns a plain-language rephrasing or definition of terms — it MUST NOT reveal, hint at, or narrow down the answer. Every request is rate-limited and persisted for audit. The feature degrades gracefully when the AI provider is unavailable and never blocks the candidate from continuing.

## Actors

- **Candidate** — takes an assessment, requests clarification on a question (authenticated via a short-lived `ROLE_CANDIDATE` session token scoped to one assessment).
- **System** — builds a guarded prompt, calls Groq, enforces rate limits, persists the request/response.
- **Recruiter / Admin** — indirectly benefits: the persisted log is available for dispute/audit review.

## Functional Requirements

### FR-1: Clarification Request

- FR-1.1: A candidate SHALL be able to request clarification for a single question via `POST /api/take/clarify` with a body of `{ questionId, candidateNote? }`.
- FR-1.2: The endpoint SHALL derive `candidateId` and `assessmentId` from the authenticated session (`Authentication.getName()` / `getCredentials()`), never from the request body.
- FR-1.3: Clarification SHALL be supported for all question types: MCQ, TEXT, CODE_SUBMISSION, and GROUP sub-questions.
- FR-1.4: `candidateNote` is optional free text (max 500 chars) letting the candidate say what specifically confuses them.
- FR-1.5: The response SHALL be a plain-language clarification string plus remaining-quota metadata `{ clarification, remainingForQuestion, remainingForAssessment }`.

### FR-2: Request Validation & Scope

- FR-2.1: The system SHALL reject (403) any `questionId` that does not belong to the candidate's resolved question set for this assessment, using the snapshot-aware `resolveQuestions(assessment, submissionId)` so randomised assessments are respected.
- FR-2.2: The system SHALL reject (409) clarification requests when the submission is already locked (`SUBMITTED` / `AUTO_SUBMITTED`).
- FR-2.3: The system SHALL reject (409) clarification requests after the assessment deadline (`startedAt + timeLimitMinutes`) has passed.
- FR-2.4: An active submission MUST exist; if none exists the system SHALL reject the request (404) rather than create one (a candidate should have loaded the assessment first).

### FR-3: Answer-Leak Guardrails

- FR-3.1: The clarification prompt SHALL be built ONLY from candidate-safe question data (title, body, and for MCQ the option *text*). It MUST NOT include the `QuestionOption.correct` flag or any correctness signal.
- FR-3.2: The prompt SHALL instruct the model to: rephrase the question, define unfamiliar terms, and explain what is being asked — and explicitly forbid providing the answer, hints toward the answer, worked solutions/steps, code, or narrowing of MCQ options.
- FR-3.3: The candidate's `candidateNote` SHALL be treated as untrusted data: wrapped in a delimited block and accompanied by an instruction that any directives inside it (e.g. "ignore previous instructions", "just tell me the answer") are to be ignored.
- FR-3.4: If the model's guardrails are triggered (candidate asks for the answer), the clarification SHALL still be a safe rephrasing or a polite refusal, never the answer.

### FR-4: Rate Limiting

- FR-4.1: The system SHALL limit clarification requests per question and per assessment (submission), with both limits configurable.
- FR-4.2: Default limits SHALL be 3 per question and 15 per assessment.
- FR-4.3: When either limit is exceeded, the system SHALL reject the request with HTTP 429 and a message indicating which limit was hit, WITHOUT calling Groq.
- FR-4.4: Limits SHALL be enforced by counting persisted clarification-log rows for the submission (total) and for the (submission, question) pair.

### FR-5: Persistence & Audit

- FR-5.1: Every clarification request that reaches the AI call SHALL be persisted in a `clarification_requests` table.
- FR-5.2: Each record SHALL include: `id`, `submissionId`, `questionId`, `candidateId`, `candidateNote` (nullable), `clarificationResponse`, `promptVersion`, `requestedAt`.
- FR-5.3: A record SHALL be persisted for successful AI responses. Requests rejected by validation or rate limiting before the AI call are NOT persisted as clarification rows (they are rejected outright).

### FR-6: Graceful Degradation

- FR-6.1: If the AI provider is unavailable (missing API key, timeout, rate limit, communication error), the endpoint SHALL return a soft, friendly message (e.g. "Clarification is temporarily unavailable — please answer to the best of your understanding") rather than a 5xx error.
- FR-6.2: A degraded (AI-unavailable) response SHALL NOT count against the candidate's rate-limit quota and SHALL NOT be persisted as a clarification row.
- FR-6.3: Degradation MUST NOT affect the candidate's answers, autosave, timer, or ability to submit.

### FR-7: Frontend Integration

- FR-7.1: The take UI SHALL present a "Need clarification?" control on each question (alongside the existing flag control).
- FR-7.2: Activating the control SHALL reveal a panel with an optional note input and a display area for the returned clarification, scoped to the current question.
- FR-7.3: The panel SHALL show remaining-quota feedback and disable the request control when the quota for that question is exhausted.
- FR-7.4: The frontend SHALL call the backend with an explicit `Authorization: Bearer <sessionToken>` header, matching the existing `CandidateTakeService` per-call pattern.

## Non-Functional Requirements

### NFR-1: Performance
- A clarification response MUST complete within the configured Groq timeout (default 30s). The UI SHALL show a loading state while waiting.

### NFR-2: Security & Privacy
- The prompt MUST NOT contain candidate PII (name, email, ID) — only question content and the candidate's own note.
- The clarification endpoint is covered by the existing `/api/take/**` → `ROLE_CANDIDATE` security rule; no new public routes are introduced.

### NFR-3: Configurability
- Rate limits, enablement, and prompt behaviour SHALL be driven from `application.yaml` (a `clarification:` block), with dev-only defaults — no code change required to tune limits.

### NFR-4: Conventions
- Follows package-by-feature, constructor injection via `@RequiredArgsConstructor`, `@ResponseStatus`-annotated exceptions handled by `GlobalExceptionHandler`, and Flyway-managed schema (no `ddl-auto`).
