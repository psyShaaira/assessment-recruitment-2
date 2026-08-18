# AI-Generated Feedback Email to Candidate

## Summary

Enhance the existing feedback email feature to use Groq AI for generating a candidate-friendly email body from the structured feedback report, instead of the current static template rendering.

## Problem

The current `feedbackemail` package sends candidates a rigidly templated email that directly lists strengths/weaknesses/next-steps in a structured format. While functional, this feels impersonal and doesn't explain the feedback in context. Recruiters want the email to read like a thoughtful, encouraging message that explains what the feedback means for the candidate.

## Proposed Solution

- Inject `AiService` into `FeedbackEmailServiceImpl`.
- When sending a feedback email, build a prompt from the `FeedbackReportContent` (overall summary, topics with strengths/weaknesses, next steps) along with the candidate's first name and assessment title.
- Send the prompt to Groq (plain text mode, not JSON) and use the AI response as the email body.
- If the AI call fails for any reason, fall back silently to a static template so the candidate still receives feedback.
- Log a warning on fallback for observability.

## Scope

### In Scope
- AI body generation via `AiService.prompt()` in the existing `sendFeedbackEmail` flow.
- Graceful degradation (static fallback) on AI failure.
- Unit tests covering AI happy path, fallback, and prompt content.

### Out of Scope
- Editing the AI-generated email before sending (future enhancement).
- Candidate self-service trigger (remains staff-initiated only).
- HTML email formatting (remains plain text).
- New database schema changes (existing `feedback_email_send_log` table is sufficient).

## Affected Capabilities
- `feedback-email-send` (existing capability, enhanced)
