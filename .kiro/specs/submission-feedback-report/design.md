# Design Document — Submission Feedback Report

## Overview

Adds an AI-powered feedback report feature under `com.psybergate.recruitment.feedback`. Recruiters trigger report generation via a new REST endpoint; the backend builds a PII-stripped prompt, calls the Groq AI via the existing `AiService` abstraction, parses the structured JSON response, and persists one report per submission (upsert on regenerate). A read-only GET endpoint retrieves the stored report.

The feature requires a small addition to the `ai/` package (`promptForJson` / `response_format` support) but no other cross-package changes.

---

## Architecture

```
FeedbackReportController
  POST /api/submissions/{id}/feedback-report  → generate(submissionId, requestedBy)
  GET  /api/submissions/{id}/feedback-report  → getExisting(submissionId)
        │
        ▼
  FeedbackReportService (interface)
  FeedbackReportServiceImpl (@Service)
        │  reads result
        ├─→ SubmissionService.getResult(submissionId)
        │  fetches tags
        ├─→ QuestionRepository.findAllById(questionIds)
        │  builds prompt, calls AI
        ├─→ AiService.promptForJson(prompt)
        │  parses JSON
        ├─→ ObjectMapper.readValue(rawJson, FeedbackReportContent)
        │  upserts report
        └─→ SubmissionFeedbackReportRepository.save(report)

  com.psybergate.recruitment.feedback/
  ├── FeedbackReportService.java            # interface
  ├── FeedbackReportServiceImpl.java        # @Service
  ├── FeedbackReportController.java         # @RestController
  ├── domain/
  │   └── SubmissionFeedbackReport.java     # @Entity
  ├── dto/
  │   ├── FeedbackTopicDto.java             # record
  │   ├── FeedbackReportContent.java        # record
  │   └── FeedbackReportResponse.java       # record
  └── repository/
      └── SubmissionFeedbackReportRepository.java

  ai/ changes (additive only):
  ├── dto/GroqChatRequest.java              # adds responseFormat field + withJsonObjectFormat()
  ├── client/AiClient.java                  # adds sendPromptForJson(String)
  ├── AiService.java                        # adds promptForJson(String)
  ├── AiServiceImpl.java                    # implements promptForJson
  └── client/GroqClient.java               # routes to json_object mode when requested
```

---

## Components and Interfaces

### FeedbackReportService

```java
public interface FeedbackReportService {
    FeedbackReportResponse generate(UUID submissionId, UUID requestedBy);
    FeedbackReportResponse getExisting(UUID submissionId);
}
```

### FeedbackReportServiceImpl

- `@Service @RequiredArgsConstructor`
- Injects: `SubmissionService`, `AiService`, `SubmissionFeedbackReportRepository`, `QuestionRepository`, `ObjectMapper`
- `generate()`:
  1. `submissionService.getResult(submissionId)` — loads full result (includes candidate name for response, not for prompt)
  2. Flatten leaf question IDs → `questionRepository.findAllById(ids)` → build `Map<UUID, Set<String>> tagsByQuestionId`
  3. `buildPrompt(result, tagsByQuestionId)` — emits system instructions, JSON schema, per-question data (PII-stripped)
  4. `aiService.promptForJson(prompt)` → raw JSON string
  5. `parseContent(rawJson)` → `FeedbackReportContent` (throws `AiResponseException` on parse failure)
  6. Upsert: `findBySubmissionId` → new or existing entity → set fields → `save()`
  7. Return `FeedbackReportResponse`
- `getExisting()`: `findBySubmissionId` → 404 if absent → parse and return

### Prompt structure

```
You are an expert technical recruiter...
Return ONLY a valid JSON object matching this exact schema — no markdown, no extra text:
{ "overallSummary": "...", "topics": [...], "nextSteps": [...] }

Assessment: <title>
Total score: X / Y
[If partial: "Note: N of M questions are not yet scored — base feedback only on scored responses below."]

Questions and answers:
- Question: <title>
  Tags: <tag1, tag2>
  Candidate answer: <text or "(no answer)">
  Score: X / Y
[...one entry per scored question only...]
```

Unscored questions are skipped entirely from the body — they appear only in the count note.

### FeedbackReportController

```java
@RestController
@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")
@RequiredArgsConstructor
public class FeedbackReportController {
    POST /api/submissions/{submissionId}/feedback-report  → generate(submissionId, auth.getName())
    GET  /api/submissions/{submissionId}/feedback-report  → getExisting(submissionId)
}
```

---

## Data Models

### SubmissionFeedbackReport (entity)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | PK, generated |
| `submissionId` | UUID | unique, FK to candidate_submissions |
| `content` | String (TEXT) | raw JSON from Groq |
| `aiGenerated` | boolean | default true |
| `promptVersion` | String | `"v1"` |
| `generatedAt` | Instant | set on save |
| `generatedBy` | UUID | staff user, nullable |

### DTOs

```java
record FeedbackTopicDto(String topic, String strengths, String weaknesses) {}

record FeedbackReportContent(
    String overallSummary,
    List<FeedbackTopicDto> topics,
    List<String> nextSteps
) {}

record FeedbackReportResponse(
    UUID submissionId,
    FeedbackReportContent content,
    boolean aiGenerated,
    String promptVersion,
    Instant generatedAt
) {}
```

### GroqChatRequest changes

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroqChatRequest(
    String model,
    List<GroqMessage> messages,
    double temperature,
    @JsonProperty("response_format") Map<String, String> responseFormat
) {
    public GroqChatRequest(String model, List<GroqMessage> messages, double temperature) { ... }
    public GroqChatRequest withJsonObjectFormat() { ... }
}
```

---

## Error Handling

| Condition | Behaviour |
|---|---|
| Submission not found | `SubmissionService.getResult()` throws 404 (existing behaviour) |
| No report exists on GET | `FeedbackReportService.getExisting()` throws `ResponseStatusException(404)` |
| Groq returns malformed JSON | `parseContent()` catches `JsonProcessingException`, throws `AiResponseException` |
| Groq unavailable / 5xx | `AiService` throws typed AI exception, propagates to `GlobalExceptionHandler` |
| Caller lacks RECRUITER/ADMIN | Spring Security returns 403 before controller is entered |

---

## Testing Strategy

### Unit — FeedbackReportServiceImplTest (`@ExtendWith(MockitoExtension.class)`)

- Fully-marked submission → report returned with all fields populated
- Partially-marked submission → prompt contains "not yet scored" note
- Prompt never contains candidate name/email (PII assertion on captured argument)
- Malformed AI JSON → `AiResponseException` thrown, no raw exception leaks
- Existing report found → same entity instance updated (upsert check)
- No existing report → 404 `ResponseStatusException`

### Slice — FeedbackReportControllerTest (`@WebMvcTest`)

- `RECRUITER` role → 200 on POST and GET
- `ADMIN` role → 200 on POST
- `CANDIDATE` role → 403
- Unauthenticated → 401
