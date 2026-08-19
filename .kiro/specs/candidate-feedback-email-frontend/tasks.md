# Implementation Plan: Candidate Feedback Email Frontend

## Overview

This plan adds a `FeedbackEmailService` under `core/feedback-email/`, extends `ResultsComponent` with feedback-email signal state (history, confirm/send/result flow), and inserts a Feedback_Email_Section into the results detail template, mirroring the existing `FeedbackService`/Feedback Report Section and Reminder section patterns. Work proceeds bottom-up: model → service → component signals/visibility/reset → history load/render → send/resend action → error handling → template wiring → integration tests. Property-based tests (fast-check, already a project dependency) are added alongside each piece of logic they validate, referencing the design document's 19 correctness properties.

## Tasks

- [x] 1. Create the feedback-email model and service
  - [x] 1.1 Create `core/feedback-email/feedback-email.model.ts`
    - Export `FeedbackEmailSendResponse` (`submissionId: string`, `status: 'SENT' | 'FAILED'`, `sentAt: string`) and `FeedbackEmailSendLogEntry` (`sentAt: string`, `status: 'SENT' | 'FAILED'`, `sentBy: string | null`, `failureReason: string | null`)
    - _Requirements: 1.4, 2.1, 2.2_

  - [x] 1.2 Implement `core/feedback-email/feedback-email.service.ts`
    - `@Injectable({ providedIn: 'root' })`, `HttpClient` via `inject()` assigned to `private readonly http`
    - `sendEmail(submissionId)`: throw synchronously on empty `submissionId`, else POST empty body to `/api/submissions/${submissionId}/feedback-report/email` typed `Observable<FeedbackEmailSendResponse>`
    - `getSendHistory(submissionId)`: throw synchronously on empty `submissionId`, else GET the same URL typed `Observable<FeedbackEmailSendLogEntry[]>`
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 2.3_

  - [ ]* 1.3 Write property test for URL and verb construction
    - **Property 1: Service URL and Verb Construction**
    - **Validates: Requirements 1.2, 1.3**

  - [ ]* 1.4 Write property test for empty submissionId validation
    - **Property 2: Empty SubmissionId Validation**
    - **Validates: Requirements 1.5**

  - [ ]* 1.5 Write unit tests for `FeedbackEmailService`
    - Verify HTTP method/URL/body for `sendEmail`/`getSendHistory` via `HttpTestingController`, following `FeedbackService`'s spec conventions
    - _Requirements: 1.2, 1.3, 1.5_

- [ ] 2. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Add feedback-email signal state, visibility, and reset logic to `ResultsComponent`
  - [x] 3.1 Inject `FeedbackEmailService` and declare feedback-email signals
    - Add `feedbackEmailSvc = inject(FeedbackEmailService)`, `feedbackEmailHistory`, `feedbackEmailHistoryLoading`, `feedbackEmailHistoryError`, `showFeedbackEmailConfirm`, `feedbackEmailSending`, `feedbackEmailSuccess`, `feedbackEmailError` signals, and a `feedbackEmailSub?: Subscription` field, alongside the existing `feedback*` fields
    - _Requirements: 3.1, 3.4_

  - [x] 3.2 Implement `showFeedbackEmailSection` computed
    - `computed()` gating on `result()?.markingStatus === 'FULLY_MARKED' && feedbackReport() !== null`
    - _Requirements: 3.1, 3.2, 3.3_

  - [ ]* 3.3 Write property test for section visibility
    - **Property 3: Feedback Email Section Visibility Matches Report Availability**
    - **Validates: Requirements 3.1, 3.2, 3.3**

  - [x] 3.4 Add feedback-email state reset to `selectSubmission()`
    - Unsubscribe `feedbackEmailSub` and reset `feedbackEmailHistory` to `[]` and the loading/error/confirm/sending/success/error signals to their initial values, alongside the existing sibling-feature reset block
    - _Requirements: 3.4_

  - [ ]* 3.5 Write property test for state reset on submission change
    - **Property 4: State Reset on Submission Change**
    - **Validates: Requirements 3.4**

- [ ] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement send-history loading and stale-response handling
  - [x] 5.1 Trigger `loadFeedbackEmailHistory()` from the sibling report-load success paths
    - Call `this.loadFeedbackEmailHistory(submissionId)` inside the existing `loadFeedbackReport()`'s GET-success branch and its nested generate-success branch
    - _Requirements: 4.1_

  - [x] 5.2 Implement `loadFeedbackEmailHistory()` and `retryFeedbackEmailHistory()`
    - Set loading, clear error, subscribe to `getSendHistory()`, guard `next`/`error` callbacks with `selectedSummary()?.submissionId === submissionId` before touching state (Req 4.7), set a generic error message + hide loading on failure (Req 4.5), clear error/loading on success (Req 4.6)
    - `retryFeedbackEmailHistory()` re-invokes `loadFeedbackEmailHistory()` for the current submission
    - _Requirements: 4.1, 4.2, 4.5, 4.6, 4.7, 8.2_

  - [ ]* 5.3 Write property test for history loading state reflecting fetch outcome
    - **Property 5: History Loading State Reflects Fetch Outcome Exactly**
    - **Validates: Requirements 4.1, 4.2, 4.5, 4.6**

  - [ ]* 5.4 Write property test for stale history response discard
    - **Property 7: Stale History Response Is Discarded on Submission Switch**
    - **Validates: Requirements 4.7**

- [x] 6. Render send-history entries
  - [x] 6.1 Add the history list template fragment to `ResultsComponent`
    - Loading indicator, error + Retry control, "no feedback emails sent yet" empty state, and `@for` entry list (`track $index`) rendering in received order with no client-side sort; badge, sent-by, and failure-reason conditional markup per entry
    - _Requirements: 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ]* 6.2 Write property test for rendering completeness and order preservation
    - **Property 6: History Rendering Completeness and Order Preservation**
    - **Validates: Requirements 4.3, 4.4**

  - [ ]* 6.3 Write property test for timestamp formatting
    - **Property 8: Timestamp Formatting**
    - **Validates: Requirements 5.1**

  - [ ]* 6.4 Write property test for status badge mapping
    - **Property 9: Status Badge Mapping**
    - **Validates: Requirements 5.2**

  - [ ]* 6.5 Write property test for sent-by indicator presence
    - **Property 10: Sent-By Indicator Presence**
    - **Validates: Requirements 5.3, 5.4**

  - [ ]* 6.6 Write property test for failure-reason presence
    - **Property 11: Failure Reason Presence**
    - **Validates: Requirements 5.5, 5.6**

- [ ] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement send/resend button label and confirm/cancel controls
  - [x] 8.1 Implement `feedbackEmailButtonLabel` computed and confirm/cancel template markup
    - `computed()` returning "Send Feedback Email" when history is empty, "Resend Feedback Email" otherwise; template button reveals a Confirm/Cancel prompt on click; Cancel sets `showFeedbackEmailConfirm` to `false` without calling the service; button/labels disable and show "Sending…" whenever `feedbackEmailSending()` is `true`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 8.2 Write property test for button label reflecting history length
    - **Property 12: Send/Resend Button Label Reflects History Length**
    - **Validates: Requirements 6.1, 6.2**

  - [ ]* 8.3 Write property test for sending state overriding the default label
    - **Property 13: Sending State Overrides the Default Label**
    - **Validates: Requirements 6.6**

  - [ ]* 8.4 Write unit tests for confirm/cancel interaction
    - Clicking Send/Resend shows the confirm prompt with no HTTP call yet; clicking Cancel dismisses it with no call; clicking Confirm calls `sendEmail()`
    - _Requirements: 6.3, 6.4, 6.5_

- [x] 9. Implement the send action, result handling, and error classification
  - [x] 9.1 Implement `sendFeedbackEmail()` and `classifySendError()`
    - Clear prior success/error and set `sending` before dispatching (Req 7.6); on success, set success indicator, prepend the new entry to `feedbackEmailHistory` without a re-fetch (Req 7.1), auto-clear success after a delay; on error, classify via `classifySendError` (502 → delivery-failure message + history re-fetch; 404/409/network(0) → generic message, no re-fetch) and set `feedbackEmailError`; every path sets `feedbackEmailSending` back to `false` (Req 7.5)
    - _Requirements: 6.5, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ]* 9.2 Write property test for successful send updating history
    - **Property 14: Successful Send Updates History and Shows Success**
    - **Validates: Requirements 7.1**

  - [ ]* 9.3 Write property test for send error classification mapping
    - **Property 15: Send Error Classification Mapping**
    - **Validates: Requirements 7.2, 7.3, 7.4**

  - [ ]* 9.4 Write property test for the button always returning to its default state
    - **Property 16: Button Always Returns to Its Default State After Completion**
    - **Validates: Requirements 7.5**

  - [ ]* 9.5 Write property test for clearing prior success/error on a new attempt
    - **Property 17: New Send Attempt Clears Prior Success/Error State**
    - **Validates: Requirements 7.6**

  - [ ]* 9.6 Write property test for no raw technical details in error messages
    - **Property 18: No Raw Technical Details in Any Displayed Error Message**
    - **Validates: Requirements 8.1**

- [x] 10. Implement error dismissal and history-retry wiring
  - [x] 10.1 Add dismiss-error button and retry-control wiring to the template
    - Dismiss button sets `feedbackEmailError` to `null` only; Retry control (from task 6.1) calls `retryFeedbackEmailHistory()`
    - _Requirements: 8.2, 8.3_

  - [ ]* 10.2 Write property test for dismiss not mutating history
    - **Property 19: Dismissing an Error Notification Does Not Mutate History**
    - **Validates: Requirements 8.3**

  - [ ]* 10.3 Write unit test for the history-retry interaction
    - Clicking the retry control clears the history error and re-invokes `getSendHistory()`
    - _Requirements: 8.2_

- [ ] 11. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. Wire the Feedback_Email_Section into the results template and styles
  - [x] 12.1 Insert the Feedback_Email_Section block into `ResultsComponent`'s template
    - Place immediately after the existing Feedback Report Section, gated by `showFeedbackEmailSection()`, before the answers-title, per the design's template layout
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 12.2 Add feedback-email styles to `ResultsComponent`'s `styles` array
    - `.feedback-email-section`, `.feedback-email-controls`, `.feedback-email-failure-reason`, reusing existing `.reminder-confirm`/`.reminder-toast`/`.audit-*`/`.marking-badge`/`.badge-done`/`.badge-pending`/`.feedback-inline-error`/`.dismiss-btn`/`.feedback-loading`/`.feedback-error` classes
    - _Requirements: 5.2, 6.6_

  - [ ]* 12.3 Write unit tests for template conditional rendering
    - Verify the section is absent for `PENDING_REVIEW` and while no report has loaded, and present once `feedbackReport()` is set for `FULLY_MARKED`
    - _Requirements: 3.1, 3.2, 3.3_

- [ ] 13. Write integration tests for the full report-to-send flow
  - [ ]* 13.1 Write integration test for report loads → history loads → send → optimistic update
    - Use `HttpTestingController` to drive the full flow end to end
    - _Requirements: 4.1, 7.1_

  - [ ]* 13.2 Write integration test for stale request cancellation
    - Select submission A, let its history GET hang, select submission B, flush A's response, assert B's (or empty) state is displayed — not A's
    - _Requirements: 4.7_

  - [ ]* 13.3 Write integration test for the 502-triggers-refetch flow
    - Mock `sendEmail()` to return 502, assert a second `getSendHistory()` request is issued and its response replaces the optimistic state
    - _Requirements: 7.2_

- [ ] 14. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP.
- Property tests use `fast-check` (already a project dependency, `4.9.0`) with `fc.assert(fc.property(...), { numRuns: 100 })`, and each is tagged with a comment referencing its design property number, e.g. `// Feature: candidate-feedback-email-frontend, Property 15: Send error classification mapping`.
- Each task references specific requirements for traceability; property test tasks reference the design document's property number and the requirement clauses it validates.
- Checkpoints ensure incremental validation — run `npm test` and `npx tsc --noEmit` in `recruitment-fe/` at each checkpoint.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "3.1"] },
    { "id": 3, "tasks": ["1.4", "3.2"] },
    { "id": 4, "tasks": ["1.5", "3.3", "3.4"] },
    { "id": 5, "tasks": ["3.5", "5.1"] },
    { "id": 6, "tasks": ["5.2"] },
    { "id": 7, "tasks": ["5.3", "6.1"] },
    { "id": 8, "tasks": ["5.4", "8.1"] },
    { "id": 9, "tasks": ["6.2", "9.1"] },
    { "id": 10, "tasks": ["6.3", "10.1"] },
    { "id": 11, "tasks": ["6.4", "12.1"] },
    { "id": 12, "tasks": ["6.5", "12.2"] },
    { "id": 13, "tasks": ["6.6"] },
    { "id": 14, "tasks": ["8.2"] },
    { "id": 15, "tasks": ["8.3"] },
    { "id": 16, "tasks": ["8.4"] },
    { "id": 17, "tasks": ["9.2"] },
    { "id": 18, "tasks": ["9.3"] },
    { "id": 19, "tasks": ["9.4"] },
    { "id": 20, "tasks": ["9.5"] },
    { "id": 21, "tasks": ["9.6"] },
    { "id": 22, "tasks": ["10.2"] },
    { "id": 23, "tasks": ["10.3"] },
    { "id": 24, "tasks": ["12.3"] },
    { "id": 25, "tasks": ["13.1"] },
    { "id": 26, "tasks": ["13.2"] },
    { "id": 27, "tasks": ["13.3"] }
  ]
}
```
