# AI Auto-Flagging — Implementation Tasks

## Phase 1: Infrastructure & Domain

- [x] 1. Create Flyway migration `V26__create_flagging_risk_assessments.sql` with the schema from design.md (table, unique constraint on `submission_id`, partial index on `risk`).
- [x] 2. Create `RiskLevel` enum (`HIGH`, `MEDIUM`, `LOW`) in `flag/domain/`.
- [x] 3. Create `FlaggingRiskAssessment` JPA entity in `flag/domain/` mapping to `flagging_risk_assessments` table.
- [x] 4. Create `FlaggingRiskAssessmentRepository` (Spring Data JPA) in `flag/repository/` with `findBySubmissionId(UUID)` and `findByRiskIn(List<RiskLevel>)`.
- [x] 5. Create `AiFlaggingProperties` record as `@ConfigurationProperties(prefix = "flagging")` in `flag/ai/` with fields: `aiEnabled`, `highThreshold`, `mediumThreshold`, `similarityThreshold`, `timeoutSeconds`.
- [x] 6. Add `flagging:` configuration block to `application.yaml` and `application-dev.yaml` with default values from design.md.
- [x] 7. Create `SubmissionCompletedEvent` record in `take/` package (`submissionId`, `assessmentId`).
- [x] 8. Publish `SubmissionCompletedEvent` from `CandidateTakeServiceImpl.submitAssessment()` after `scoreUnansweredQuestions()` using `ApplicationEventPublisher`.

## Phase 2: AI Analysis Core

- [x] 9. Create `SubmissionAnalysisContext` and `AnswerContext` records in `flag/ai/`.
- [x] 10. Create `AiFlaggingResult` record in `flag/ai/dto/` (`RiskLevel risk`, `List<FlagReason> reasons`, `String rationale`, `double confidence`) with a static `LOW_DEFAULT` constant.
- [x] 11. Create `AiFlaggingPromptBuilder` (`@Component`, no deps) — builds prompt from `SubmissionAnalysisContext` following the structure in design.md section 4.4. Include `PROMPT_VERSION = "v1"` constant.
- [x] 12. Create `AiFlaggingService` interface in `flag/ai/` with `analyze(UUID submissionId)` and `Optional<RiskAssessmentResponse> getRiskAssessment(UUID submissionId)`.
- [x] 13. Create `AiFlaggingServiceImpl` — implements context building (load submission, answers, assessment, questions), calls prompt builder → `aiService.promptForJson()`, parses response, persists `FlaggingRiskAssessment`, and auto-creates flag via `SubmissionFlagService.createFlag()` for HIGH risk. Use `SYSTEM_ACTOR_ID` (zeroed UUID) and `"SYSTEM"` username.
- [x] 14. Implement JSON response parsing in `AiFlaggingServiceImpl` — extract `risk`, `reasons`, `rationale`, `confidence` from Groq response, validate against thresholds from properties, and handle malformed responses gracefully (default to LOW).

## Phase 3: Similarity Check

- [x] 15. Create `SimilarityResult` record in `flag/ai/` (`RiskLevel risk`, `FlagReason reason`, `double maxSimilarity`, `String rationale`).
- [x] 16. Create `SimilarityCheckService` interface in `flag/ai/` with `SimilarityResult check(SubmissionAnalysisContext context)`.
- [x] 17. Implement `SimilarityCheckServiceImpl` — query other SUBMITTED/AUTO_SUBMITTED submissions for same assessment, compute Jaccard word-set similarity on TEXT/CODE answers, return HIGH + `COPIED_ANSWERS` if any pair exceeds threshold.
- [x] 18. Implement `normalize()` and `jaccardSimilarity()` helper methods — lowercase, strip punctuation, split on whitespace, filter common stopwords, compute intersection/union ratio.
- [x] 19. Wire `SimilarityCheckService` into `AiFlaggingServiceImpl.analyze()` — run after AI analysis, merge results (take highest risk level).

## Phase 4: Event Listener & Async

- [x] 20. Create `AiFlaggingListener` (`@Component`) with `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` method listening for `SubmissionCompletedEvent`. Gate on `properties.isAiEnabled()`.
- [x] 21. Configure async executor in `application.yaml` — `spring.task.execution.pool` with core-size 2, max-size 4, queue-capacity 50, thread-name-prefix `ai-flagging-`.
- [x] 22. Add `@EnableAsync` to the application config (or verify it's already present).

## Phase 5: REST API

- [x] 23. Create `RiskAssessmentResponse` DTO in `flag/ai/dto/` — maps from `FlaggingRiskAssessment` entity.
- [x] 24. Add `GET /api/submissions/{submissionId}/risk-assessment` endpoint to `SubmissionFlagController` (or a new `AiFlaggingController`). Secure with `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`. Return 404 if no assessment exists.
- [x] 25. Add optional `aiRiskLevel` field to the submission list/detail DTO (only populated for MEDIUM/HIGH).

## Phase 6: Unit Tests

- [x] 26. `AiFlaggingPromptBuilderTest` — verify prompt contains assessment timing, per-answer content, JSON schema instruction, and respects PII isolation (no candidate names).
- [x] 27. `AiFlaggingServiceImplTest` — mock `AiService`, verify: HIGH risk → flag created, MEDIUM → no flag, LOW → no flag, AI failure → graceful degradation, open-flag skip, response parsing edge cases.
- [x] 28. `SimilarityCheckServiceImplTest` — verify Jaccard calculation, threshold boundaries (0.79 → no flag, 0.80 → flag), empty text handling, no other submissions → LOW.
- [x] 29. `AiFlaggingListenerTest` — verify disabled property → no call, enabled → delegates to service, async behavior.

## Phase 7: Integration Test

- [x] 30. `AiFlaggingControllerIntegrationTest` — extend `AbstractIntegrationTest`, seed a submission + risk assessment, verify GET returns correct response, verify 404 for missing assessment, verify 403 for unauthenticated/candidate access.

## Phase 8: Frontend (Minimal)

- [x] 31. Add `aiRiskLevel` field to the frontend submission/result model in `core/flag/` or `core/marking/`.
- [x] 32. Display AI risk badge (MEDIUM = yellow, HIGH = red) on submission list and detail views where `aiRiskLevel` is present.
- [x] 33. Add "AI Flagged" indicator to the flag detail view when `createdBy` is the system actor username.
