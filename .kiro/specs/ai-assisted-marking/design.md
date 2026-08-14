# Design Document — AI-Assisted Marking

## Overview

Adds a new `marking.ai` sub-package that layers an on-demand AI marking
suggestion capability on top of the existing manual marking workflow. A
recruiter triggers generation for a single `CandidateAnswer` belonging to a
`TEXT` or `CODE_SUBMISSION` question; the service builds a prompt from the
question and answer content, calls the existing `AiService`, parses a score
and rationale out of the plain-text response, clamps the score to the
question's valid range, and persists the result as a new `AiMarkingSuggestion`
row — completely separate from `AnswerScore`. Existing manual marking
endpoints (`SubmissionController`, `SubmissionService`) are untouched.

The design mirrors the existing `ai-integration-foundation` and `marking`
package conventions: package-by-feature, constructor injection of interfaces,
Flyway-managed schema, `@ResponseStatus`-annotated exceptions picked up by the
existing `GlobalExceptionHandler`.

---

## Architecture

```
SubmissionController (existing)          AiMarkingController (new)
        │                                          │
        ▼                                          ▼
SubmissionService (existing, unchanged)   AiMarkingService (new interface)
                                                    │
                                          AiMarkingServiceImpl (new)
                                          ┌─────────────────────────────┐
                                          │ 1. validate question type   │
                                          │    + answer content         │
                                          │ 2. build prompt             │
                                          │ 3. call AiService.prompt()  │
                                          │ 4. parse score + rationale  │
                                          │ 5. clamp score to [0,max]   │
                                          │ 6. upsert AiMarkingSuggestion│
                                          └──────────────┬───────────────┘
                                                          │ injects
                                                          ▼
                                                     AiService (existing,
                                                     from ai-integration-
                                                     foundation)
```

No change to `MarkingService`, `SubmissionService`, `AnswerScore`, or any
existing endpoint. `AiMarkingServiceImpl` reads `CandidateAnswer` and
`Question` via the existing shared repositories and writes only to the new
`ai_marking_suggestions` table.

---

## Components and Interfaces

### Package structure

```
com.psybergate.recruitment.marking/
├── ai/
│   ├── AiMarkingService.java              # interface
│   ├── AiMarkingServiceImpl.java          # @Service
│   ├── AiMarkingController.java           # @RestController
│   ├── AiMarkingResponseException.java    # @ResponseStatus(502)
│   ├── AiMarkingPromptBuilder.java        # pure prompt-building helper
│   └── dto/
│       ├── AiMarkingSuggestionResponse.java   # record
│       └── GenerateAiMarkingSuggestionRequest.java (not needed — no body; see API)
```

`AiMarkingSuggestion` (JPA entity) and `AiMarkingSuggestionRepository` follow
the existing convention of shared entities/repositories used by a single
feature but co-located with other marking entities — placed in the existing
top-level `domain/` and `repository/` packages (matching where `AnswerScore`
and `CandidateAnswer` already live), since they reference and are referenced
by other `marking`-domain data and may be reused by future reporting
features.

```
com.psybergate.recruitment.domain/
└── AiMarkingSuggestion.java               # @Entity

com.psybergate.recruitment.repository/
└── AiMarkingSuggestionRepository.java     # JpaRepository
```

### AiMarkingService

```java
public interface AiMarkingService {

    /** Generates a new suggestion, replacing any prior one for the answer. */
    AiMarkingSuggestionResponse generateSuggestion(UUID submissionId, UUID questionId);

    /** Returns the most recent suggestion, or 404 if none exists. */
    AiMarkingSuggestionResponse getSuggestion(UUID submissionId, UUID questionId);
}
```

Both methods are keyed by `(submissionId, questionId)` rather than
`answerId`, matching the existing `scoreByQuestionId` pattern in
`SubmissionService` — this lets the controller mirror the existing
`/api/submissions/{submissionId}/questions/{questionId}/score` route shape
and lets `AiMarkingServiceImpl` validate that the question belongs to the
submission's assessment before touching any answer data (Requirement 1.5).

### AiMarkingServiceImpl — generateSuggestion flow

1. Load `CandidateSubmission` by `submissionId` (404 if absent).
2. Resolve the `Question` for `questionId` and confirm it belongs to the
   submission's assessment (via `AssessmentQuestionRepository`, reusing the
   same top-level/GROUP-member lookup pattern already used in
   `SubmissionServiceImpl.scoreByQuestionId`) — 404 otherwise (Req 1.5).
3. If `question.getType()` is `MCQ` or `GROUP` → throw
   `ResponseStatusException(BAD_REQUEST, ...)` (Req 1.2). No `AiService` call.
4. Look up `CandidateAnswer` by `(submissionId, questionId)`. If absent, or
   `textContent` is null/blank → throw
   `ResponseStatusException(BAD_REQUEST, ...)` (Req 1.3). No `AiService`
   call.
5. Build the prompt via `AiMarkingPromptBuilder.build(question, answer)`
   (Req 3.1–3.3).
6. Call `aiService.prompt(promptText)`. Exceptions
   (`AiAuthenticationException`, `AiCommunicationException`,
   `AiTimeoutException`, `AiRateLimitException`, `AiResponseException`)
   propagate unchanged (Req 6.1) — no catch block, matching the
   `AiServiceImpl` no-catch convention.
7. Parse the raw response text for a score line and a rationale section
   (format defined by the prompt itself, see below). If either cannot be
   parsed → throw `AiMarkingResponseException` (new, `@ResponseStatus(502)`)
   (Req 2.4). No suggestion persisted.
8. Clamp the parsed score into `[0, question.getMaxScore()]` (Req 2.2–2.3).
9. Upsert: find existing `AiMarkingSuggestion` by `candidateAnswerId`; if
   present, overwrite its `score`, `rationale`, `generatedAt`; else create a
   new row (Req 1.4). Save and return the mapped response DTO.

Everything above runs in a single `@Transactional` method. Nothing in this
flow writes to `answer_scores` (Req 4.1, 5.1).

### AiMarkingServiceImpl — getSuggestion flow

1. Resolve submission + question exactly as in step 1–2 above (404 on
   mismatch, Req 1.5).
2. Look up `CandidateAnswer` by `(submissionId, questionId)`; if absent →
   404 "no suggestion exists" (Req 4.3).
3. Look up `AiMarkingSuggestion` by `candidateAnswerId`; if absent → 404
   "no suggestion exists" (Req 4.3).
4. Map to response DTO and return (Req 4.2).

### AiMarkingPromptBuilder

Pure static/stateless helper (no Spring bean needed, but implemented as a
`@Component` with a single method for testability consistency with the rest
of the codebase):

```java
public String build(Question question, CandidateAnswer answer) {
    // Includes: question title, question body, max score, answer text content,
    // and (if CodeSubmissionQuestion with non-blank languageHint) the language hint.
    // Instructs the model to reply in a fixed, parseable two-part format:
    //   SCORE: <integer>
    //   RATIONALE: <free text>
}
```

The prompt explicitly instructs the AI to respond in the `SCORE:` /
`RATIONALE:` format so that `AiMarkingServiceImpl` can parse it with a simple,
deterministic regex rather than JSON (avoids depending on `promptForJson`
behavior and keeps parsing failures easy to reason about). Only data drawn
from the single `question` and `answer` arguments passed in is ever included
— no repository access inside the builder — which is what makes Requirement
3.3 (no other candidate/submission data, no PII) structurally guaranteed
rather than merely tested.

### AiMarkingController

```java
@RestController
@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")
@RequiredArgsConstructor
public class AiMarkingController {

    private final AiMarkingService aiMarkingService;

    @PostMapping("/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion")
    public ResponseEntity<AiMarkingSuggestionResponse> generate(
            @PathVariable UUID submissionId, @PathVariable UUID questionId) {
        return ResponseEntity.ok(aiMarkingService.generateSuggestion(submissionId, questionId));
    }

    @GetMapping("/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion")
    public ResponseEntity<AiMarkingSuggestionResponse> get(
            @PathVariable UUID submissionId, @PathVariable UUID questionId) {
        return ResponseEntity.ok(aiMarkingService.getSuggestion(submissionId, questionId));
    }
}
```

`@PreAuthorize("hasAnyRole('RECRUITER','ADMIN')")` at the class level mirrors
`SubmissionController` exactly, satisfying Requirement 7 (403 for any other
role, enforced by Spring Security before the method body runs).

**Security note:** these are new authenticated, role-restricted endpoints
under the existing JWT-secured `/api/**` surface — no new unauthenticated
routes are introduced.

---

## Data Models

### AiMarkingSuggestion entity

```java
@Entity
@Table(name = "ai_marking_suggestions")
@Getter @Setter @NoArgsConstructor
public class AiMarkingSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "candidate_answer_id", nullable = false, unique = true)
    private UUID candidateAnswerId;

    @Column(nullable = false)
    private int score;

    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
```

`candidateAnswerId` is a plain UUID column (not a `@ManyToOne`), matching the
existing `AnswerScore.candidateAnswerId` pattern. The `unique` constraint
enforces "at most one current suggestion per answer" at the database level,
supporting the upsert-by-replace behavior (Req 1.4) and making it structurally
distinguishable from `AnswerScore.markedBy` (Req 4.4) — `AiMarkingSuggestion`
has no marker/user column at all, since it is machine-generated, not
recruiter-attributed.

### Flyway migration — V24__create_ai_marking_suggestions.sql

```sql
-- AI-assisted marking: stores the latest AI-generated suggestion per answer
CREATE TABLE ai_marking_suggestions (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    candidate_answer_id UUID        NOT NULL,
    score               INTEGER     NOT NULL,
    rationale           TEXT        NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ai_marking_suggestions_pk                  PRIMARY KEY (id),
    CONSTRAINT uq_ai_marking_suggestions_candidate_answer UNIQUE (candidate_answer_id),
    CONSTRAINT ai_marking_suggestions_candidate_answer_fk FOREIGN KEY (candidate_answer_id)
        REFERENCES candidate_answers (id) ON DELETE CASCADE,
    CONSTRAINT ai_marking_suggestions_score_non_negative  CHECK (score >= 0)
);
```

### AiMarkingSuggestionRepository

```java
public interface AiMarkingSuggestionRepository extends JpaRepository<AiMarkingSuggestion, UUID> {
    Optional<AiMarkingSuggestion> findByCandidateAnswerId(UUID candidateAnswerId);
}
```

### DTO — AiMarkingSuggestionResponse

```java
public record AiMarkingSuggestionResponse(
        UUID answerId,
        int score,
        int maxScore,
        String rationale,
        Instant generatedAt
) {}
```

### Exception — AiMarkingResponseException

```java
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class AiMarkingResponseException extends RuntimeException {
    public AiMarkingResponseException(String message) { super(message); }
}
```

Picked up automatically by the existing `GlobalExceptionHandler.handleException`
via `@ResponseStatus` reflection — no handler changes needed (same mechanism
already used for the five `Ai*Exception` classes).

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all
valid executions of a system.*

### Property 1: Valid non-MCQ answer with content always produces a stored suggestion

*For any* `TEXT` or `CODE_SUBMISSION` question and any non-null, non-blank
answer text, when `AiService.prompt()` returns a well-formed
`SCORE:`/`RATIONALE:` response, `generateSuggestion()` SHALL persist an
`AiMarkingSuggestion` linked to that answer and return a response whose
`score` and `rationale` match the parsed values (post-clamping).

**Validates: Requirements 1.1, 2.1**

---

### Property 2: MCQ and GROUP questions are always rejected without calling AiService

*For any* question of type `MCQ` or `GROUP`, `generateSuggestion()` SHALL
throw a 400 `ResponseStatusException` and `AiService.prompt()` SHALL never be
invoked.

**Validates: Requirements 1.2**

---

### Property 3: Missing or blank answer content is always rejected without calling AiService

*For any* question of type `TEXT` or `CODE_SUBMISSION` where the
`CandidateAnswer` is absent, or its `textContent` is null or composed
entirely of whitespace, `generateSuggestion()` SHALL throw a 400
`ResponseStatusException` and `AiService.prompt()` SHALL never be invoked.

**Validates: Requirements 1.3**

---

### Property 4: Regenerating a suggestion always replaces the prior one, never duplicates

*For any* sequence of two successful `generateSuggestion()` calls for the
same answer with two different mocked `AiService` responses, after the
second call exactly one `AiMarkingSuggestion` row SHALL exist for that
answer, and its `score`/`rationale` SHALL reflect the second response, not
the first.

**Validates: Requirements 1.4**

---

### Property 5: Score is always clamped to [0, question max score]

*For any* integer parsed from the AI response (including negative values and
values greater than the question's max score), the persisted
`AiMarkingSuggestion.score` SHALL be within `[0, question.maxScore]`, equal
to the nearest boundary when the parsed value falls outside that range, and
equal to the parsed value otherwise.

**Validates: Requirements 2.2, 2.3**

---

### Property 6: Unparseable AI response never persists a suggestion

*For any* AI response text that lacks either a recognizable numeric score or
non-blank rationale in the expected format, `generateSuggestion()` SHALL
throw `AiMarkingResponseException`, and no `AiMarkingSuggestion` row SHALL be
created or modified for that answer as a result of the call.

**Validates: Requirements 2.4**

---

### Property 7: Prompt always contains the question and answer content, and only that content

*For any* question (title, body, max score) and any non-blank answer text,
the string built by `AiMarkingPromptBuilder.build()` SHALL contain the
question title, question body, max score, and answer text as substrings.

**Validates: Requirements 3.1**

---

### Property 8: Language hint is included in the prompt exactly when present

*For any* `CodeSubmissionQuestion`, the built prompt SHALL contain the
language hint as a substring when it is non-null and non-blank, and the
literal language hint value SHALL NOT appear when it is null or blank.

**Validates: Requirements 3.2**

---

### Property 9: Generating a suggestion never creates or modifies an AnswerScore

*For any* sequence of `generateSuggestion()` calls (successful or failing)
for any answer, the set of `AnswerScore` rows in the database (by id, score,
feedback, markedBy, markedAt) SHALL be identical before and after the calls.

**Validates: Requirements 1.1, 4.1, 5.1**

---

### Property 10: Retrieval always returns the most recently generated suggestion

*For any* sequence of N ≥ 1 successful `generateSuggestion()` calls for the
same answer, `getSuggestion()` called afterward SHALL return the score and
rationale from the Nth (most recent) call.

**Validates: Requirements 4.2**

---

### Property 11: Recording an Answer_Score is never influenced by an existing AI suggestion

*For any* `AiMarkingSuggestion` previously generated for an answer (or none
at all) and any recruiter-supplied `(score, feedback)` pair,
`SubmissionService.scoreAnswer()` SHALL persist exactly the supplied score
and feedback, regardless of the suggestion's score or rationale.

**Validates: Requirements 5.2, 5.3**

---

### Property 12: Every AiService exception propagates unchanged and persists nothing

*For any* of the five existing AI exception types
(`AiAuthenticationException`, `AiCommunicationException`,
`AiTimeoutException`, `AiRateLimitException`, `AiResponseException`) thrown
by a mocked `AiService.prompt()`, `generateSuggestion()` SHALL propagate the
exact same exception instance, and no `AiMarkingSuggestion` row SHALL be
created or modified for that answer.

**Validates: Requirements 6.1, 6.2**

---

### Property 13: A failed suggestion generation never blocks manual marking of the same answer

*For any* answer for which `generateSuggestion()` fails (AiService exception
or unparseable response), a subsequent call to
`SubmissionService.scoreAnswer()` for that same answer SHALL succeed and
persist the supplied score.

**Validates: Requirements 6.3**

---

### Property 14: Only RECRUITER and ADMIN roles can invoke the AI marking endpoints

*For any* authenticated request bearing a role other than `RECRUITER` or
`ADMIN` (including `CANDIDATE`), and for any unauthenticated request, both
`POST` and `GET` `/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion`
SHALL respond with HTTP 403 (or 401 if unauthenticated) and SHALL NOT invoke
`AiMarkingService`.

**Validates: Requirements 7.1, 7.2**

---

## Error Handling

| Condition | Exception / Response | HTTP status |
|---|---|---|
| Submission not found | `ResponseStatusException(NOT_FOUND)` | 404 |
| Question not part of submission's assessment | `ResponseStatusException(NOT_FOUND)` | 404 |
| Question type is `MCQ` or `GROUP` | `ResponseStatusException(BAD_REQUEST)` | 400 |
| No answer, or blank answer content | `ResponseStatusException(BAD_REQUEST)` | 400 |
| `AiService` throws `AiAuthenticationException` | propagated unchanged | 502 (existing mapping) |
| `AiService` throws `AiCommunicationException` | propagated unchanged | 502 (existing mapping) |
| `AiService` throws `AiTimeoutException` | propagated unchanged | 504 (existing mapping) |
| `AiService` throws `AiRateLimitException` | propagated unchanged | 503 (existing mapping) |
| `AiService` throws `AiResponseException` | propagated unchanged | 502 (existing mapping) |
| AI response text unparseable (no score/rationale) | `AiMarkingResponseException` | 502 |
| No suggestion exists yet for `getSuggestion()` | `ResponseStatusException(NOT_FOUND)` | 404 |
| Caller lacks `RECRUITER`/`ADMIN` role | Spring Security `AccessDeniedException` (existing handler) | 403 |

All new exception types rely on the existing `GlobalExceptionHandler` —
no changes to that class are required.

---

## Testing Strategy

### Dual approach

Unit tests cover specific examples, edge cases, and wiring (mirroring
`MarkingServiceTest`/`SubmissionServiceTest`). Property-based tests verify the
14 properties above across generated inputs. Both are required.

### PBT library

**jqwik** (already a test-scope dependency, version 1.9.3). Each `@Property`
method runs a minimum of 100 tries. Tag format comment above each method:

```
// Feature: ai-assisted-marking, Property N: <property_text>
```

### Unit tests — AiMarkingServiceImpl

Mock `AiService`, `CandidateAnswerRepository`, `AiMarkingSuggestionRepository`,
`QuestionRepository`, `CandidateSubmissionRepository`,
`AssessmentQuestionRepository` with Mockito.

- Valid TEXT answer + well-formed AI response → suggestion saved and returned
- Valid CODE_SUBMISSION answer with language hint → prompt includes hint
- MCQ question → 400, `AiService` never invoked
- GROUP question → 400, `AiService` never invoked
- No `CandidateAnswer` for question → 400, `AiService` never invoked
- Blank `textContent` → 400, `AiService` never invoked
- Question not in submission's assessment → 404
- Regenerate after existing suggestion → old row updated in place, not duplicated
- AI response missing `SCORE:` → `AiMarkingResponseException`, nothing saved
- AI response missing `RATIONALE:` → `AiMarkingResponseException`, nothing saved
- Parsed score `-5` with maxScore `10` → stored score `0`
- Parsed score `15` with maxScore `10` → stored score `10`
- Each of the 5 `AiService` exception types → propagates unchanged, nothing saved
- `getSuggestion()` with no stored suggestion → 404
- `getSuggestion()` with a stored suggestion → returns it unchanged

### Unit tests — AiMarkingPromptBuilder

- Prompt contains title, body, max score, answer text
- CODE_SUBMISSION with language hint → hint present in prompt
- CODE_SUBMISSION with null language hint → no hint placeholder text leaked
- TEXT question → no language-hint section at all

### Integration tests — AiMarkingControllerIntegrationTest

Using `AbstractIntegrationTest` (TestContainers) conventions already used by
`MarkingIntegrationTest`:

- RECRUITER generates suggestion for a TEXT answer → 200 with suggestion body
- CANDIDATE role → 403
- Unauthenticated → 401
- Generating a suggestion, then calling `scoreAnswer` → 200, `AnswerScore`
  reflects recruiter-supplied values, unaffected by suggestion
- Generating a suggestion does not create any `answer_scores` row
- `GET` suggestion before any generation → 404

### Property-based tests (jqwik)

```java
// Feature: ai-assisted-marking, Property 1: Valid non-MCQ answer with content always produces a stored suggestion
@Property(tries = 100)
void validAnswerProducesStoredSuggestion(@ForAll("nonMcqQuestions") Question q, @ForAll @NotBlank String answerText) { … }

// Feature: ai-assisted-marking, Property 2: MCQ and GROUP questions are always rejected without calling AiService
@Property(tries = 100)
void mcqAndGroupAlwaysRejected(@ForAll("mcqOrGroupQuestions") Question q) { … }

// Feature: ai-assisted-marking, Property 3: Missing or blank answer content is always rejected without calling AiService
@Property(tries = 100)
void blankAnswerAlwaysRejected(@ForAll("nonMcqQuestions") Question q, @ForAll("blankOrNullStrings") String content) { … }

// Feature: ai-assisted-marking, Property 4: Regenerating a suggestion always replaces the prior one, never duplicates
@Property(tries = 100)
void regenerateReplacesNotDuplicates(@ForAll @NotBlank String firstResponse, @ForAll @NotBlank String secondResponse) { … }

// Feature: ai-assisted-marking, Property 5: Score is always clamped to [0, question max score]
@Property(tries = 100)
void scoreAlwaysClamped(@ForAll int rawScore, @ForAll @IntRange(min = 1, max = 100) int maxScore) { … }

// Feature: ai-assisted-marking, Property 6: Unparseable AI response never persists a suggestion
@Property(tries = 100)
void unparseableResponseNeverPersists(@ForAll("malformedAiResponses") String response) { … }

// Feature: ai-assisted-marking, Property 7: Prompt always contains the question and answer content, and only that content
@Property(tries = 100)
void promptContainsExpectedFields(@ForAll @NotBlank String title, @ForAll @NotBlank String body, @ForAll @NotBlank String answerText) { … }

// Feature: ai-assisted-marking, Property 8: Language hint is included in the prompt exactly when present
@Property(tries = 100)
void languageHintIncludedWhenPresent(@ForAll("nullableLanguageHints") String hint) { … }

// Feature: ai-assisted-marking, Property 9: Generating a suggestion never creates or modifies an AnswerScore
@Property(tries = 100)
void generatingSuggestionNeverTouchesAnswerScore(@ForAll @NotBlank String answerText) { … }

// Feature: ai-assisted-marking, Property 10: Retrieval always returns the most recently generated suggestion
@Property(tries = 100)
void retrievalReturnsMostRecent(@ForAll List<@NotBlank String> aiResponses) { … }

// Feature: ai-assisted-marking, Property 11: Recording an Answer_Score is never influenced by an existing AI suggestion
@Property(tries = 100)
void recordingScoreIgnoresSuggestion(@ForAll int suggestionScore, @ForAll @IntRange(min = 0, max = 100) int recruiterScore, @ForAll String recruiterFeedback) { … }

// Feature: ai-assisted-marking, Property 12: Every AiService exception propagates unchanged and persists nothing
@Property(tries = 100)
void aiExceptionsPropagateAndPersistNothing(@ForAll("aiExceptions") RuntimeException ex) { … }

// Feature: ai-assisted-marking, Property 13: A failed suggestion generation never blocks manual marking of the same answer
@Property(tries = 100)
void failedGenerationNeverBlocksManualMarking(@ForAll("generationFailures") Runnable failureSetup) { … }

// Feature: ai-assisted-marking, Property 14: Only RECRUITER and ADMIN roles can invoke the AI marking endpoints
@Property(tries = 100)
void onlyRecruiterAndAdminCanInvoke(@ForAll("nonAllowedRoles") String role) { … }
```

### Integration test scope

Real HTTP calls to Groq are never made in tests. `AiService` is mocked at the
Spring bean level (`@MockBean`) in controller/integration tests, matching the
existing pattern from `ai-integration-foundation`'s testing strategy. No new
TestContainers setup is required beyond the existing PostgreSQL container
already used by `AbstractIntegrationTest`.
