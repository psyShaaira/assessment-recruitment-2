# Requirements Document

## Introduction

This feature lets a recruiter or admin email an already-generated AI feedback report (from the `submission-feedback-report` feature) to the candidate who owns the submission. The candidate has no login/account, so the report content is rendered inline in the email body rather than delivered via a link. Sending is only permitted once a submission is fully marked and a report has already been generated; every send attempt is recorded in a send-history log, mirroring the existing `ReminderSendLog` pattern. This spec is backend-only.

## Glossary

- **FeedbackEmail**: The email sent to a candidate containing their AI-generated feedback report content.
- **FeedbackEmailSendLog**: A persisted record of one feedback-email send attempt for a submission (timestamp, sent-by staff user, outcome).
- **FullyMarked**: A `CandidateSubmission` whose `markingStatus` (as computed by `SubmissionService.getResult()`) equals `FULLY_MARKED`.
- **SubmissionFeedbackReport**: The existing entity (from `submission-feedback-report`) holding the AI-generated `content` (`overallSummary`, `topics[]`, `nextSteps[]`) for a submission.
- **Recruiter**: A staff user holding the `RECRUITER` or `ADMIN` role.
- **Strong_Topic**: An entry in the report's `topics[]` whose `strengths` field is non-null and, after trimming leading and trailing whitespace, non-empty.
- **Weak_Topic**: An entry in the report's `topics[]` whose `weaknesses` field is non-null and, after trimming leading and trailing whitespace, non-empty.

---

## Requirements

### Requirement 1: Database Schema — Send History

**User Story:** As a system, I want feedback-email send attempts persisted so that recruiters can see when and by whom a candidate was emailed their feedback.

#### Acceptance Criteria

1. THE system SHALL create a `feedback_email_send_log` table via a Flyway migration with columns: `id` (UUID PK), `submission_id` (UUID FK → `candidate_submissions`, NOT NULL), `sent_at` (TIMESTAMPTZ NOT NULL), `sent_by` (UUID NULL, FK → `users.id`), `status` (VARCHAR NOT NULL, one of `SENT` or `FAILED`), `failure_reason` (TEXT NULL).
2. THE `feedback_email_send_log` table SHALL NOT enforce a unique constraint on `submission_id`, so that multiple send attempts (resends) can be recorded for the same submission.
3. WHEN a feedback email send is attempted, THE system SHALL insert one `feedback_email_send_log` row with `status` set to either `SENT` or `FAILED`, reflecting the outcome of that attempt.
4. THE `feedback_email_send_log` table SHALL enforce, via a CHECK constraint, that `failure_reason` is non-null when `status` is `FAILED` and is null when `status` is `SENT`.

---

### Requirement 2: Send Feedback Email

**User Story:** As a recruiter, I want to email a candidate's AI feedback report to them so that the candidate receives structured feedback without needing to log in.

#### Acceptance Criteria

1. WHEN a recruiter or admin POSTs to `/api/submissions/{submissionId}/feedback-report/email`, THE system SHALL look up the submission's `markingStatus` via `SubmissionService.getResult(submissionId)`.
2. IF the `submissionId` does not correspond to an existing `CandidateSubmission`, THEN THE system SHALL return HTTP 404 without sending an email or writing a send-log row.
3. IF the submission exists and its `markingStatus` is not `FULLY_MARKED`, THEN THE system SHALL return HTTP 409 without sending an email or writing a send-log row.
4. IF the submission is `FULLY_MARKED` and no `SubmissionFeedbackReport` exists for the submission, THEN THE system SHALL return HTTP 404 without sending an email or writing a send-log row.
5. WHEN the submission is `FULLY_MARKED` and a `SubmissionFeedbackReport` exists, THE system SHALL retrieve the candidate's name and email via the submission's candidate relationship.
6. WHEN the candidate's name and email have been retrieved per Acceptance Criterion 5, THE system SHALL render a plain-text email body addressed to the candidate, composed per Acceptance Criteria 7–19.
7. THE rendered email body SHALL open with a greeting line containing the candidate's first name, formatted as "Hi `<firstName>`,".
8. THE rendered email body SHALL include, immediately following the greeting line, a fixed introductory line stating "Here is your feedback on your recent assessment:".
9. THE rendered email body SHALL include a score sentence stating the candidate's overall score as a whole-number percentage, computed from the `ResultSummaryResponse`'s `totalScore` divided by `maxScore`.
10. THE system SHALL format any list of topic names rendered into the email body by separating items with a comma, except that the final two items in a list of two or more SHALL be joined with the word "and" preceded by no comma, and a list containing exactly one item SHALL render that item alone with no separator or conjunction.
11. WHEN the report's `topics[]` include at least one topic whose `strengths` field is non-null and, after trimming leading and trailing whitespace, non-empty (a "Strong_Topic"), THE system SHALL include in the rendered email body a strengths sentence naming every Strong_Topic, formatted per Acceptance Criterion 10.
12. IF the report's `topics[]` include no Strong_Topic, THEN THE system SHALL omit the strengths sentence from the rendered email body.
13. WHEN the report's `topics[]` include at least one topic whose `weaknesses` field is non-null and, after trimming leading and trailing whitespace, non-empty (a "Weak_Topic"), THE system SHALL include in the rendered email body a weaknesses sentence naming every Weak_Topic, formatted per Acceptance Criterion 10.
14. IF the report's `topics[]` include no Weak_Topic, THEN THE system SHALL omit the weaknesses sentence from the rendered email body.
15. WHEN a topic in the report's `topics[]` is both a Strong_Topic and a Weak_Topic, THE system SHALL include that topic's name in both the strengths sentence and the weaknesses sentence.
16. THE rendered email body SHALL include, immediately preceding the next-steps bullet list, a fixed transition line introducing the next steps.
17. THE rendered email body SHALL include the full `nextSteps[]` list rendered as bullet points, unchanged in content from the `SubmissionFeedbackReport`.
18. THE rendered email body SHALL close with an encouraging, supportive sign-off sentence immediately preceding the "The Psybergate Recruitment Team" signature line.
19. THE rendered email body SHALL consist of exactly the following elements, in order: the greeting line, the introductory line, the score sentence, the strengths sentence (where a Strong_Topic exists), the weaknesses sentence (where a Weak_Topic exists), the transition line, the next-steps bullet list, the sign-off sentence, and the signature line, with no additional narrative paragraph beyond the strengths and weaknesses sentences.
20. WHEN the email body has been rendered per Acceptance Criteria 6–19, THE system SHALL send the rendered email to the candidate's email address using the existing `EmailService` abstraction.
21. WHEN the email is sent successfully, THE system SHALL insert a `feedback_email_send_log` row with `status` `SENT`, the sending staff user's ID, and the current timestamp.
22. IF the `EmailService` call throws an exception, THEN THE system SHALL insert a `feedback_email_send_log` row with `status` `FAILED` and a non-blank `failure_reason`, and SHALL return HTTP 502 to the caller.
23. IF a `feedback_email_send_log` row was written with `status` `FAILED`, THEN THE system SHALL NOT modify the `SubmissionFeedbackReport` or `CandidateSubmission` records for that submission.
24. THE endpoint SHALL return a `FeedbackEmailSendResponse` with `submissionId`, `status`, and `sentAt` on success.

---

### Requirement 3: Resend Feedback Email

**User Story:** As a recruiter, I want to resend the feedback email if a candidate says they didn't receive it, so that delivery issues don't block the candidate from getting their feedback.

#### Acceptance Criteria

1. IF at least one prior `feedback_email_send_log` row exists for a submission and a subsequent POST to `/api/submissions/{submissionId}/feedback-report/email` is made for that submission, THEN THE system SHALL evaluate that POST against exactly the same checks and outcomes defined in Requirement 2, Acceptance Criteria 2–24, irrespective of the status of any prior row.
2. IF the submission is not `FULLY_MARKED` at the time of a resend attempt (e.g. a score was reverted since a prior send), THEN THE system SHALL return HTTP 409 without sending an email or writing a send-log row, per Requirement 2, Acceptance Criterion 3, regardless of the status of any prior send attempts for that submission.
3. WHEN a resend attempt results in either a `SENT` or `FAILED` outcome, THE system SHALL insert a new `feedback_email_send_log` row for that outcome and SHALL NOT update, overwrite, or delete any prior `feedback_email_send_log` row for that submission.

---

### Requirement 4: Retrieve Send History

**User Story:** As a recruiter, I want to see the history of feedback-email send attempts for a submission so that I know whether and when the candidate was emailed.

#### Acceptance Criteria

1. WHEN a recruiter or admin GETs `/api/submissions/{submissionId}/feedback-report/email` for a `submissionId` that corresponds to an existing `CandidateSubmission`, THE system SHALL respond with HTTP 200 containing the list of `feedback_email_send_log` rows for that submission ordered by `sent_at` descending, with each entry including its `sent_at` timestamp, `status`, `sent_by` staff-user identifier (if recorded), and `failure_reason` (if the attempt failed).
2. IF the `submissionId` does not correspond to an existing `CandidateSubmission`, THEN THE system SHALL return HTTP 404.
3. IF the `submissionId` corresponds to an existing `CandidateSubmission` with no recorded send attempts, THEN THE system SHALL return HTTP 200 with an empty list.

---

### Requirement 5: Access Control

**User Story:** As a security administrator, I want feedback-email endpoints restricted to staff roles so that candidates and unauthenticated callers cannot trigger or view feedback-email activity.

#### Acceptance Criteria

1. THE `POST` and `GET` endpoints under `/api/submissions/{submissionId}/feedback-report/email` SHALL be accessible only to callers authenticated with the `RECRUITER` or `ADMIN` role.
2. IF a request to either endpoint is made without valid authentication (missing, malformed, or expired credentials), THEN THE system SHALL return HTTP 401 and SHALL NOT include any submission, send-log, or feedback-report data in the response body.
3. IF a request to either endpoint is made by an authenticated caller who does not hold the `RECRUITER` or `ADMIN` role (e.g. `CANDIDATE`), THEN THE system SHALL return HTTP 403 and SHALL NOT include any submission, send-log, or feedback-report data in the response body.

---

### Requirement 6: Failure Isolation

**User Story:** As a recruiter, I want a failed feedback-email send to be an isolated, non-blocking event so that it doesn't corrupt the submission's marking or report state, consistent with how AI failures never block manual marking.

#### Acceptance Criteria

1. IF the `EmailService` call throws an exception for any reason (as described in Requirement 2, Acceptance Criterion 22), THEN THE system SHALL leave the submission's `markingStatus` (as computed by `SubmissionService.getResult()`), all `AnswerScore` records for the submission, and the persisted `SubmissionFeedbackReport` for the submission unchanged from their state immediately before the send attempt.
2. IF the `EmailService` call throws an exception, THEN THE system SHALL confine all persisted evidence of the failure to the `status` and `failure_reason` columns of the newly inserted `feedback_email_send_log` row (per Requirement 2, Acceptance Criterion 22), and SHALL NOT create, modify, or delete any row in the `candidate_submissions`, `answer_scores`, or `submission_feedback_reports` tables as a result of the failure.
3. IF the `EmailService` call throws an exception on a given attempt, THEN THE system SHALL accept and process a subsequent POST to `/api/submissions/{submissionId}/feedback-report/email` for the same submission according to Requirement 3, without imposing any limit on the number of retry attempts or any additional restriction arising from the prior failure.
