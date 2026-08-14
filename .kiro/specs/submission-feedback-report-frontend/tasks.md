# Implementation Plan: Submission Feedback Report Frontend

## Overview

Implement the AI-generated feedback report section in the Results & Evaluation page. The work introduces model interfaces, a `FeedbackService`, signal-based state management in `ResultsComponent`, and a conditional template section with loading/error/report states, auto-generation flow, and regenerate capability.

## Tasks

- [x] 1. Create feedback model interfaces
  - [x] 1.1 Create `core/feedback/feedback.model.ts` with `FeedbackTopic`, `FeedbackReportContent`, and `FeedbackReportResponse` interfaces
    - Export `FeedbackTopic` with fields: `name: string`, `strength: string`, `weakness: string`, `recommendation: string`
    - Export `FeedbackReportContent` with fields: `overallSummary: string`, `topics: FeedbackTopic[]`, `nextSteps: string[]`
    - Export `FeedbackReportResponse` with fields: `submissionId: string`, `content: FeedbackReportContent`, `aiGenerated: boolean`, `promptVersion: string`, `generatedAt: string`
    - _Requirements: 8.1, 8.2, 8.3_

- [x] 2. Implement FeedbackService
  - [x] 2.1 Create `core/feedback/feedback.service.ts` as standalone injectable service
    - Use `@Injectable({ providedIn: 'root' })` with `inject(HttpClient)` assigned to `private readonly http`
    - Implement `getReport(submissionId: string): Observable<FeedbackReportResponse>` — GET to `/api/submissions/{submissionId}/feedback-report`
    - Implement `generateReport(submissionId: string): Observable<FeedbackReportResponse>` — POST empty body to `/api/submissions/{submissionId}/feedback-report`
    - Throw synchronous error if `submissionId` is empty string before making HTTP request
    - Use `FeedbackReportResponse` as generic type parameter in HttpClient calls
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 8.4_

  - [ ]* 2.2 Write property test: Service URL Construction
    - **Property 1: Service URL Construction**
    - Use `fc.string({ minLength: 1 })` to generate non-empty submissionIds
    - Verify `getReport` issues GET to `/api/submissions/${submissionId}/feedback-report`
    - Verify `generateReport` issues POST to same URL with empty body
    - Use `provideHttpClientTesting` to intercept and assert
    - **Validates: Requirements 1.2, 1.3**

  - [ ]* 2.3 Write property test: Empty SubmissionId Validation
    - **Property 2: Empty SubmissionId Validation**
    - Use `fc.stringOf(fc.constant(' '))` plus empty string to generate blank submissionIds
    - Verify `getReport('')` and `generateReport('')` throw synchronously
    - Verify no HTTP request is made (no pending requests in HttpTestingController)
    - **Validates: Requirements 1.5**

  - [ ]* 2.4 Write unit tests for FeedbackService
    - Create `core/feedback/feedback.service.spec.ts`
    - Test successful GET returns typed `FeedbackReportResponse`
    - Test successful POST returns typed `FeedbackReportResponse`
    - Test error propagation for non-404 errors
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 8.4, 8.5_

- [x] 3. Checkpoint - Ensure model and service compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Integrate feedback state into ResultsComponent
  - [x] 4.1 Add feedback signals and service injection to ResultsComponent
    - Inject `FeedbackService` via `inject(FeedbackService)`
    - Add signals: `feedbackReport`, `feedbackLoading`, `feedbackError`, `regenerating`, `regenerateError`
    - Add `private feedbackSub?: Subscription` for stale request cancellation
    - Reset all feedback state in `selectSubmission()` method and unsubscribe `feedbackSub`
    - _Requirements: 2.3, 3.7_

  - [x] 4.2 Implement `loadFeedbackReport(submissionId: string)` method
    - Set `feedbackLoading` to `'fetching'`, clear error
    - Call `feedbackSvc.getReport(submissionId)` — on success set report, clear loading
    - On 404 error: set loading to `'generating'`, call `feedbackSvc.generateReport(submissionId)` — on success set report (guard against stale submission), on error set error message
    - On non-404 error: set generic error message, clear loading
    - Store subscription in `feedbackSub` for cancellation support
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7, 5.1, 5.2, 6.1, 6.2, 6.4_

  - [x] 4.3 Implement `retryFeedback()` method
    - Clear error and restart full `loadFeedbackReport()` flow from `getReport()`
    - _Requirements: 6.3, 6.5_

  - [x] 4.4 Implement `regenerateReport()` method with 30s timeout
    - Set `regenerating` to true, clear `regenerateError`
    - Call `feedbackSvc.generateReport(submissionId).pipe(timeout(30_000))`
    - On success: update `feedbackReport` signal with new data, set `regenerating` false
    - On timeout: set `regenerateError` to timeout message, re-enable button
    - On other error: set `regenerateError` to failure message, keep previous report visible
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 4.5 Wire `loadFeedbackReport()` call into submission selection flow
    - After `markingService.getResult()` succeeds and `markingStatus === 'FULLY_MARKED'`, call `loadFeedbackReport(submissionId)`
    - Skip feedback loading entirely when markingStatus is not `'FULLY_MARKED'`
    - _Requirements: 2.1, 2.2, 3.1, 3.6_

- [x] 5. Implement feedback report template section
  - [x] 5.1 Add feedback section template to ResultsComponent inline template
    - Insert between history-grid and answers-title, guarded by `markingStatus === 'FULLY_MARKED'`
    - Add `[attr.aria-busy]="feedbackLoading() !== null"` on section container
    - Render loading state with appropriate text ("Loading feedback report…" or "Generating feedback report…")
    - Render error state with message and "Retry" button calling `retryFeedback()`
    - Render report content: header with title, AI badge (conditional on `aiGenerated`), Regenerate button
    - Render `overallSummary` as paragraph
    - Render `topics` array as cards with name heading, strength/weakness/recommendation sub-sections (hidden if empty)
    - Render `nextSteps` as ordered list under "Next Steps" heading (hidden if empty)
    - Render `generatedAt` formatted as locale medium date-time
    - Render inline regenerate error notification with dismiss button
    - _Requirements: 2.1, 2.2, 2.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 5.1, 5.2, 5.3, 5.4, 5.5, 6.3, 7.1, 7.3, 7.5_

  - [x] 5.2 Add feedback section CSS styles to ResultsComponent inline styles
    - Add `.feedback-section`, `.feedback-loading`, `.loading-dot` with pulse animation
    - Add `.ai-badge`, `.topic-card`, `.topic-name`, `.topic-label`, `.topic-field` styles
    - Add `.feedback-header`, `.feedback-summary`, `.feedback-topics`, `.feedback-next-steps` styles
    - Add `.feedback-error`, `.feedback-inline-error`, `.dismiss-btn`, `.feedback-meta` styles
    - Use existing CSS variables (`--bg-card`, `--border`, `--accent`, `--text-1`, `--text-2`, etc.)
    - CSS-only loading indicator (no JS animation libraries)
    - _Requirements: 5.1, 5.2_

  - [x] 5.3 Add `formatDateTime` helper method to ResultsComponent
    - Format ISO date string using locale medium date-time format (e.g., "Jun 15, 2025, 2:30:00 PM")
    - _Requirements: 4.4_

- [x] 6. Checkpoint - Verify template renders and type-checks
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Property-based and unit tests for component logic
  - [ ]* 7.1 Write property test: Feedback Section Visibility Matches Marking Status
    - **Property 3: Feedback Section Visibility Matches Marking Status**
    - Use `fc.oneof(fc.constant('FULLY_MARKED'), fc.constant('PENDING_REVIEW'))` for markingStatus
    - Verify feedback section rendered if and only if `markingStatus === 'FULLY_MARKED'`
    - Verify no feedback HTTP calls made when not `FULLY_MARKED`
    - **Validates: Requirements 2.1, 2.2, 3.6**

  - [ ]* 7.2 Write property test: Topic Card Completeness
    - **Property 4: Topic Card Completeness**
    - Use `fc.record({ name: fc.string(), strength: fc.string(), weakness: fc.string(), recommendation: fc.string() })` for topic generation
    - Verify all four fields (name, strength, weakness, recommendation) present in rendered DOM for each topic
    - **Validates: Requirements 4.2**

  - [ ]* 7.3 Write property test: Error Messages Never Expose Technical Details
    - **Property 5: Error Messages Never Expose Technical Details**
    - Use `fc.record({ status: fc.integer({ min: 400, max: 599 }), message: fc.string() })` to generate error responses
    - Verify displayed error messages contain no numeric HTTP status codes, no stack traces, no raw response bodies
    - **Validates: Requirements 6.4**

  - [ ]* 7.4 Write unit tests for ResultsComponent feedback integration
    - Test state reset on submission change clears all feedback signals
    - Test auto-generation flow: GET → 404 → POST → report displayed
    - Test GET success displays report without triggering generation
    - Test non-404 error shows error message and Retry button
    - Test regeneration success replaces report content
    - Test regeneration timeout shows timeout message and re-enables button
    - Test stale request cancellation (new selection discards pending response)
    - Test aria-busy attribute toggles with loading state
    - _Requirements: 2.3, 3.1, 3.2, 3.3, 3.4, 3.7, 5.1, 5.2, 5.4, 6.1, 6.3, 6.5, 7.3, 7.4, 7.5, 7.6_

- [x] 8. Final checkpoint - Full type-check and test pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- All code uses Angular 21.2 standalone patterns with `inject()`, signals, and inline templates/styles
- Vitest is the test runner; fast-check is used for property-based tests
- The existing `ResultsComponent` pattern (inline template/styles, signal state) must be followed

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 5, "tasks": ["4.5", "5.3"] },
    { "id": 6, "tasks": ["5.1", "5.2"] },
    { "id": 7, "tasks": ["7.1", "7.2", "7.3", "7.4"] }
  ]
}
```
