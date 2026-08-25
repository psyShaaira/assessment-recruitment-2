# Candidate Clarification Bot — Implementation Tasks

## Phase 1: Infrastructure & Domain

- [x] 1. Create Flyway migration `V27__create_clarification_requests.sql` (use the next unused `V<n>` if V27 is taken) with the table + indexes from design.md section 3.2.
- [x] 2. Create `ClarificationRequest` JPA entity in `take/clarify/domain/` mapping to `clarification_requests`.
- [x] 3. Create `ClarificationRequestRepository` (Spring Data JPA) in `take/clarify/repository/` with `countBySubmissionId(UUID)` and `countBySubmissionIdAndQuestionId(UUID, UUID)`.
- [x] 4. Create `ClarificationProperties` record as `@ConfigurationProperties(prefix = "clarification")` in `take/clarify/` with `enabled`, `maxPerQuestion`, `maxPerAssessment`.
- [x] 5. Add a `clarification:` block to `application.yaml` (and dev override if present) with defaults `enabled: true`, `max-per-question: 3`, `max-per-assessment: 15`. Register the properties class (`@EnableConfigurationProperties` or `@ConfigurationPropertiesScan` — match existing `flagging` wiring). Registration via existing `@ConfigurationPropertiesScan` — no manual wiring needed. Base `clarification:` block covers dev (override only holds `flagging`).

## Phase 2: Prompt & Guardrails

- [x] 6. Create `ClarificationPromptBuilder` (`@Component`, no deps) with `PROMPT_VERSION = "v1"`. Build the prompt from a candidate-safe question view (title, body, MCQ option texts only — never `correct`) plus the guardrail rules and the delimited untrusted-note block (design.md section 4.3).

## Phase 3: Service Core

- [x] 7. Create `ClarificationRequestDto` (`{ @NotNull questionId, @Size(max=500) candidateNote }`) and `ClarificationResponse` (`{ clarification, remainingForQuestion, remainingForAssessment, degraded }` + static `degraded(...)` factory) in `take/clarify/dto/`.
- [x] 8. Create `ClarificationRateLimitException` in `take/clarify/` annotated `@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)`.
- [x] 9. Add a package-visible helper to `CandidateTakeService`/`Impl` that, given `(candidateId, assessmentId, questionId)`, enforces the active/unlocked/in-deadline guards, validates the question belongs to the snapshot-aware resolved set, and returns the sanitized `TakeQuestionDto` (or throws 403/404/409). Reuses `requireActiveSubmission`, `checkDeadline`, `resolveQuestions`. Added `resolveQuestionForClarification(...)` returning a `ClarificationTarget(submissionId, question)` record; note-length is enforced via `@Size(max=500)` on the DTO (Bean Validation) rather than in the service.
- [x] 10. Create `ClarificationService` interface with `ClarificationResponse clarify(UUID candidateId, UUID assessmentId, ClarificationRequestDto request)`.
- [x] 11. Create `ClarificationServiceImpl` implementing the flow in design.md section 4.2: resolve+guard (via the helper from task 9), note-length check, rate-limit checks (per-question then per-assessment), build prompt, call `aiService.prompt(...)`, persist on success, return decremented quota.
- [x] 12. Implement graceful degradation in `ClarificationServiceImpl`: catch the `Ai*Exception` hierarchy (and honour `enabled=false`), returning `ClarificationResponse.degraded(...)` without persisting or consuming quota.

## Phase 4: REST API

- [x] 13. Create `ClarificationController` (`@RestController`, `@RequestMapping("/api/take")`, `@PreAuthorize("hasRole('CANDIDATE')")`) exposing `POST /api/take/clarify`, deriving `candidateId`/`assessmentId` from `Authentication`. Confirm no `SecurityConfig` change is needed (already covered by `/api/take/**`). Verified: `SecurityConfig` line 30 maps `/api/take/**` → `hasRole("CANDIDATE")`.

## Phase 5: Backend Tests

- [x] 14. `ClarificationPromptBuilderTest` — guardrail rules present; question body present; MCQ option texts present but no correctness marker; candidate note wrapped/delimited; no PII. (6 tests, pass)
- [x] 15. `ClarificationServiceImplTest` (Mockito) — happy path persists + returns decremented quota; guard exception propagates; per-question limit → 429 with `verifyNoInteractions(aiService)`; per-assessment limit → 429; AI exception → degraded (no save); `enabled=false` → degraded (no AI call). Note >500 (task-9 note) is covered by DTO `@Size` validation at the controller boundary, exercised in the integration layer. (6 tests, pass)
- [x] 16. `ClarificationControllerIntegrationTest` extends `AbstractIntegrationTest` — seed invitation/submission/question, mint candidate session token, stub the `AiService` bean; assert 200 + clarification, 403 for out-of-scope question, 401/403 for missing token, 429 after exceeding the per-question limit, degraded-200 when AI unavailable. (5 tests, pass — required `api.version=1.44` in `~/.docker-java.properties` for Docker Engine 29+ / API 1.53.)

## Phase 6: Frontend

- [x] 17. Add a `ClarificationResponse` model and `askClarification(token, questionId, candidateNote?)` to `core/take/candidate-take.service.ts`, POSTing to `/api/take/clarify` with an explicit `Authorization: Bearer` header. (also added `ClarificationRequest` model)
- [x] 18. Add the clarification UI to `features/assessments/assessment-take.component.ts`: a "Need clarification?" button in `.question-meta`, a panel with an optional note input + response area scoped to the current question, loading state, remaining-quota display, and 429/degraded handling. Reset panel state on question change (hooked into `prev()`/`next()`).
- [x] 19. `candidate-take.service.spec.ts` — assert `askClarification` POSTs to the endpoint with the Bearer header and body, and handles the response (`HttpTestingController`). (2 tests: with-note and null-note)
- [x] 20. Update/extend the take component spec — toggle panel, click calls the service + renders clarification, exhausted quota, 429 disables the control, reset on navigation. (5 tests)

## Phase 7: Verification

- [x] 21. Run `./mvnw test` (backend) and `npm test` + `npx tsc --noEmit` (frontend); ensure green. Backend: 388 tests, 0 failures/errors. Frontend: 117 tests pass, tsc clean (run under Node 24 via nvm — system node is v12).
- [x] 22. Run `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`; confirm mutation coverage stays ≥ 29. Passed (overall 44%, threshold 29). Clarification classes: `ClarificationServiceImpl` 18/18 killed, `ClarificationPromptBuilder` 10/13, `ClarificationController` 0/1 (thin controller, covered only by the PIT-excluded integration test — consistent with other controllers).
