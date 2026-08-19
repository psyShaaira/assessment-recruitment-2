# Requirements Document

## Introduction

This spec covers the frontend implementation for sending and resending a candidate's AI feedback report by email, and for viewing the send history of those attempts, from the Results & Evaluation page. The backend API is fully implemented (see `candidate-feedback-email`) — this work adds a `Feedback_Email_Service` under `core/feedback-email/`, a feedback-email UI section in the results detail panel (alongside the existing `Feedback_Report_Section` from `submission-feedback-report-frontend`), a send/resend control with confirmation and loading/success/failure feedback, and a send-history display.

## Glossary

- **Feedback_Email_Service**: Angular injectable service at `core/feedback-email/feedback-email.service.ts` responsible for calling the backend feedback-email endpoints.
- **Feedback_Email_Section**: The UI region within the results detail panel that lets a recruiter send/resend the candidate's feedback email and view the send history.
- **Results_Component**: The existing `results.component.ts` page that displays submission details in a split-panel layout.
- **Feedback_Report_Section**: The existing UI region (from `submission-feedback-report-frontend`) that renders the structured AI feedback report content.
- **FeedbackEmailSendResponse**: The backend response shape returned on a successful send: `{ submissionId: string, status: 'SENT' | 'FAILED', sentAt: string }`.
- **FeedbackEmailSendLogEntry**: One entry in the send-history list: `{ sentAt: string, status: 'SENT' | 'FAILED', sentBy: string | null, failureReason: string | null }`.
- **Marking_Status**: The `ResultSummary.markingStatus` field — either `'FULLY_MARKED'` or `'PENDING_REVIEW'`.
- **Recruiter**: A staff user holding the `RECRUITER` or `ADMIN` role, authenticated via the existing staff JWT session.

---

## Requirements

### Requirement 1: Feedback Email Service

**User Story:** As a developer, I want a dedicated Angular service for feedback-email API calls so that the feedback-email domain is encapsulated following existing project conventions.

#### Acceptance Criteria

1. THE Feedback_Email_Service SHALL be a standalone injectable service at `core/feedback-email/feedback-email.service.ts` using `providedIn: 'root'` and obtaining `HttpClient` via the `inject()` function assigned to a `private readonly http` field.
2. THE Feedback_Email_Service SHALL expose a `sendEmail(submissionId: string): Observable<FeedbackEmailSendResponse>` method that POSTs an empty body to `/api/submissions/{submissionId}/feedback-report/email`.
3. THE Feedback_Email_Service SHALL expose a `getSendHistory(submissionId: string): Observable<FeedbackEmailSendLogEntry[]>` method that GETs `/api/submissions/{submissionId}/feedback-report/email`.
4. THE `core/feedback-email/` directory SHALL contain a `feedback-email.model.ts` file exporting the `FeedbackEmailSendResponse` and `FeedbackEmailSendLogEntry` interfaces.
5. IF the `submissionId` parameter passed to `sendEmail` or `getSendHistory` is an empty string, THEN THE Feedback_Email_Service SHALL throw an error synchronously and SHALL NOT issue an HTTP request.

---

### Requirement 2: Feedback Email Model Types

**User Story:** As a developer, I want strongly-typed interfaces for the feedback-email API responses so that TypeScript strict mode catches contract mismatches at compile time.

#### Acceptance Criteria

1. THE `feedback-email.model.ts` file SHALL export a `FeedbackEmailSendResponse` interface with fields: `submissionId: string`, `status: 'SENT' | 'FAILED'`, `sentAt: string`.
2. THE `feedback-email.model.ts` file SHALL export a `FeedbackEmailSendLogEntry` interface with fields: `sentAt: string`, `status: 'SENT' | 'FAILED'`, `sentBy: string | null`, `failureReason: string | null`.
3. THE Feedback_Email_Service SHALL use `FeedbackEmailSendResponse` and `FeedbackEmailSendLogEntry[]` as generic type parameters in its HttpClient calls (e.g., `http.post<FeedbackEmailSendResponse>(...)`, `http.get<FeedbackEmailSendLogEntry[]>(...)`).
4. THE `npx tsc --noEmit` command SHALL be run against the project, and THE TypeScript compiler SHALL produce zero type errors related to the feedback-email model interfaces and their usage in the Feedback_Email_Service.

---

### Requirement 3: Feedback Email Section Visibility

**User Story:** As a recruiter, I want the feedback-email controls to only appear when a feedback report actually exists so that I can't try to email a report that hasn't been generated yet.

#### Acceptance Criteria

1. WHILE the Marking_Status equals `'FULLY_MARKED'` AND the Feedback_Report_Section has a successfully loaded feedback report for the selected submission, THE Results_Component SHALL display the Feedback_Email_Section.
2. WHILE the Marking_Status equals `'PENDING_REVIEW'`, THE Results_Component SHALL hide the Feedback_Email_Section entirely, rendering no container element or placeholder for it.
3. WHILE the Marking_Status equals `'FULLY_MARKED'` AND no feedback report has yet loaded successfully for the selected submission (report is loading, failed to load, or generation is in progress), THE Results_Component SHALL hide the Feedback_Email_Section.
4. WHEN the selected submission changes, THE Results_Component SHALL reset the feedback-email state by clearing the previously loaded send history, any error messages, any success indicators, and any loading indicators associated with the Feedback_Email_Section.

---

### Requirement 4: Load and Display Send History

**User Story:** As a recruiter, I want to see the history of feedback-email send attempts for a submission so that I know whether and when the candidate was emailed.

#### Acceptance Criteria

1. WHEN the Feedback_Email_Section becomes visible for a selected submission, THE Results_Component SHALL call `Feedback_Email_Service.getSendHistory(submissionId)` to retrieve the send history.
2. WHILE the send history is being fetched, THE Feedback_Email_Section SHALL display a loading indicator in the history area.
3. WHEN `getSendHistory()` returns successfully with an empty list, THE Feedback_Email_Section SHALL display a message indicating that no feedback emails have been sent yet.
4. WHEN `getSendHistory()` returns successfully with one or more entries, THE Feedback_Email_Section SHALL render the entries in the order received from the backend, without re-sorting them.
5. IF `getSendHistory()` fails with an HTTP error or network error, THEN THE Feedback_Email_Section SHALL hide the loading indicator, display an error message indicating the history could not be retrieved, and SHALL display a control allowing the recruiter to retry the fetch.
6. IF `getSendHistory()` succeeds, THEN THE Feedback_Email_Section SHALL NOT display the history-retrieval error message or retry control described in Acceptance Criterion 5.
7. WHEN a different submission is selected while a send-history fetch is still in progress, THE Results_Component SHALL discard the pending response and SHALL NOT display send-history entries for the previously selected submission.

---

### Requirement 5: Send History Entry Content

**User Story:** As a recruiter, I want each send-history entry to show clear timestamp, outcome, and failure details so that I can understand exactly what happened on each attempt.

#### Acceptance Criteria

1. THE Feedback_Email_Section SHALL display each send-history entry's `sentAt` timestamp formatted using the locale medium date-time format (e.g., "Jun 15, 2025, 2:30:00 PM").
2. THE Feedback_Email_Section SHALL render each send-history entry's `status` as a visually distinguished badge indicating either "Sent" or "Failed".
3. IF a send-history entry's `sentBy` is present, THEN THE Feedback_Email_Section SHALL display it as an indicator of the staff member who triggered that send attempt.
4. IF a send-history entry's `sentBy` is null, THEN THE Feedback_Email_Section SHALL omit the sent-by indicator for that entry.
5. IF a send-history entry's `status` is `'FAILED'`, THEN THE Feedback_Email_Section SHALL display that entry's `failureReason` text.
6. IF a send-history entry's `status` is `'SENT'`, THEN THE Feedback_Email_Section SHALL NOT display a failure reason for that entry.

---

### Requirement 6: Send and Resend Action

**User Story:** As a recruiter, I want to send the feedback email to a candidate, and resend it if needed, so that the candidate receives their feedback even if a prior attempt failed or they say they didn't receive it.

#### Acceptance Criteria

1. WHILE the loaded send history contains zero entries, THE Feedback_Email_Section SHALL display a button labelled "Send Feedback Email".
2. WHILE the loaded send history contains at least one entry, THE Feedback_Email_Section SHALL display a button labelled "Resend Feedback Email" in place of the initial send button.
3. WHEN the recruiter clicks the "Send Feedback Email" or "Resend Feedback Email" button, THE Feedback_Email_Section SHALL display a confirmation prompt asking the recruiter to confirm the send, with Confirm and Cancel controls, before any request is made.
4. WHEN the recruiter clicks Cancel on the confirmation prompt, THE Feedback_Email_Section SHALL dismiss the prompt and SHALL NOT call `Feedback_Email_Service.sendEmail()`.
5. WHEN the recruiter clicks Confirm on the confirmation prompt, THE Results_Component SHALL call `Feedback_Email_Service.sendEmail(submissionId)`.
6. WHILE a send request is in progress, THE Feedback_Email_Section SHALL disable the send/resend button and display "Sending…" in place of its default label, overriding the "Send Feedback Email" / "Resend Feedback Email" label regardless of the current send-history state.

---

### Requirement 7: Send Result Handling

**User Story:** As a recruiter, I want clear loading, success, and failure feedback when I send a feedback email so that I know whether the candidate actually received it.

#### Acceptance Criteria

1. WHEN `sendEmail()` returns successfully with `status` `'SENT'`, THE Feedback_Email_Section SHALL display a success indicator and SHALL update the displayed send history to include the newly sent entry without requiring a page reload.
2. IF `sendEmail()` fails with an HTTP 502 response, THEN THE Feedback_Email_Section SHALL display an error indicating the email could not be delivered to the candidate, and SHALL re-fetch the send history via `Feedback_Email_Service.getSendHistory()` so the newly recorded failed attempt is reflected.
3. IF `sendEmail()` fails with an HTTP 404 or HTTP 409 response, THEN THE Feedback_Email_Section SHALL display a generic error message indicating the send could not be completed, without exposing raw HTTP status codes.
4. IF `sendEmail()` fails with a network error (status 0 / no response), THEN THE Feedback_Email_Section SHALL display a generic error message indicating the send could not be completed.
5. WHEN a send attempt completes, whether successfully or with an error, THE Feedback_Email_Section SHALL re-enable the send/resend button and restore its default label.
6. WHEN the recruiter initiates a new send/resend attempt, THE Feedback_Email_Section SHALL clear any success or error indicator displayed from a prior attempt before the new request begins.

---

### Requirement 8: Error Message Content and Retry

**User Story:** As a recruiter, I want error messages to be clear and free of technical noise, with an obvious way to retry, so that transient failures don't block me from completing my task.

#### Acceptance Criteria

1. THE error messages displayed by the Feedback_Email_Section for send failures and send-history retrieval failures SHALL NOT expose raw HTTP status codes, stack traces, or raw response bodies to the recruiter.
2. WHEN the recruiter clicks the retry control shown after a send-history retrieval failure, THE Feedback_Email_Section SHALL clear the current error message and re-invoke `Feedback_Email_Service.getSendHistory(submissionId)`.
3. WHEN the recruiter dismisses a send-result error notification, THE Feedback_Email_Section SHALL clear that error notification without affecting the displayed send history.
