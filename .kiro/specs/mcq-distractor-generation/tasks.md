# MCQ Distractor Generation — Implementation Tasks

## Phase 1: Shared Validation Helper (refactor)

- [x] 1. Create `ai/McqOptionValidation.java` (package-visible utility) exposing `normalizeOptionText(String)`, `isDuplicateOrWordPrefix(List<String>, List<String>)`, and `assertNoNearDuplicates(List<String> texts)` (throws `AiGenerationValidationException`). Move the logic verbatim from `QuestionGenerationServiceImpl`.
- [x] 2. Refactor `QuestionGenerationServiceImpl.checkForNearDuplicateOptions` to delegate to `McqOptionValidation` (behaviour unchanged).
- [x] 3. Run existing `QuestionGenerationServiceImplTest` + golden-dataset test — confirm still green after the extraction.

## Phase 2: Backend Service

- [x] 4. Create `question/dto/GenerateDistractorsRequest.java` (`@NotBlank questionBody`, `@NotBlank correctAnswer`, `@Min(1) @Max(5) int count`).
- [x] 5. Create `ai/DistractorGenerationService.java` interface: `List<QuestionOptionRequest> generateDistractors(GenerateDistractorsRequest request)`.
- [x] 6. Create `ai/DistractorGenerationServiceImpl.java`: build guarded prompt (stem + correctAnswer + count, "must be wrong/plausible/distinct", JSON `{"distractors":[...]}`), `aiService.prompt(...)`, parse + validate (count, non-blank, not equal/near-dup of correctAnswer, not near-dup of each other via `McqOptionValidation`), one retry with corrective feedback, return options all `correct=false`.

## Phase 3: REST API

- [x] 7. Add `POST /api/questions/generate-distractors` to `QuestionController`, injecting `DistractorGenerationService`. Confirm no `SecurityConfig` change (inherits `hasAnyRole('ADMIN','RECRUITER')`).

## Phase 4: Backend Tests

- [x] 8. `DistractorGenerationServiceImplTest` (Mockito) — happy path (all `correct=false`); distractor equals correct answer → retry → success; near-duplicate distractors → retry; wrong count → retry, >count → trimmed; blank text → failure; malformed JSON → retry → propagate; verify `prompt` called twice on first-attempt validation failure.
- [x] 9. `McqOptionValidationTest` — `assertNoNearDuplicates` flags word-prefix duplicates, passes distinct sets (guards the refactor).
- [x] 10. Extend `QuestionControllerIntegrationTest` (or new `DistractorGenerationControllerIntegrationTest`) — stub `AiService` bean; 200 with N `correct=false` options; 401/403 without RECRUITER/ADMIN; 400 on blank body / blank correctAnswer / bad count.

## Phase 5: Frontend

- [x] 11. Add `GenerateDistractorsRequest` to `core/ai/ai.model.ts` and `generateDistractors(params)` to `core/ai/ai.service.ts` (POST `/api/questions/generate-distractors`, returns `QuestionOptionRequest[]`).
- [x] 12. Add distractor UI to `features/questions/question-form.component.ts` MCQ section: `distractorCount` (default 3) + `generatingDistractors` signals, a "Suggest distractors with AI" button + count selector, and `suggestDistractors()` that sends `body` + the marked-correct option text, **appends** returned rows as `correct:false` (existing rows untouched), shows loading, and toasts on error via `friendlyAiError`. Block when body or correct answer is missing.

## Phase 6: Frontend Tests

- [x] 13. `ai.service.spec.ts` — `generateDistractors` POSTs to `/api/questions/generate-distractors` with the request body (`HttpTestingController`).
- [x] 14. `question-form.component.spec.ts` — `suggestDistractors()` appends returned options without modifying existing ones; blocked when no correct answer/body; error path leaves options intact.

## Phase 7: Verification

- [x] 15. Run `./mvnw test` (backend) and `npm test` + `npx tsc --noEmit` (frontend); ensure green.
- [x] 16. Run `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`; confirm mutation coverage stays ≥ 29.
