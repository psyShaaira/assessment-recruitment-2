# Tasks: AI-Generated Feedback Email

## Implementation

- [x] Inject `AiService` into `FeedbackEmailServiceImpl`
- [x] Add `@Slf4j` for fallback warning logging
- [x] Implement `buildEmailPrompt()` method
- [x] Implement `generateAiBody()` with try/catch fallback
- [x] Implement `renderStaticBody()` (renamed from `renderBody()`, enhanced with assessment title)
- [x] Wire `generateAiBody()` into `sendFeedbackEmail()` flow

## Testing

- [x] Unit test: AI success sends AI-generated body
- [x] Unit test: prompt contains candidate name, assessment title, feedback data
- [x] Unit test: AI failure falls back to static template
- [x] Unit test: generic RuntimeException also triggers fallback
- [x] Unit test: 409 on not-fully-marked
- [x] Unit test: 404 on missing feedback report
- [x] Unit test: 502 on email send failure with FAILED log row

## Documentation

- [x] OpenSpec change created (`openspec/changes/ai-feedback-email/`)
- [x] Kiro spec created (`.kiro/specs/ai-feedback-email/`)
