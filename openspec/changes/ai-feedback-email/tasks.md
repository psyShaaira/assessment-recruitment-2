# Tasks: AI-Generated Feedback Email

## Implementation Tasks

- [x] Add `AiService` dependency to `FeedbackEmailServiceImpl` (constructor injection via `@RequiredArgsConstructor`)
- [x] Add `@Slf4j` annotation for fallback warning logging
- [x] Implement `buildEmailPrompt(FeedbackReportContent, candidateName, assessmentTitle)` — constructs the AI prompt with tone instructions and feedback data
- [x] Implement `generateAiBody(FeedbackReportContent, candidateName, assessmentTitle)` — calls `aiService.prompt()`, catches all exceptions, falls back to static template
- [x] Rename old `renderBody()` to `renderStaticBody()` and add `assessmentTitle` parameter for richer fallback output
- [x] Update `sendFeedbackEmail()` to call `generateAiBody()` instead of old `renderBody()`

## Test Tasks

- [x] Create `FeedbackEmailServiceImplTest` with Mockito setup (mock `AiService`, `EmailService`, repos)
- [x] Test: AI success path — verify AI-generated body is passed to `emailService.sendFeedbackReport()`
- [x] Test: prompt contains candidate name, assessment title, and feedback content
- [x] Test: AI failure (AiResponseException) — falls back to static template, email still sent
- [x] Test: AI failure (generic RuntimeException) — same fallback behavior
- [x] Test: 409 when submission not fully marked — `aiService` never called
- [x] Test: 404 when no feedback report — `aiService` never called
- [x] Test: email send failure — FAILED log persisted, 502 returned
