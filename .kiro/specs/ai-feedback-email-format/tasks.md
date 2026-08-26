# Implementation Plan: AI-Generated Feedback Email Body

## Overview

This plan extracts the current static `renderBody()` logic out of `FeedbackEmailServiceImpl` into
a new package-private collaborator, `FeedbackEmailBodyGenerator`, then layers an AI-generation
attempt (prompt build → structural validation → retry-once-with-feedback, up to 3 total attempts)
in front of the now-renamed `renderStaticBody()` fallback. `AiService` is reused as-is; no new
endpoints, DTOs, or schema changes are introduced. Work proceeds in the order the collaborator's
internal call graph requires (skeleton + static-body move → prompt building → validation → full
retry/fallback orchestration), with property tests for each of the design's 7 correctness
properties added alongside the logic they cover, followed by regression unit tests.

## Tasks

- [x] 1. Extract `FeedbackEmailBodyGenerator` and move static rendering onto it
  - [x] 1.1 Create `FeedbackEmailBodyGenerator` with the static body path moved from `FeedbackEmailServiceImpl`
    - Create `recruitment-be/src/main/java/com/psybergate/recruitment/feedbackemail/FeedbackEmailBodyGenerator.java` as a package-private `@Component` with `@RequiredArgsConstructor`, depending on `AiService`
    - Move the current private `renderBody(...)` and `joinWithAnd(...)` methods from `FeedbackEmailServiceImpl` onto this class verbatim, renaming `renderBody` to `renderStaticBody`
    - Add the package-private `String generateBody(FeedbackReportContent content, ResultSummaryResponse result, String candidateFirstName)` method signature; for now, have it delegate directly to `renderStaticBody(...)` as a placeholder (full AI orchestration is implemented in task 5.1)
    - Add the private `record GenerationAttempt(String body, String rejectionReason) {}` used later to thread rejection reasons across retries
    - _Requirements: 4.3_
  - [x] 1.2 Wire `FeedbackEmailBodyGenerator` into `FeedbackEmailServiceImpl`
    - Add a constructor dependency on `FeedbackEmailBodyGenerator` (via Lombok `@RequiredArgsConstructor`, matching the existing `FeedbackEmailSendLogWriter` convention)
    - Replace the `String body = renderBody(content, result, candidate.getFirstName());` line in `sendFeedbackEmail` with a call to `feedbackEmailBodyGenerator.generateBody(content, result, candidate.getFirstName())`
    - Remove the now-moved `renderBody`/`joinWithAnd` private methods from `FeedbackEmailServiceImpl`
    - _Requirements: 1.1, 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 2. Implement Feedback_Prompt construction
  - [x] 2.1 Implement `buildPrompt(FeedbackReportContent, ResultSummaryResponse, String firstName, String previousRejectionReason)`
    - Build the plain-text prompt from the candidate's first name, assessment title (if available on `ResultSummaryResponse`), whole-number score percentage (`totalScore`/`maxScore`), every topic name paired with its `strengths`, every topic name paired with its `weaknesses`, and every `nextSteps` entry
    - Include fixed instructional text directing the AI to open with a personalized greeting, acknowledge effort/achievements, give 2-3 actionable recommendations, close with encouragement and next steps, return plain text with no markdown, and sign off as "The Psybergate Recruitment Team"
    - Do not include the candidate's last name, email address, submission ID, or candidate ID anywhere in the prompt
    - WHEN `previousRejectionReason` is non-null, append corrective-feedback text naming that rejection reason (mirroring `QuestionGenerationServiceImpl.buildPrompt`'s corrective-feedback clause)
    - _Requirements: 1.2, 1.3, 1.4, 2.1, 2.2_
  - [ ]* 2.2 Write property test for Feedback_Prompt data completeness
    - **Property 2: Feedback_Prompt data completeness**
    - **Validates: Requirements 1.2**
  - [ ]* 2.3 Write property test for Feedback_Prompt instruction completeness
    - **Property 3: Feedback_Prompt instruction completeness**
    - **Validates: Requirements 1.3, 1.4**
  - [ ]* 2.4 Write property test for PII minimization in the prompt
    - **Property 4: PII minimization in the prompt**
    - **Validates: Requirements 2.1, 2.2**

- [x] 3. Implement structural validation of AI_Body
  - [x] 3.1 Implement `validate(String aiBody, String candidateFirstName)`
    - Return `Optional<String>` (empty = accepted) rather than throwing
    - Reject (return a non-empty rejection reason) if `aiBody` is blank, OR does not contain `candidateFirstName`, OR does not contain "The Psybergate Recruitment Team", OR contains any of `#`, `*`, `` ` ``, or `_`
    - _Requirements: 3.1_
  - [ ]* 3.2 Write property test for structural validation correctness
    - **Property 5: Structural validation correctness**
    - **Validates: Requirements 3.1**

- [ ] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement the full generation/retry/fallback orchestration
  - [x] 5.1 Implement `generateBody` with up to 3 attempts, exception handling, retry, and fallback
    - Replace the task 1.1 placeholder body of `generateBody` with the full loop: call `AiService.prompt(buildPrompt(...))`, catching `AiCommunicationException`, `AiTimeoutException`, `AiRateLimitException`, `AiAuthenticationException`, and `AiResponseException` and treating any of them identically to a `validate(...)` rejection (log at `warn`, per `QuestionGenerationServiceImpl`'s retry logging convention)
    - On acceptance (validation passes), return that AI_Body immediately without calling `renderStaticBody`
    - On rejection with fewer than 3 total attempts made, make one more attempt, passing the previous rejection reason into `buildPrompt`
    - After 3 rejected attempts, return `renderStaticBody(content, result, candidateFirstName)`
    - Ensure `generateBody` never throws for any AI-related failure and always returns a non-null, non-blank body
    - _Requirements: 1.1, 1.5, 3.2, 3.3, 4.1, 4.2_
  - [ ]* 5.2 Write property test for the AI-first success path
    - **Property 1: AI-first success path**
    - **Validates: Requirements 1.1, 1.5**
  - [ ]* 5.3 Write property test for corrective retry while attempts remain
    - **Property 6: Rejection triggers a corrective retry while attempts remain**
    - **Validates: Requirements 3.2, 4.1**
  - [ ]* 5.4 Write property test for exhausted-retries fallback
    - **Property 7: Exhausted retries fall back to Static_Body with a still-successful result**
    - **Validates: Requirements 3.3, 4.1, 4.2**

- [x] 6. Regression unit tests
  - [x] 6.1 Update `FeedbackEmailServiceImplTest` for the new collaborator and add the Requirement 5.4 fault-injection case
    - Update the happy-path test(s) to mock `FeedbackEmailBodyGenerator.generateBody(...)` returning a canned body instead of exercising static rendering directly
    - Keep the existing 409/404/502 gating tests passing unchanged against the new constructor shape
    - Add a test for Requirement 5.4: send succeeds, the `SENT` row save throws, and `sendFeedbackEmail` still returns successfully
    - _Requirements: 5.1, 5.4, 5.5_
  - [ ]* 6.2 Write `FeedbackEmailBodyGeneratorTest` with concrete examples
    - Move the existing `renderBody`-focused examples (structured body, omitted strengths/weaknesses sentences) onto `renderStaticBody`, covering Requirement 4.3's non-regression requirement
    - Add one example each for "AI succeeds on attempt 1", "AI succeeds on attempt 2 (retry prompt includes rejection reason)", and "AI exhausted after 3 attempts falls back to static body"
    - _Requirements: 1.1, 1.5, 3.2, 3.3, 4.1, 4.2, 4.3_

- [ ] 7. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; the model implementing this
  plan MUST NOT implement tasks marked with `*` unless explicitly asked to.
- All 7 correctness properties from the design's "Correctness Properties" section are covered by a
  dedicated property test sub-task, placed immediately after the implementation it validates.
- `FeedbackEmailBodyGeneratorPropertyTest.java` is a single shared test class across tasks 2.2, 2.3,
  2.4, 3.2, 5.2, 5.3, and 5.4 — each task adds one `@Property` method to that file.
- `AiService` is mocked with Mockito in every property test; no real network call is made.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["3.1", "2.2"] },
    { "id": 3, "tasks": ["5.1", "2.3"] },
    { "id": 4, "tasks": ["2.4", "6.1", "6.2"] },
    { "id": 5, "tasks": ["3.2"] },
    { "id": 6, "tasks": ["5.2"] },
    { "id": 7, "tasks": ["5.3"] },
    { "id": 8, "tasks": ["5.4"] }
  ]
}
```
