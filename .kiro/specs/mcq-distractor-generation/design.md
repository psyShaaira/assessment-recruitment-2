# MCQ Distractor Generation — Software Design Document

## 1. Architecture Overview

Synchronous request/response, mirroring the existing full-question generation flow. No persistence — the endpoint returns transient option drafts the recruiter reviews and saves via the normal create/update path.

```
Question form (question-form.component)
      │  POST /api/questions/generate-distractors { questionBody, correctAnswer, count }
      ▼
QuestionController (@PreAuthorize ADMIN/RECRUITER — inherited on /api/questions)
      ▼
DistractorGenerationService
  1. Build guarded prompt (stem + correctAnswer + count, "must be wrong")
  2. aiService.prompt(prompt)
  3. Parse JSON → list of distractor texts
  4. Validate: non-blank, count, not equal/near-dup of correctAnswer, not near-dup of each other
     └─ on validation failure: retry once with corrective feedback, then throw
  5. Return List<QuestionOptionRequest> all with correct=false
      ▼
Frontend appends returned rows to the MCQ options FormArray (existing rows untouched)
```

### Design Decisions

| Decision | Rationale |
|----------|-----------|
| New `ai` package service, not folded into `QuestionGenerationServiceImpl` | Keeps single-responsibility; distractor gen has its own prompt + validation. Shares helpers via a small extraction (see §4.3). |
| `aiService.prompt` (not `promptForJson`) | Matches the existing generation path, which uses `prompt` and parses JSON with the schema pinned in the prompt text. Keeps behaviour consistent. |
| No schema / no persistence | Same as existing generation — drafts are transient. Nothing to store. |
| Reuse near-duplicate heuristic | `checkForNearDuplicateOptions` already exists in `QuestionGenerationServiceImpl`; extract to a shared helper so both features use it (FR-2.2/2.3). |
| Append on the frontend | Preserves the recruiter's own correct answer and any distractors already typed (FR-3.3). |
| Correct answer sent from the marked-correct option | The form already tracks which option is correct; its text becomes `correctAnswer`. No new input field needed. |

---

## 2. Backend — Package Structure

```
com.psybergate.recruitment.ai/
├── DistractorGenerationService.java       (NEW — interface)
├── DistractorGenerationServiceImpl.java   (NEW — prompt build + parse + validate + retry)
├── QuestionGenerationServiceImpl.java     (existing — extract shared near-dup helper)
├── McqOptionValidation.java               (NEW — extracted shared near-duplicate/normalize helpers)
└── AiGenerationValidationException.java   (existing — reused for validation failures + retry)

com.psybergate.recruitment.question/
├── QuestionController.java                (existing — add POST /generate-distractors)
└── dto/
    └── GenerateDistractorsRequest.java    (NEW — { questionBody, correctAnswer, count })
```

The response type reuses the existing `QuestionOptionRequest` (`{ text, correct }`), all with `correct=false`.

---

## 3. REST API

### 3.1 Request DTO

```java
public record GenerateDistractorsRequest(
        @NotBlank String questionBody,
        @NotBlank String correctAnswer,
        @Min(1) @Max(5) int count
) {}
```

### 3.2 Controller method (added to existing `QuestionController`)

```java
@PostMapping("/generate-distractors")
public ResponseEntity<List<QuestionOptionRequest>> generateDistractors(
        @RequestBody @Valid GenerateDistractorsRequest request) {
    return ResponseEntity.ok(distractorGenerationService.generateDistractors(request));
}
```

Route inherits `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` from the class annotation — no `SecurityConfig` change.

---

## 4. Service Logic

### 4.1 Interface

```java
public interface DistractorGenerationService {
    List<QuestionOptionRequest> generateDistractors(GenerateDistractorsRequest request);
}
```

### 4.2 Impl flow (`DistractorGenerationServiceImpl`)

Mirrors `QuestionGenerationServiceImpl.generateOne` — one retry with corrective feedback:

```java
public List<QuestionOptionRequest> generateDistractors(GenerateDistractorsRequest req) {
    try {
        String raw = aiService.prompt(buildPrompt(req, null));
        return parseAndValidate(raw, req);
    } catch (AiGenerationValidationException firstFailure) {
        log.warn("Distractor generation failed validation, retrying once: {}", firstFailure.getMessage());
        String retry = aiService.prompt(buildPrompt(req, firstFailure.getMessage()));
        return parseAndValidate(retry, req);
    }
}
```

**Prompt** (hand-built `StringBuilder`, matching convention):
- Role: "You are generating plausible but INCORRECT answer options (distractors) for a multiple-choice question."
- Hard rules:
  - Every option MUST be factually wrong for the question.
  - Options MUST be plausible/tempting to someone who doesn't know the answer.
  - Do NOT restate or paraphrase the correct answer.
  - Options must be distinct from each other.
- Schema: `Respond with ONLY a JSON object: {"distractors": [string, ...]}` (no markdown fences).
- Corrective-feedback clause appended on retry (same pattern as existing gen).
- Inputs: question stem, the correct answer, and the requested count.

**parseAndValidate**:
1. Parse JSON; `distractors` must be an array of the requested `count` non-blank strings (tolerate ±: if more returned, take first `count`; if fewer, validation failure → retry).
2. Each distractor must not equal / near-duplicate `correctAnswer` (shared helper).
3. Distractors must not near-duplicate each other (shared helper).
4. Build `List<QuestionOptionRequest>` with `correct = false` for every entry.
5. Any failure throws `AiGenerationValidationException` (triggers the single retry, then propagates → 422 via existing handler).

### 4.3 Shared helper extraction (`McqOptionValidation`)

Extract the existing private methods from `QuestionGenerationServiceImpl` into a package-visible utility so both services share one implementation (avoids divergence):
- `normalizeOptionText(String)`
- `isDuplicateOrWordPrefix(List<String>, List<String>)`
- `assertNoNearDuplicates(List<String> texts)` — throws `AiGenerationValidationException`

`QuestionGenerationServiceImpl.checkForNearDuplicateOptions` is refactored to call the shared helper (behaviour unchanged; existing tests must stay green). The distractor service also uses it, plus an extra check comparing each distractor against `correctAnswer`.

---

## 5. Frontend Design

**`core/ai/ai.model.ts`** — add:
```ts
export interface GenerateDistractorsRequest {
  questionBody: string;
  correctAnswer: string;
  count: number;
}
```

**`core/ai/ai.service.ts`** — add:
```ts
generateDistractors(params: GenerateDistractorsRequest): Observable<QuestionOptionRequest[]> {
  return this.http.post<QuestionOptionRequest[]>('/api/questions/generate-distractors', params);
}
```
(`QuestionOptionRequest`/option shape is `{ text, correct }`, already in the question model.)

**`features/questions/question-form.component.ts`** (MCQ section) —
- New signals: `distractorCount` (default 3), `generatingDistractors`.
- New method `suggestDistractors()`:
  - Read `body` and the currently-correct option's text. If either missing/blank → toast "Add the question and mark the correct answer first" and return.
  - Set `generatingDistractors(true)`, call `aiSvc.generateDistractors({ questionBody, correctAnswer, count })`.
  - On success: for each returned option, `this.options.push(this.makeOption(o.text, false))` (append; existing rows untouched). Toast success.
  - On error: reuse `friendlyAiError`, toast; leave options untouched.
  - Finally clear loading.
- Template: a "Suggest distractors with AI" button + count selector inside the MCQ options block, disabled while generating or when no correct answer/body is set.

---

## 6. Testing Strategy

### Backend (unit) — `DistractorGenerationServiceImplTest` (Mockito, mock `AiService`)
- Happy path: valid JSON → N distractors, all `correct=false`.
- Distractor equal to correct answer → validation failure → retry → success on 2nd attempt.
- Near-duplicate distractors → validation failure → retry.
- Wrong count returned → failure/retry; >count returned → trimmed to count.
- Blank distractor text → failure.
- Malformed JSON → failure → retry → propagates if still bad.
- Verify `aiService.prompt` called twice on a first-attempt validation failure.

### Backend — shared helper `McqOptionValidationTest`
- `assertNoNearDuplicates` flags word-prefix duplicates; passes distinct sets. (Guards the refactor.)

### Backend (regression)
- Existing `QuestionGenerationServiceImplTest` + golden-dataset test MUST stay green after the helper extraction.

### Backend (integration) — extend `QuestionControllerIntegrationTest` (or new)
- `POST /api/questions/generate-distractors` with `AiService` stubbed → 200 with N `correct=false` options.
- 401/403 without RECRUITER/ADMIN.
- 400 on blank body / blank correctAnswer / count out of range.

### Frontend
- `ai.service.spec.ts` — `generateDistractors` POSTs to the endpoint with the body (`HttpTestingController`).
- `question-form.component.spec.ts` — `suggestDistractors()` appends returned rows without touching existing options; blocked when no correct answer; error path leaves options intact.

### PIT
- `DistractorGenerationServiceImpl` + `McqOptionValidation` in scope; keep mutation coverage ≥ 29. DTOs and integration tests excluded per existing config.

## 7. Out of Scope

- Persistence/audit of requests. Non-MCQ types. Generating the correct answer. Embeddings-based similarity (keep the existing word-prefix heuristic; upgrade later if the golden-dataset suite shows false negatives).
