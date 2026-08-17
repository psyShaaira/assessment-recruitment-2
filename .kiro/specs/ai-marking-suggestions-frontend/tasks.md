# Implementation Plan: AI Marking Suggestions Frontend

## Overview

This plan implements the AI-assisted marking suggestion UI in `recruitment-fe`'s
existing Results & Evaluation feature. Work proceeds bottom-up: the new
`core/ai-marking/` service and models first, then the eligibility predicate,
then per-answer state management (fetch-on-select fan-out, generate/regenerate
dispatch, HTTP error classification, stale-response guarding), then the
copy-to-manual-mark control, then the `Suggestion_Panel` template/styles, and
finally cross-cutting integration tests. Property tests are added as sub-tasks
immediately after the implementation they validate, each in a dedicated
`*.pbt.spec.ts` file (using `fast-check`, added as a new dev dependency) so
they stay independent of each other and of the example-based unit tests in
`results.component.spec.ts`.

## Tasks

- [ ] 1. Set up the `AiMarkingService` and its data models
  - [x] 1.1 Add `fast-check` as a dev dependency and create AI marking model interfaces
    - Add `fast-check` (pinned exact version) to `recruitment-fe/package.json` devDependencies
    - Create `core/ai-marking/ai-marking.model.ts` with `AiMarkingSuggestionResponse` (`answerId`, `score`, `maxScore`, `rationale`, `generatedAt`) and `AiSuggestionErrorKind` (`'error' | 'ineligible'`)
    - _Requirements: 2.2_

  - [x] 1.2 Implement `AiMarkingService`
    - Create `core/ai-marking/ai-marking.service.ts` as an injectable (`providedIn: 'root'`) service using `inject(HttpClient)`
    - Implement `getSuggestion(submissionId, questionId)` (`GET /api/submissions/{submissionId}/questions/{questionId}/ai-suggestion`)
    - Implement `generateSuggestion(submissionId, questionId)` (`POST` to the same URL with an empty body)
    - _Requirements: 1.2, 1.4, 2.1_

  - [ ]* 1.3 Write unit tests for `AiMarkingService`
    - Verify HTTP method (GET/POST), URL construction from `submissionId`/`questionId`, and empty POST body using `HttpTestingController`
    - _Requirements: 1.2, 1.4, 2.1_

- [x] 2. Implement the AI-eligibility predicate
  - [x] 2.1 Implement `isAiEligibleQuestion`, `hasAnswerContent`, and `eligibleQuestionIds`
    - Add `isAiEligibleQuestion(q)` and `hasAnswerContent(answer)` helper functions in `results.component.ts`
    - Add the `eligibleQuestionIds` computed signal, applying the predicate to top-level questions and, separately, to each `GROUP` question's `subQuestions`, never to the `GROUP` question itself
    - _Requirements: 1.3, 8.2, 8.3, 8.4_

  - [ ]* 2.2 Write property test for eligibility predicate correctness
    - **Property 1: Eligibility Predicate Correctness**
    - **Validates: Requirements 1.3, 8.2, 8.3, 8.4**

  - [ ]* 2.3 Write unit tests for the eligibility predicate
    - Concrete examples for each `QuestionType`, `null`/empty/whitespace-only answers, and a `GROUP` question with a mix of eligible/ineligible sub-questions
    - _Requirements: 8.2, 8.3, 8.4_

- [ ] 3. Add per-answer AI suggestion state and the fetch-on-select flow
  - [x] 3.1 Add AI state signals and reset-on-submission-change logic
    - Add `aiSuggestions`, `aiLoading`, `aiError`, `aiAccessDenied` `Record<string, T>` signals and the `aiGeneration`/`aiRequestSeq` bookkeeping fields to `results.component.ts`; inject `AiMarkingService`
    - In `selectSubmission()`, bump `aiGeneration` and reset all four AI Record signals alongside the existing feedback-state reset
    - _Requirements: 1.6, 6.5_

  - [x] 3.2 Implement `fetchAiSuggestion` and the `loadAiSuggestions` fan-out
    - Implement `isCurrentAiRequest(questionId, generation, seq)` guard
    - Implement `fetchAiSuggestion(submissionId, questionId)`, setting `aiLoading[questionId]` and updating `aiSuggestions`/`aiError` on response, discarding stale responses via the guard
    - Implement `loadAiSuggestions(submissionId)`, dispatching one `fetchAiSuggestion` call per id in `eligibleQuestionIds()`, invoked after `getResult()` succeeds in `selectSubmission()`
    - _Requirements: 1.1, 2.1, 2.3, 2.5_

  - [ ]* 3.3 Write property test for panel visibility and dispatch following eligibility
    - **Property 2: Panel Visibility and Dispatch Follow Eligibility**
    - **Validates: Requirements 1.1, 2.1, 2.3, 8.2, 8.3, 8.4**

  - [ ]* 3.4 Write property test for the loading indicator lifecycle
    - **Property 7: Loading Indicator Lifecycle**
    - **Validates: Requirements 3.1, 3.4**

  - [ ]* 3.5 Write property test for stale response discarding
    - **Property 8: Stale Response Discarding**
    - **Validates: Requirements 1.6, 6.5**

- [ ] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement the generate/regenerate request path and HTTP error classification
  - [x] 5.1 Implement `applyAiErrorClassification`
    - Map a fetch's 404 to "no suggestion yet" (no state change, no error), 401/403 to sticky `aiAccessDenied`, 400 (generate only) to `aiError = 'ineligible'`, and any other 4xx/5xx or no-response outcome to `aiError = 'error'`
    - _Requirements: 4.4, 4.5, 4.6, 7.2, 7.3, 8.1_

  - [x] 5.2 Implement `requestAiSuggestion`
    - No-op if `aiLoading()[questionId]` or `aiAccessDenied()[questionId]` is `true`
    - Otherwise capture `generation`/`seq`, set `aiLoading[questionId] = true`, clear `aiError[questionId]`, call `AiMarkingService.generateSuggestion`, and on response (guarded by `isCurrentAiRequest`) either replace `aiSuggestions[questionId]` on success or classify the failure via `applyAiErrorClassification`
    - _Requirements: 1.2, 1.4, 1.5, 4.1, 4.3, 6.1, 6.4_

  - [ ]* 5.3 Write property test for activation always dispatching a fresh request
    - **Property 4: Activation Always Dispatches a Fresh Request**
    - **Validates: Requirements 1.2, 1.4, 4.3**

  - [ ]* 5.4 Write property test for in-flight requests blocking duplicate dispatch
    - **Property 5: In-Flight Requests Block Duplicate Dispatch**
    - **Validates: Requirements 1.5, 3.3, 6.4**

  - [ ]* 5.5 Write property test for failed requests never mutating the stored suggestion
    - **Property 9: Failed Requests Never Mutate the Stored Suggestion**
    - **Validates: Requirements 4.1**

  - [ ]* 5.6 Write property test for HTTP outcome classification totality and exclusivity
    - **Property 11: HTTP Outcome Classification Is Total and Mutually Exclusive**
    - **Validates: Requirements 4.4, 4.5, 4.6, 7.2, 7.3, 8.1**

  - [ ]* 5.7 Write property test for access-denied stickiness
    - **Property 12: Access-Denied Is Sticky Per Answer**
    - **Validates: Requirements 7.4**

  - [ ]* 5.8 Write property test for full suggestion replacement on success
    - **Property 15: Successful Generate/Regenerate Fully Replaces the Prior Suggestion**
    - **Validates: Requirements 6.1, 6.2, 6.3**

  - [ ]* 5.9 Write property test for per-answer state isolation
    - **Property 6: Per-Answer State Isolation**
    - **Validates: Requirements 1.7, 2.5, 3.1**

- [ ] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement copying a suggested score into the manual mark input
  - [x] 7.1 Implement `copyAiScoreToMark`
    - Read `aiSuggestions()[questionId]`; if present, write its `score` into `editScores` via the existing `Record` update pattern; no HTTP call
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

  - [ ]* 7.2 Write property test for copy activation write semantics
    - **Property 14: Copy Activation Writes Exactly the Suggested Score, Persistently, With No HTTP Call**
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**

  - [ ]* 7.3 Write property test for AI state transitions never affecting manual marking inputs
    - **Property 10: AI State Transitions Never Affect Manual Marking Inputs**
    - **Validates: Requirements 3.2, 4.2**

- [x] 8. Add the Suggestion_Panel template and styles
  - [x] 8.1 Add the `Suggestion_Panel` block to `results.component.ts`
    - Add the `.ai-suggestion-panel` `<section>` as a sibling of `.mark-row` (never nested inside it), guarded by `isAiEligibleQuestion(...)`, for both top-level questions and `GROUP` sub-questions
    - Render, per state: request control (disabled while loading), loading indicator, access-denied indication, generic/ineligible error indication, or suggestion content (score/maxScore, rationale, generated-at, "Use this score" button, "Regenerate" button)
    - Add the `.ai-suggestion-panel`/`.ai-badge`/`.ai-score`/`.ai-rationale`/`.ai-meta`/`.ai-loading`/`.ai-error`/`.ai-access-denied`/`.ai-request-row` styles to the component's inline `styles` array
    - _Requirements: 1.1, 1.3, 1.5, 2.2, 2.3, 2.4, 3.1, 3.3, 5.1, 7.1, 7.2, 7.3, 7.4, 8.1, 8.3, 8.4_

  - [ ]* 8.2 Write unit test for Suggestion_Panel DOM structure
    - Assert `.ai-suggestion-panel` is a sibling of `.mark-row`, not nested inside it, for both a top-level question and a `GROUP` sub-question, using `TestBed` + `DebugElement`
    - _Requirements: 2.4_

  - [ ]* 8.3 Write property test for suggestion content completeness
    - **Property 3: Suggestion Content Completeness**
    - **Validates: Requirements 2.2**

  - [ ]* 8.4 Write property test for copy control visibility
    - **Property 13: Copy Control Visibility Depends Only on Suggestion Presence**
    - **Validates: Requirements 5.1**

- [ ] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. Write integration tests for end-to-end AI suggestion flows
  - [ ]* 10.1 Write integration test for the submission-select fan-out
    - Using `provideHttpClientTesting()`/`HttpTestingController`, assert exactly one GET per eligible question (including `GROUP` sub-questions) and zero GETs for ineligible questions (MCQ, `GROUP` preamble, blank-answer TEXT/CODE_SUBMISSION)
    - _Requirements: 1.1, 2.1, 8.2, 8.3, 8.4_

  - [ ]* 10.2 Write integration test for the regenerate flow's distinct panel states
    - Assert POST → 200 replaces the displayed suggestion, and POST → 400/401/403/5xx each produce their own distinct panel state (ineligible / access-denied / generic error)
    - _Requirements: 4.5, 4.6, 6.1, 7.2, 8.1_

  - [ ]* 10.3 Write integration test for a mid-flight submission switch
    - Select submission A, let a GET start, switch to submission B before it resolves, flush A's response, assert it never appears in B's view
    - _Requirements: 1.6_

  - [ ]* 10.4 Write integration test for copy-then-save
    - Activate the copy control, then call `saveScore()`, asserting the submitted `ScoreAnswerRequest.score` equals the suggested score
    - _Requirements: 5.5_

- [ ] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP; they are not implemented by the coding agent by default.
- Property tests use `fast-check` with `numRuns: 100` and a comment tagging the property number, per the design's Testing Strategy.
- Each property test sub-task lives in its own dedicated `*.pbt.spec.ts` file, kept separate from `results.component.spec.ts` and from each other, so property tests, unit tests, and integration tests can be authored independently.
- Checkpoints ensure incremental validation before moving to the next area of the feature.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "2.2", "2.3"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["3.2"] },
    { "id": 5, "tasks": ["3.3", "3.4", "3.5"] },
    { "id": 6, "tasks": ["5.1"] },
    { "id": 7, "tasks": ["5.2"] },
    { "id": 8, "tasks": ["5.3", "5.4", "5.5", "5.6", "5.7", "5.8", "5.9"] },
    { "id": 9, "tasks": ["7.1"] },
    { "id": 10, "tasks": ["7.2", "7.3"] },
    { "id": 11, "tasks": ["8.1"] },
    { "id": 12, "tasks": ["8.2", "8.3", "8.4"] },
    { "id": 13, "tasks": ["10.1", "10.2", "10.3", "10.4"] }
  ]
}
```
