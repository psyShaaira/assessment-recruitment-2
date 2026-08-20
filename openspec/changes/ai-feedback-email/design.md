# Design: AI-Generated Feedback Email

## Architecture

No new packages, entities, or endpoints. This is a behavioral change within `FeedbackEmailServiceImpl`.

### Modified Components

| Component | Change |
|-----------|--------|
| `FeedbackEmailServiceImpl` | Inject `AiService`; replace `renderBody()` with `generateAiBody()` + `renderStaticBody()` fallback |

### Data Flow

```
POST /api/submissions/{id}/feedback-report/email
  → FeedbackEmailController.send()
  → FeedbackEmailServiceImpl.sendFeedbackEmail()
    → submissionService.getResult()           // validate fully marked
    → feedbackReportRepo.findBySubmissionId()  // get stored feedback JSON
    → resolveCandidate()                       // get candidate name + email
    → parseContent()                           // JSON → FeedbackReportContent
    → generateAiBody()                         // NEW: AI prompt → Groq → email body
        ├─ SUCCESS: use AI response as body
        └─ FAILURE: log warning, use renderStaticBody() instead
    → emailService.sendFeedbackReport()        // send via JavaMailSender
    → persist SENT log row
```

### AI Prompt Design

- Mode: plain text (`aiService.prompt()`, not `promptForJson()`)
- Tone instructions: warm, encouraging, professional, actionable
- Constraints: no subject line, no markdown, plain text only
- Context provided: candidate first name, assessment title, overall summary, topics (strengths + weaknesses), next steps
- Sign-off: "The Psybergate Recruitment Team"

### Fallback Strategy

Any exception from `aiService.prompt()` (network, timeout, auth, rate limit, parse error) is caught and triggers the static template. The email is still sent — the candidate is never left without feedback due to an AI outage.

### Security Considerations

- The prompt contains the candidate's first name only (no email, no last name, no PII beyond what appears in the email greeting).
- Assessment content (questions, answers, scores) is NOT sent to the AI — only the recruiter-generated feedback summary.

## Alternatives Considered

1. **Use `promptForJson()` and parse a structured response** — rejected; plain text output is simpler and maps directly to the email body without a JSON→text conversion step.
2. **Store the AI-generated body in a new column for preview before sending** — deferred to a future "preview before send" feature.
3. **Use a different AI provider** — out of scope; the existing `AiService` abstraction handles provider selection.
