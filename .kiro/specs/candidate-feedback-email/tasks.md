# Implementation Plan: Candidate Feedback Email

## Overview

This plan implements the `feedbackemail` backend package: a new send-history table (`feedback_email_send_log`), a `FeedbackEmailService` that gates sends on marking status and report existence, renders report content into a plain-text body, delegates sending to an extended `EmailService`, records every outcome (success or failure) append-only, and exposes send/history REST endpoints restricted to `RECRUITER`/`ADMIN`. Java is the implementation language (the design document already uses concrete Java, not pseudocode).

Work proceeds bottom-up: schema/domain → repository/DTOs → `EmailService` extension → gating logic → rendering/addressing → outcome recording/history → controller wiring → integration tests for resend append-only behavior, access control, and schema constraints.

## Tasks

- [x] 1. Set up database schema and domain model
  - [x] 1.1 Create Flyway migration `V25__create_feedback_email_send_log.sql`
    - Add `feedback_email_send_log` table with `id`, `submission_id` (FK → `candidate_submissions`, `ON DELETE CASCADE`), `sent_at`, `sent_by` (FK → `users.id`, nullable), `status`, `failure_reason`
    - No unique constraint on `submission_id`
    - Add CHECK constraint pairing `status='FAILED'` with non-null/non-blank `failure_reason` and `status='SENT'` with null `failure_reason`
    - Add index on `submission_id`
    - _Requirements: 1.1, 1.2, 1.4_

  - [x] 1.2 Create `FeedbackEmailSendStatus` enum and `FeedbackEmailSendLog` entity
    - `FeedbackEmailSendStatus` with `SENT`/`FAILED` in `feedbackemail/domain/`
    - `FeedbackEmailSendLog` JPA entity mirroring `ReminderSendLog`'s shape (`@CreationTimestamp` on `sentAt`, raw UUID columns for `submissionId`/`sentBy`, `@Enumerated(EnumType.STRING)` status)
    - _Requirements: 1.1, 1.3_

- [x] 2. Create repository and DTOs
  - [x] 2.1 Create `FeedbackEmailSendLogRepository`
    - Extend `JpaRepository<FeedbackEmailSendLog, UUID>` with `findBySubmissionIdOrderBySentAtDesc(UUID)`
    - _Requirements: 1.1, 4.1_

  - [x] 2.2 Create `FeedbackEmailSendResponse` and `FeedbackEmailSendLogDto` records
    - `FeedbackEmailSendResponse(submissionId, status, sentAt)`
    - `FeedbackEmailSendLogDto(sentAt, status, sentBy, failureReason)` with a static `from(FeedbackEmailSendLog)` mapper
    - _Requirements: 2.24, 4.1_

- [x] 3. Extend `EmailService` for feedback report emails
  - [x] 3.1 Add `sendFeedbackReport(Candidate, String)` to `EmailService` and implement it in `EmailServiceImpl`
    - Follow the existing `SimpleMailMessage` pattern used by `sendInvitation`/`sendReminder`/`sendCancellation`/`sendContactMessage` (fixed subject, caller-supplied plain-text body)
    - _Requirements: 2.20_

  - [ ]* 3.2 Write unit test for `EmailServiceImpl.sendFeedbackReport`
    - Verify the `SimpleMailMessage` is addressed, subjected, and bodied correctly
    - _Requirements: 2.20_

- [ ] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement submission gating logic in `FeedbackEmailService`
  - [x] 5.1 Create `FeedbackEmailService` interface and `FeedbackEmailServiceImpl` with existence and marking-status gating
    - `sendFeedbackEmail(UUID submissionId, UUID sentBy)` calls `SubmissionService.getResult(submissionId)` (propagates 404 for unknown submission), then throws 409 if `markingStatus != FULLY_MARKED`; neither path writes a log row
    - _Requirements: 2.1, 2.2, 2.3_

  - [ ]* 5.2 Write property test for unknown submission rejection
    - **Property 1: Unknown submission is always rejected with 404, regardless of history**
    - **Validates: Requirements 2.2, 3.1**

  - [ ]* 5.3 Write property test for non-fully-marked rejection
    - **Property 2: Non-fully-marked submissions are always rejected with 409, regardless of history**
    - **Validates: Requirements 2.3, 3.1, 3.2, 6.3**

  - [x] 5.4 Implement report-existence check in `sendFeedbackEmail`
    - Query `SubmissionFeedbackReportRepository.findBySubmissionId(submissionId)`; throw 404 if absent, writing no log row
    - _Requirements: 2.4_

  - [ ]* 5.5 Write property test for missing report rejection
    - **Property 3: Missing report is always rejected with 404, regardless of history**
    - **Validates: Requirements 2.4, 3.1**

- [x] 6. Implement candidate resolution and email body rendering
  - [x] 6.1 Implement candidate name/email resolution from the submission
    - Load `CandidateSubmission` via `CandidateSubmissionRepository`, then `Candidate` via `CandidateRepository` using `candidateId`
    - _Requirements: 2.5_

  - [x] 6.2 Rework plain-text body rendering helper for the topic-name-listing format
    - Parse the report's stored JSON `content` into `FeedbackReportContent` (reusing the existing `ObjectMapper` + record), then compute `Strong_Topic` and `Weak_Topic` name lists by filtering `topics[]` for entries whose `strengths`/`weaknesses` field, respectively, is non-null and non-empty after trimming
    - Add a shared `joinWithAnd(List<String>)` helper: comma-separated for 3+ items with "and" (no preceding comma) before the last, "X and Y" for exactly two, the bare item for exactly one, and "" for an empty list (AC 2.10)
    - Render, in order: a greeting line "Hi `<firstName>`," (AC 2.7); the fixed intro line "Here is your feedback on your recent assessment:" (AC 2.8); a score sentence stating `round(totalScore / maxScore * 100)` as a whole-number percentage (AC 2.9); a strengths sentence naming every `Strong_Topic` via `joinWithAnd`, included only if the `Strong_Topic` list is non-empty (AC 2.11, 2.12); a weaknesses sentence naming every `Weak_Topic` via `joinWithAnd`, included only if the `Weak_Topic` list is non-empty (AC 2.13, 2.14) — a topic that is both a `Strong_Topic` and a `Weak_Topic` must appear, by name, in both sentences, since the two lists are computed independently with no deduplication (AC 2.15)
    - Append the fixed transition line (AC 2.16), then the full `nextSteps[]` rendered verbatim as bullets, unchanged (AC 2.17), then the encouraging sign-off sentence immediately before the signature (AC 2.18)
    - Ensure the body emits exactly this named element set in this order with no separate narrative paragraph synthesized from `overallSummary` or anything else (AC 2.19)
    - _Requirements: 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 2.15, 2.16, 2.17, 2.18, 2.19_

  - [ ]* 6.3 Write property test for rendered body structure
    - **Property 4: Rendered email body has the required structure, conditional strengths/weaknesses sentences, and preserves next steps verbatim**
    - Assert: greeting with first name; intro line immediately after; score sentence with the correct whole-number percentage; strengths sentence present if and only if a `Strong_Topic` exists, naming exactly that set joined per the comma/"and" rule; weaknesses sentence present if and only if a `Weak_Topic` exists under the identical rule; a topic that is both a `Strong_Topic` and a `Weak_Topic` appears in both sentences; transition line immediately before the next-steps bullets; every next step verbatim and in order; a non-empty sign-off immediately before the signature; and no non-blank body line falls outside this fixed element set
    - **Validates: Requirements 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13, 2.14, 2.15, 2.16, 2.17, 2.18, 2.19**

  - [ ]* 6.4 Write property test for correct email addressing
    - **Property 5: Email is always addressed to the submission's actual candidate**
    - **Validates: Requirements 2.5, 2.20**

- [ ] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement send outcome handling, logging, and history retrieval
  - [x] 8.1 Implement `EmailService` invocation with success/failure branching and log persistence
    - On success: save `FeedbackEmailSendLog` with `status=SENT`, `sentBy`, `sentAt`; return `FeedbackEmailSendResponse`
    - On exception: catch it, save `FeedbackEmailSendLog` with `status=FAILED` and a non-blank `failureReason` (fallback message if blank/null) in an isolated transaction (e.g. `REQUIRES_NEW`) so the row commits even though the method then rethrows as 502
    - _Requirements: 2.20, 2.21, 2.22, 2.23, 1.3_

  - [ ]* 8.2 Write property test for send outcome faithfully recorded
    - **Property 6: Send outcome is faithfully recorded and reported**
    - **Validates: Requirements 1.3, 2.1, 2.21, 2.22, 2.24**

  - [x] 8.4 Implement `getSendHistory` with existence check and DTO mapping
    - Verify submission exists via `CandidateSubmissionRepository.existsById`; throw 404 otherwise
    - Return `feedbackEmailSendLogRepository.findBySubmissionIdOrderBySentAtDesc(submissionId)` mapped via `FeedbackEmailSendLogDto.from`
    - _Requirements: 4.1, 4.2, 4.3_

  - [ ]* 8.3 Write property test for failed-send isolation
    - **Property 7: Failed sends are fully isolated to the send-log table**
    - **Validates: Requirements 2.23, 6.1, 6.2**

  - [ ]* 8.5 Write property test for GET unknown submission rejection
    - **Property 10: Unknown submission is always rejected with 404 on history retrieval**
    - **Validates: Requirements 4.2**

  - [ ]* 8.6 Write property test for send history retrieval correctness
    - **Property 9: Send history is returned complete, correctly mapped, and ordered**
    - **Validates: Requirements 4.1, 4.3**

- [ ] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement REST controller and wire endpoints
  - [x] 10.1 Create `FeedbackEmailController` with POST and GET endpoints
    - `@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")` at class level; `POST /api/submissions/{submissionId}/feedback-report/email` extracts `sentBy` from `Authentication` and delegates to `sendFeedbackEmail`; `GET` on the same path delegates to `getSendHistory`
    - _Requirements: 2.1, 4.1, 5.1_

  - [ ]* 10.2 Write unit test for a happy-path send and a concrete resend example
    - A fully-marked submission with an existing report sent successfully producing a `SENT` row and 200 response; a resend example where the first attempt fails and the second succeeds, asserting both rows exist with correct statuses in the right order
    - _Requirements: 2.21, 3.3_

- [ ] 11. Integration tests for resend append-only behavior, access control, and schema
  - [ ]* 11.1 Write property test for append-only resends against a real repository
    - **Property 8: Resends are append-only — prior send-log rows are never mutated**
    - **Validates: Requirements 1.2, 3.3**

  - [ ]* 11.2 Write integration tests for the access control matrix
    - POST and GET as `ADMIN`, `RECRUITER` (success), `CANDIDATE` (403), no auth (401), asserting no submission/send-log/report data leaks in error responses
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ]* 11.3 Write integration tests for the schema CHECK constraint and migration smoke test
    - Four example inserts via the repository: `(SENT, null)` succeeds, `(SENT, "reason")` fails, `(FAILED, "reason")` succeeds, `(FAILED, null)` fails; plus a smoke test confirming the `V25` migration applies cleanly and the table/columns/index exist
    - _Requirements: 1.1, 1.4_

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP; they are all test-writing tasks and are not required for the feature to function.
- Each task references specific requirements for traceability.
- Checkpoints ensure incremental validation as the feature is built bottom-up.
- Property tests (jqwik, ≥100 iterations) validate the ten universal correctness properties from the design; Properties 8 and 9 run against a real repository (TestContainers) per the design's testing strategy, while Properties 1–7 and 10 run against mocked collaborators.
- Unit and integration tests cover access control, the CHECK-constraint combination space, and the migration smoke test — these were classified as example/edge-case in the design's prework and are intentionally not property tests.
- The failure-isolation transaction boundary (task 8.1) is a correctness-critical structural detail called out in the design (`REQUIRES_NEW` or independently-committing repository calls) — implement it before writing Property 6/7 tests so the failure path can be exercised realistically.
- Task 6.2 (and its property test, 6.3) require a **second rework** of the `FeedbackEmailServiceImpl` rendering helper. The first rework (already applied) moved the body from a raw per-topic strengths/weaknesses dump to a synthesized narrative paragraph derived from `overallSummary`. That narrative-paragraph approach has now itself been superseded: Requirement 2 (ACs 2.7-2.19) replaces it with a greeting, fixed intro line, score sentence, a conditional strengths sentence naming every `Strong_Topic` (via the shared `joinWithAnd` comma/"and" list-join helper), a conditional weaknesses sentence naming every `Weak_Topic` the same way (including the both-Strong-and-Weak overlap case), a fixed transition line, verbatim next-steps bullets, and a sign-off. Task 6.2 has been reopened (`[ ]`) to implement this new structure, and task 6.3's property test description has been updated to match the new Property 4. This is scope-change rework of existing, live code — not new-feature work. Downstream tasks 6.4 and 8.1 depend on 6.2's rendered-body output and, per the dependency graph, cannot meaningfully re-run/re-verify until 6.2's rework is redone; no new dependency edges are needed since the existing wave ordering (6.2 → 6.3 → 6.4/8.1) already sequences this correctly.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "3.1"] },
    { "id": 1, "tasks": ["2.1", "2.2", "3.2"] },
    { "id": 2, "tasks": ["5.1"] },
    { "id": 3, "tasks": ["5.2"] },
    { "id": 4, "tasks": ["5.3", "5.4"] },
    { "id": 5, "tasks": ["5.5", "6.1"] },
    { "id": 6, "tasks": ["6.2"] },
    { "id": 7, "tasks": ["6.3"] },
    { "id": 8, "tasks": ["6.4", "8.1"] },
    { "id": 9, "tasks": ["8.2", "8.4", "11.1"] },
    { "id": 10, "tasks": ["8.3", "10.1"] },
    { "id": 11, "tasks": ["8.5", "10.2", "11.2"] },
    { "id": 12, "tasks": ["8.6", "11.3"] }
  ]
}
```
