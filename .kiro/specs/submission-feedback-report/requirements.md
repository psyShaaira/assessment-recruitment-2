# Requirements Document

## Introduction

This feature adds AI-generated feedback reports for candidate submissions. After a recruiter triggers generation, the backend calls Groq to produce a structured assessment of the candidate's performance — covering topic strengths/weaknesses and recommended next steps — and stores the result against the submission. Reports can be regenerated (overwrite) or retrieved read-only.

## Glossary

- **FeedbackReport**: The AI-generated structured document produced for a single submission.
- **FeedbackReportContent**: The JSON shape returned by Groq — `{overallSummary, topics[], nextSteps[]}`.
- **PromptVersion**: A string tag (e.g. `"v1"`) identifying which prompt template produced a report.
- **PII**: Personally identifiable information — candidate name and email, which must never be sent to Groq.
- **Partial marking**: A submission where not all questions have been scored yet.
- **Upsert**: One report per submission; regeneration overwrites the existing row.

---

## Requirements

### Requirement 1: Database Schema

**User Story:** As a system, I want a table to persist feedback reports so that generated reports survive restarts and can be retrieved without re-calling the AI.

#### Acceptance Criteria

1. THE system SHALL create a `submission_feedback_reports` table via Flyway migration `V23` with columns: `id` (UUID PK), `submission_id` (UUID FK → `candidate_submissions`, NOT NULL), `content` (TEXT NOT NULL), `ai_generated` (BOOLEAN NOT NULL DEFAULT TRUE), `prompt_version` (VARCHAR NOT NULL), `generated_at` (TIMESTAMPTZ NOT NULL), `generated_by` (UUID NULL).
2. THE table SHALL enforce a unique constraint on `submission_id` so that at most one report row exists per submission.
3. IF a report is regenerated, THE system SHALL update the existing row rather than inserting a new one.

---

### Requirement 2: Generate Feedback Report

**User Story:** As a recruiter, I want to generate an AI feedback report for a submission so that I can share structured, actionable feedback with the candidate.

#### Acceptance Criteria

1. WHEN a recruiter or admin POSTs to `/api/submissions/{submissionId}/feedback-report`, THE system SHALL call `SubmissionService.getResult()` to retrieve the full result summary.
2. THE system SHALL fetch question tags via `QuestionRepository` for all leaf questions in the result.
3. THE prompt sent to the AI provider SHALL include: assessment title, total score/max score, question titles, tags, candidate answer text, and score/maxScore per question.
4. THE prompt SHALL NOT include the candidate's name, email, or any other personally identifiable information.
5. WHEN the submission has unscored questions, THE prompt SHALL include a note stating how many of N questions are not yet scored and instruct the AI to base feedback only on scored responses.
6. THE system SHALL call `AiService.promptForJson()` with the constructed prompt so that the AI provider is constrained to return valid JSON.
7. WHEN the AI returns a valid JSON matching `FeedbackReportContent`, THE system SHALL parse it and persist a `SubmissionFeedbackReport` row (upsert).
8. THE endpoint SHALL return a `FeedbackReportResponse` with `submissionId`, `content`, `aiGenerated`, `promptVersion`, and `generatedAt`.
9. WHEN the AI returns malformed JSON, THE system SHALL throw `AiResponseException` with a non-blank message rather than propagating a raw `JsonProcessingException`.

---

### Requirement 3: Retrieve Existing Report

**User Story:** As a recruiter, I want to retrieve an already-generated report without re-calling the AI so that repeated views are fast and free.

#### Acceptance Criteria

1. WHEN a recruiter or admin GETs `/api/submissions/{submissionId}/feedback-report`, THE system SHALL return the persisted `FeedbackReportResponse` for that submission.
2. IF no report has been generated yet, THE system SHALL return HTTP 404.

---

### Requirement 4: Access Control

**User Story:** As a security administrator, I want feedback report endpoints restricted to staff roles so that candidates cannot access or trigger AI reports.

#### Acceptance Criteria

1. BOTH the `POST` and `GET` endpoints SHALL require the caller to hold the `RECRUITER` or `ADMIN` role.
2. IF the caller holds any other role (e.g. `CANDIDATE`) or is unauthenticated, THE system SHALL return HTTP 403 or 401 respectively.

---

### Requirement 5: AI Plumbing — Structured JSON Output

**User Story:** As a developer, I want the Groq client to support a JSON-constrained output mode so that feedback generation receives parseable structured data reliably.

#### Acceptance Criteria

1. `GroqChatRequest` SHALL support an optional `response_format` field serialised as `{"type": "json_object"}` when JSON mode is requested.
2. `AiClient` and `AiService` SHALL expose a `sendPromptForJson` / `promptForJson` method that sets this constraint.
3. Plain `sendPrompt` / `prompt` calls SHALL remain unaffected (no `response_format` field sent).

---

### Requirement 6: PII Protection

**User Story:** As a data protection officer, I want to ensure candidate personal data is never sent to a third-party AI provider.

#### Acceptance Criteria

1. THE prompt construction logic SHALL only include question titles, tags, answer text, and numeric scores — never candidate name or email.
2. IF a future change to the prompt builder would add PII fields, THE unit tests SHALL detect it via string-search assertions on the captured prompt argument.
