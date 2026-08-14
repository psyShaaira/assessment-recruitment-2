# Requirements Document

## Introduction

This spec covers the frontend implementation for displaying AI-generated feedback reports in the Results & Evaluation page. The backend API is fully implemented — this work adds a `FeedbackService` under `core/feedback/`, a feedback report UI section in the results detail panel, auto-generation logic triggered when a submission is fully marked, loading/error states, and a regenerate capability.

## Glossary

- **Feedback_Service**: Angular injectable service at `core/feedback/feedback.service.ts` responsible for calling the backend feedback report endpoints.
- **Feedback_Report_Section**: The UI region within the results detail panel that renders the structured AI feedback.
- **Results_Component**: The existing `results.component.ts` page that displays submission details in a split-panel layout.
- **FeedbackReportResponse**: The backend response shape: `{ submissionId, content: FeedbackReportContent, aiGenerated, promptVersion, generatedAt }`.
- **FeedbackReportContent**: The structured content object: `{ overallSummary: string, topics: FeedbackTopic[], nextSteps: string[] }`.
- **FeedbackTopic**: An individual topic assessment: `{ name: string, strength: string, weakness: string, recommendation: string }`.
- **Auto_Generation**: The behaviour where the frontend automatically triggers report generation when a fully-marked submission has no existing report.
- **Marking_Status**: The `ResultSummary.markingStatus` field — either `'FULLY_MARKED'` or `'PENDING_REVIEW'`.

---

## Requirements

### Requirement 1: Feedback Service

**User Story:** As a developer, I want a dedicated Angular service for feedback report API calls so that the feedback domain is encapsulated following existing project conventions.

#### Acceptance Criteria

1. THE Feedback_Service SHALL be a standalone injectable service at `core/feedback/feedback.service.ts` using `providedIn: 'root'` and obtaining `HttpClient` via the `inject()` function assigned to a `private readonly http` field.
2. THE Feedback_Service SHALL expose a `generateReport(submissionId: string): Observable<FeedbackReportResponse>` method that POSTs an empty body to `/api/submissions/{submissionId}/feedback-report`.
3. THE Feedback_Service SHALL expose a `getReport(submissionId: string): Observable<FeedbackReportResponse>` method that GETs `/api/submissions/{submissionId}/feedback-report`.
4. THE `core/feedback/` directory SHALL contain a `feedback.model.ts` file exporting the `FeedbackReportResponse`, `FeedbackReportContent`, and `FeedbackTopic` interfaces.
5. IF the `submissionId` parameter passed to `generateReport` or `getReport` is an empty string, THEN THE Feedback_Service SHALL throw an error synchronously before making an HTTP request.

---

### Requirement 2: Feedback Report Section Visibility

**User Story:** As a recruiter, I want the feedback report section to only appear when marking is complete so that I don't see an incomplete or misleading report.

#### Acceptance Criteria

1. WHILE the Marking_Status equals `'FULLY_MARKED'`, THE Results_Component SHALL display the Feedback_Report_Section in the detail panel between the reminder histories section and the answers list.
2. WHILE the Marking_Status equals `'PENDING_REVIEW'`, THE Results_Component SHALL hide the Feedback_Report_Section entirely, rendering no container element or placeholder for it.
3. WHEN the selected submission changes, THE Results_Component SHALL reset the feedback report state by clearing the previously loaded report data, any error messages, and any loading indicators.
4. WHILE the Feedback_Report_Section is visible and the report data is being fetched, THE Results_Component SHALL display a loading indicator within the Feedback_Report_Section area.
5. IF the report data fails to load, THEN THE Results_Component SHALL display an error message indicating the report could not be retrieved and SHALL allow the recruiter to retry the fetch.

---

### Requirement 3: Auto-Generation on Full Mark

**User Story:** As a recruiter, I want a feedback report to be generated automatically when I view a fully-marked submission so that I get immediate insights without a manual step.

#### Acceptance Criteria

1. WHEN a submission with `markingStatus` equal to `'FULLY_MARKED'` is selected and `markingService.getResult()` succeeds, THE Results_Component SHALL call `Feedback_Service.getReport(submissionId)` to check for an existing report.
2. IF `getReport()` returns HTTP 404, THEN THE Results_Component SHALL automatically call `Feedback_Service.generateReport(submissionId)` to create a new report, and display the returned `FeedbackReportResponse` upon success.
3. IF `getReport()` or `generateReport()` returns an HTTP error other than 404, THEN THE Results_Component SHALL display an inline error message indicating that the feedback report could not be loaded and SHALL NOT retry automatically.
4. WHEN `getReport()` returns a valid `FeedbackReportResponse`, THE Results_Component SHALL display the report content without triggering generation.
5. WHILE auto-generation is in progress (between the `generateReport()` call and its response), THE Feedback_Report_Section SHALL display a loading indicator with the text "Generating feedback report…".
6. IF the selected submission has a `markingStatus` other than `'FULLY_MARKED'` (e.g. `'PENDING_REVIEW'`), THEN THE Results_Component SHALL NOT call `Feedback_Service.getReport()` or `Feedback_Service.generateReport()`.
7. WHEN a different submission is selected while auto-generation is still in progress, THE Results_Component SHALL discard the pending response and SHALL NOT display a stale report for the previously selected submission.

---

### Requirement 4: Report Display

**User Story:** As a recruiter, I want the AI feedback displayed in a structured, readable format so that I can quickly understand the candidate's strengths, weaknesses, and recommended next steps.

#### Acceptance Criteria

1. THE Feedback_Report_Section SHALL display the `overallSummary` field as a paragraph at the top of the section.
2. THE Feedback_Report_Section SHALL render each entry in the `topics` array as a card or block showing the topic `name` as a heading, with `strength`, `weakness`, and `recommendation` as labelled sub-sections.
3. THE Feedback_Report_Section SHALL render the `nextSteps` array as an ordered (numbered) list under a "Next Steps" heading.
4. THE Feedback_Report_Section SHALL display the `generatedAt` timestamp formatted using the locale medium date-time format (e.g., "Jun 15, 2025, 2:30:00 PM") below the report content.
5. IF `aiGenerated` is true, THEN THE Feedback_Report_Section SHALL display a badge with the text "AI Generated" at the top of the section adjacent to the section heading.
6. IF `aiGenerated` is false, THEN THE Feedback_Report_Section SHALL NOT display the AI-generated badge.
7. IF the `topics` array is empty, THEN THE Feedback_Report_Section SHALL hide the topics area entirely and display no placeholder.
8. IF the `nextSteps` array is empty, THEN THE Feedback_Report_Section SHALL hide the "Next Steps" heading and list entirely.

---

### Requirement 5: Loading State

**User Story:** As a recruiter, I want clear feedback on loading progress so that I know the system is working when fetching or generating a report.

#### Acceptance Criteria

1. WHILE the Feedback_Service is fetching an existing report, THE Feedback_Report_Section SHALL display a CSS-only inline loading indicator with the text "Loading feedback report…" and an `aria-busy="true"` attribute on the section container.
2. WHILE the Feedback_Service is generating a new report, THE Feedback_Report_Section SHALL display a CSS-only inline loading indicator with the text "Generating feedback report…" and an `aria-busy="true"` attribute on the section container.
3. WHEN the report loads successfully, THE Feedback_Report_Section SHALL replace the loading indicator with the report content (overallSummary, topics, and nextSteps as defined by FeedbackReportContent).
4. IF the fetch or generation request fails, THEN THE Feedback_Report_Section SHALL hide the loading indicator, set `aria-busy="false"`, and display an error message indicating that the report could not be loaded or generated.
5. IF no report has been generated yet and no request is in progress, THEN THE Feedback_Report_Section SHALL display a prompt or button allowing the recruiter to trigger report generation, with no loading indicator visible.

---

### Requirement 6: Error Handling

**User Story:** As a recruiter, I want to see a clear error message if report generation fails so that I know the issue and can retry.

#### Acceptance Criteria

1. IF `generateReport()` returns an HTTP error (non-404) or a network error (status 0 / no response), THEN THE Feedback_Report_Section SHALL hide any loading indicator and display an error message indicating generation failed.
2. IF `getReport()` returns an HTTP error other than 404 or a network error (status 0 / no response), THEN THE Feedback_Report_Section SHALL hide any loading indicator and display an error message indicating retrieval failed.
3. WHEN an error is displayed, THE Feedback_Report_Section SHALL show a "Retry" button that restarts the full GET→404→POST flow from the initial `getReport()` call.
4. THE error message SHALL NOT expose raw HTTP status codes or stack traces to the user.
5. WHEN the user clicks the "Retry" button, THE Feedback_Report_Section SHALL clear the current error message and display the loading state before re-initiating the flow.

---

### Requirement 7: Regenerate Report

**User Story:** As a recruiter, I want to regenerate the feedback report so that I can get updated feedback after making scoring changes.

#### Acceptance Criteria

1. WHEN a report is displayed, THE Feedback_Report_Section SHALL show a "Regenerate" button.
2. WHEN the recruiter clicks "Regenerate", THE Results_Component SHALL call `Feedback_Service.generateReport()` to overwrite the existing report.
3. WHILE regeneration is in progress, THE "Regenerate" button SHALL be disabled and display "Regenerating…" text in place of its default label.
4. WHEN regeneration completes successfully, THE Feedback_Report_Section SHALL replace the displayed report content with the new report content in place, without navigating away from the current view.
5. IF regeneration fails, THEN THE Feedback_Report_Section SHALL keep the previous report visible and display an inline error notification indicating that regeneration failed; the notification SHALL remain visible until the recruiter dismisses it or initiates another regeneration attempt.
6. IF regeneration does not complete within 30 seconds, THEN THE Results_Component SHALL abort the request, re-enable the "Regenerate" button, and display an inline error notification indicating a timeout occurred.

---

### Requirement 8: Feedback Model Types

**User Story:** As a developer, I want strongly-typed interfaces for the feedback API response so that TypeScript strict mode catches contract mismatches at compile time.

#### Acceptance Criteria

1. THE `feedback.model.ts` file SHALL export a `FeedbackTopic` interface with fields: `name: string`, `strength: string`, `weakness: string`, `recommendation: string`.
2. THE `feedback.model.ts` file SHALL export a `FeedbackReportContent` interface with fields: `overallSummary: string`, `topics: FeedbackTopic[]`, `nextSteps: string[]`.
3. THE `feedback.model.ts` file SHALL export a `FeedbackReportResponse` interface with fields: `submissionId: string`, `content: FeedbackReportContent`, `aiGenerated: boolean`, `promptVersion: string`, `generatedAt: string`.
4. THE Feedback_Service SHALL use `FeedbackReportResponse` as the generic type parameter in its HttpClient calls (e.g., `http.get<FeedbackReportResponse>(...)`) so that the returned Observable is typed as `Observable<FeedbackReportResponse>`.
5. WHEN `npx tsc --noEmit` is run against the project, THE TypeScript compiler SHALL produce zero type errors related to the feedback model interfaces and their usage in the Feedback Service.
