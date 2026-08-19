# Bugfix Requirements Document

## Introduction

When a recruiter or admin sends the AI-generated feedback email to a candidate (`POST /api/submissions/{submissionId}/feedback-report/email`), the "next steps" bullet list in the email body sometimes ends with a literal trailing ellipsis (`"..."` or `"…"`), because the Groq LLM's `nextSteps[]` strings are persisted and rendered verbatim with no sanitization. This was visually confirmed via a screenshot of a real received email: the last next-step bullet ended in "3 dots" attached to the end of that bullet's sentence, immediately before the sign-off line. The email must read as a complete message from top to bottom, with no trailing/dangling ellipsis in any next-step bullet.

The fix sanitizes each `nextSteps[]` string at the point the AI's JSON response is parsed and persisted (`FeedbackReportServiceImpl.generate()`), stripping a trailing ellipsis (`"..."`, `"…"`, or either combined with trailing whitespace) before the content is saved to `SubmissionFeedbackReport.content`. This makes the parse/persist step the single source of truth: both the feedback email and the existing report-display frontend (which also renders `nextSteps[]` verbatim) benefit from one fix, and future regenerations are covered automatically. Already-persisted reports are not retroactively cleaned — only reports generated or regenerated after the fix ships will have sanitized `nextSteps[]`.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a `nextSteps[]` string returned by the AI ends with a literal trailing ellipsis (`"..."`, the single-character `"…"`, or either followed by trailing whitespace) THEN the system persists that string unchanged in `SubmissionFeedbackReport.content` and later renders it verbatim as a bullet in the feedback email body, leaving the dangling ellipsis visible to the candidate.

1.2 WHEN a `nextSteps[]` string returned by the AI ends with a literal trailing ellipsis THEN the system also renders that string unchanged in the submission-feedback-report frontend's "Next Steps" list, leaving the same dangling ellipsis visible to the recruiter.

### Expected Behavior (Correct)

2.1 WHEN the AI's JSON response is parsed during `FeedbackReportServiceImpl.generate()` and a `nextSteps[]` string ends with a trailing ellipsis (`"..."` or `"…"`, optionally followed by trailing whitespace) THEN the system SHALL strip that trailing ellipsis (and any trailing whitespace before or after it) from the string before persisting it to `SubmissionFeedbackReport.content`.

2.2 WHEN a sanitized `nextSteps[]` string (per 2.1) is later rendered as a bullet in the feedback email body THEN the system SHALL render the sanitized string with no trailing ellipsis, so the bullet reads as a complete sentence.

2.3 WHEN stripping a trailing ellipsis from a `nextSteps[]` string leaves trailing whitespace THEN the system SHALL also trim that trailing whitespace so the persisted string ends with a normal terminal character (e.g. a letter, digit, or existing punctuation such as a period) or is empty.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a `nextSteps[]` string does not end with a trailing ellipsis THEN the system SHALL CONTINUE TO persist and render that string exactly as returned by the AI, with no characters added, removed, or altered.

3.2 WHEN a `nextSteps[]` string contains an ellipsis (`"..."` or `"…"`) in the middle of the sentence (not at the very end) THEN the system SHALL CONTINUE TO leave that internal ellipsis unchanged.

3.3 WHEN the `overallSummary` field or any topic's `strengths`/`weaknesses` field ends with a trailing ellipsis THEN the system SHALL CONTINUE TO persist and render that field unchanged (out of scope for this fix — confirmed only for `nextSteps[]`).

3.4 WHEN the AI's JSON response cannot be parsed as valid `FeedbackReportContent` THEN the system SHALL CONTINUE TO throw `AiResponseException`, unaffected by the sanitization step.

3.5 WHEN a `nextSteps[]` list is empty THEN the system SHALL CONTINUE TO render no next-step bullets in the email body.

3.6 WHEN the submission is not fully marked, has no feedback report, or the submission/candidate does not exist THEN the system SHALL CONTINUE TO return the same HTTP status codes (404/409) as before this fix, unaffected by the sanitization step.

### Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type String  // a single nextSteps[] entry as returned by the AI
  OUTPUT: boolean

  RETURN X is not null
         AND (trimTrailingWhitespace(X) ENDS WITH "..." OR trimTrailingWhitespace(X) ENDS WITH "…")
END FUNCTION
```

### Property Specification

```pascal
// Property: Fix Checking
FOR ALL X WHERE isBugCondition(X) DO
  result ← sanitizeNextStep'(X)
  ASSERT NOT (trimTrailingWhitespace(result) ENDS WITH "...")
     AND NOT (trimTrailingWhitespace(result) ENDS WITH "…")
END FOR
```

### Preservation Goal

```pascal
// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT sanitizeNextStep(X) = sanitizeNextStep'(X)
  // i.e. sanitizeNextStep' is the identity function for non-buggy inputs
END FOR
```
