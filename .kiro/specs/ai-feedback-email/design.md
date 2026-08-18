# Design: AI-Generated Feedback Email

## Overview

Enhancement to the existing `feedbackemail` package. No new endpoints, entities, or migrations. The change is entirely within `FeedbackEmailServiceImpl`.

## Modified Files

| File | Change |
|------|--------|
| `FeedbackEmailServiceImpl.java` | Inject `AiService`, add `generateAiBody()`, `buildEmailPrompt()`, rename `renderBody()` → `renderStaticBody()` |
| `FeedbackEmailServiceImplTest.java` | New test class with 7 unit tests |

## Sequence Diagram

```
Recruiter → Controller → FeedbackEmailServiceImpl
                              │
                              ├─ getResult() → validate FULLY_MARKED
                              ├─ findBySubmissionId() → get feedback report
                              ├─ resolveCandidate() → get name + email
                              ├─ parseContent() → FeedbackReportContent
                              ├─ generateAiBody()
                              │     ├─ buildEmailPrompt() → construct prompt
                              │     ├─ aiService.prompt() → call Groq
                              │     │     ├─ SUCCESS → use AI text
                              │     │     └─ EXCEPTION → renderStaticBody()
                              │     └─ return email body
                              ├─ emailService.sendFeedbackReport()
                              └─ persist SENT log
```

## Prompt Structure

```
You are writing a professional feedback email to a job candidate...
[tone instructions]

Candidate first name: {name}
Assessment title: {title}

Overall summary: {summary}

Topics:
- {topic}
  Strengths: {strengths}
  Areas for improvement: {weaknesses}

Suggested next steps:
- {step}
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| AI returns successfully | Use AI response as email body |
| AI throws any exception | Log warning, use static template, continue sending |
| Email send fails | Log FAILED row (REQUIRES_NEW txn), throw 502 |
