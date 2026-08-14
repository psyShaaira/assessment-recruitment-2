# Implementation Plan: Submission Feedback Report

## Overview

Add `com.psybergate.recruitment.feedback` package — Flyway migration, JPA entity, repository, DTOs, service + impl, controller — plus small additive changes to the `ai/` package for JSON-constrained output mode.

---

## Tasks

- [x] 1. Flyway migration V23
  - [x] 1.1 Create `V23__add_submission_feedback_reports.sql`
    - Table `submission_feedback_reports` with columns: id, submission_id (FK → candidate_submissions), content (TEXT), ai_generated (BOOLEAN DEFAULT TRUE), prompt_version (VARCHAR), generated_at (TIMESTAMPTZ), generated_by (UUID NULL)
    - Unique index on `submission_id`
    - _Requirements: 1.1, 1.2_

- [x] 2. Domain and repository
  - [x] 2.1 Create `feedback/domain/SubmissionFeedbackReport.java`
    - JPA entity, `@Getter @Setter @NoArgsConstructor`, maps `submission_feedback_reports`
    - _Requirements: 1.1_
  - [x] 2.2 Create `feedback/repository/SubmissionFeedbackReportRepository.java`
    - `findBySubmissionId(UUID)` returning `Optional<SubmissionFeedbackReport>`
    - _Requirements: 1.3, 3.1_

- [x] 3. DTOs
  - [x] 3.1 Create `FeedbackTopicDto`, `FeedbackReportContent`, `FeedbackReportResponse` records
    - _Requirements: 2.8, 3.1_

- [x] 4. AI plumbing — JSON output mode
  - [x] 4.1 Add `responseFormat` field to `GroqChatRequest` with `@JsonProperty("response_format")` and `@JsonInclude(NON_NULL)`
    - Add `withJsonObjectFormat()` convenience method
    - _Requirements: 5.1_
  - [x] 4.2 Add `sendPromptForJson(String)` to `AiClient` interface
    - _Requirements: 5.2_
  - [x] 4.3 Add `promptForJson(String)` to `AiService` interface and `AiServiceImpl`
    - Reuse shared `validate()` helper; delegates to `aiClient.sendPromptForJson(prompt)`
    - _Requirements: 5.2, 5.3_
  - [x] 4.4 Implement `sendPromptForJson` in `GroqClient`
    - Refactor into `doSend(String prompt, boolean jsonMode)` private method
    - When `jsonMode=true`, call `request.withJsonObjectFormat()` before POST
    - _Requirements: 5.1, 5.2_

- [x] 5. Service
  - [x] 5.1 Create `FeedbackReportService` interface
    - `generate(UUID submissionId, UUID requestedBy)` and `getExisting(UUID submissionId)`
    - _Requirements: 2.1, 3.1_
  - [x] 5.2 Implement `FeedbackReportServiceImpl`
    - Inject `SubmissionService`, `AiService`, `SubmissionFeedbackReportRepository`, `QuestionRepository`, `ObjectMapper`
    - `generate()`: load result → fetch tags → build prompt (PII-stripped, partial-marking note) → `promptForJson()` → parse → upsert → return response
    - `getExisting()`: fetch or 404
    - `parseContent()`: catch `JsonProcessingException` → throw `AiResponseException`
    - _Requirements: 2.1–2.9, 3.1, 3.2, 6.1_

- [x] 6. Controller
  - [x] 6.1 Create `FeedbackReportController`
    - `POST /api/submissions/{submissionId}/feedback-report` → `generate()`
    - `GET  /api/submissions/{submissionId}/feedback-report` → `getExisting()`
    - `@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")`
    - _Requirements: 4.1, 4.2_

- [x] 7. Tests
  - [x] 7.1 `FeedbackReportServiceImplTest`
    - Fully-marked → report returned; partially-marked → prompt notes unscored gap; PII check on prompt; malformed JSON → `AiResponseException`; upsert uses same entity instance
    - _Requirements: 2.4, 2.5, 2.9, 3.2, 6.2_
  - [x] 7.2 `FeedbackReportControllerTest` (`@WebMvcTest`)
    - RECRUITER/ADMIN → 200; CANDIDATE → 403; unauthenticated → 401
    - _Requirements: 4.1, 4.2_
  - [x] 7.3 Add `promptForJson` coverage to `AiServiceImplTest`
    - Valid prompt delegates to `sendPromptForJson`; null/blank throws before delegation
    - _Requirements: 5.2, 5.3_

---

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "2.2", "3.1", "4.1"] },
    { "id": 2, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 3, "tasks": ["5.1"] },
    { "id": 4, "tasks": ["5.2"] },
    { "id": 5, "tasks": ["6.1"] },
    { "id": 6, "tasks": ["7.1", "7.2", "7.3"] }
  ]
}
```
