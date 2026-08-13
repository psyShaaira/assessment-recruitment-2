# Implementation Plan: AI-Assisted Marking

## Overview

Build the `AiMarkingSuggestion` entity + migration, `AiMarkingSuggestionRepository`, the `com.psybergate.recruitment.marking.ai` package (`AiMarkingPromptBuilder`, `AiMarkingService`/`AiMarkingServiceImpl`, `AiMarkingResponseException`, `AiMarkingController`), and its DTOs — layering on top of the existing `AiService` (from `ai-integration-foundation`) and the existing `marking` package without modifying either.

---

## Tasks

- [x] 1. Add schema and domain entity
  - [x] 1.1 Create Flyway migration V24__create_ai_marking_suggestions.sql
    - Create `recruitment-be/src/main/resources/db/migration/V24__create_ai_marking_suggestions.sql`:
      ```sql
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
    - _Requirements: 2.1, 4.1_
  - [x] 1.2 Create AiMarkingSuggestion entity
    - Create `com.psybergate.recruitment.domain.AiMarkingSuggestion`:
      - `@Entity @Table(name = "ai_marking_suggestions") @Getter @Setter @NoArgsConstructor`
      - Fields: `UUID id` (`@GeneratedValue(strategy = GenerationType.UUID)`), `UUID candidateAnswerId` (`@Column(name = "candidate_answer_id", nullable = false, unique = true)`), `int score`, `String rationale` (`@Column(columnDefinition = "TEXT", nullable = false)`), `Instant generatedAt` (`@Column(name = "generated_at", nullable = false)`)
    - _Requirements: 2.1, 4.1, 4.4_
  - [x] 1.3 Create AiMarkingSuggestionRepository
    - Create `com.psybergate.recruitment.repository.AiMarkingSuggestionRepository extends JpaRepository<AiMarkingSuggestion, UUID>`:
      - `Optional<AiMarkingSuggestion> findByCandidateAnswerId(UUID candidateAnswerId)`
    - _Requirements: 4.2, 4.3_

- [x] 2. Create DTOs and exception class
  - [x] 2.1 Create AiMarkingSuggestionResponse DTO
    - Create `com.psybergate.recruitment.marking.ai.dto.AiMarkingSuggestionResponse`:
      ```java
      public record AiMarkingSuggestionResponse(
              UUID answerId, int score, int maxScore, String rationale, Instant generatedAt) {}
      ```
    - _Requirements: 2.1_
  - [x] 2.2 Create AiMarkingResponseException
    - Create `com.psybergate.recruitment.marking.ai.AiMarkingResponseException`:
      - `@ResponseStatus(HttpStatus.BAD_GATEWAY)`, extends `RuntimeException`, single `String message` constructor forwarding to `super(message)`
    - _Requirements: 2.4_

- [x] 3. Implement AiMarkingPromptBuilder
  - [x] 3.1 Implement prompt builder component
    - Create `com.psybergate.recruitment.marking.ai.AiMarkingPromptBuilder`:
      - `@Component`, single public method `String build(Question question, CandidateAnswer answer)`
      - Includes: question title, question body, question max score, answer `textContent`
      - If `question instanceof CodeSubmissionQuestion csq` and `csq.getLanguageHint()` is non-null/non-blank, include the language hint in the prompt
      - Instructs the AI to reply strictly in the format:
        ```
        SCORE: <integer>
        RATIONALE: <text>
        ```
      - Only reads from the `question` and `answer` parameters — no repository access inside the builder
    - _Requirements: 3.1, 3.2, 3.3_
  - [ ]* 3.2 Write unit tests for AiMarkingPromptBuilder
    - Create `AiMarkingPromptBuilderTest`
    - Cover: prompt contains title, body, max score, answer text; CODE_SUBMISSION with language hint → hint present; CODE_SUBMISSION with null language hint → no hint placeholder leaked; TEXT question → no language-hint section
    - _Requirements: 3.1, 3.2_
  - [ ]* 3.3 Write property test: prompt always contains question and answer content (Property 7)
    - // Feature: ai-assisted-marking, Property 7: Prompt always contains the question and answer content, and only that content
    - For any question title/body/maxScore and any non-blank answer text, the built prompt contains all as substrings
    - **Property 7: Prompt always contains the question and answer content, and only that content**
    - **Validates: Requirements 3.1**
  - [ ]* 3.4 Write property test: language hint included exactly when present (Property 8)
    - // Feature: ai-assisted-marking, Property 8: Language hint is included in the prompt exactly when present
    - For any CodeSubmissionQuestion, the built prompt contains the hint iff it is non-null/non-blank
    - **Property 8: Language hint is included in the prompt exactly when present**
    - **Validates: Requirements 3.2**

- [x] 4. Implement AiMarkingService interface and AiMarkingServiceImpl
  - [x] 4.1 Create AiMarkingService interface
    - Create `com.psybergate.recruitment.marking.ai.AiMarkingService`:
      ```java
      public interface AiMarkingService {
          AiMarkingSuggestionResponse generateSuggestion(UUID submissionId, UUID questionId);
          AiMarkingSuggestionResponse getSuggestion(UUID submissionId, UUID questionId);
      }
      ```
    - _Requirements: 1.1, 4.2_
  - [x] 4.2 Implement AiMarkingServiceImpl.generateSuggestion
    - Create `com.psybergate.recruitment.marking.ai.AiMarkingServiceImpl`:
      - `@Service @Transactional @RequiredArgsConstructor`
      - Inject `AiService`, `AiMarkingPromptBuilder`, `AiMarkingSuggestionRepository`, `CandidateAnswerRepository`, `CandidateSubmissionRepository`, `AssessmentQuestionRepository`, `QuestionRepository` (reuse existing repositories; do not modify them)
      - `generateSuggestion(submissionId, questionId)`:
        1. Load `CandidateSubmission` by id, else `ResponseStatusException(NOT_FOUND, "Submission not found")`
        2. Validate `questionId` belongs to the submission's assessment (reuse the top-level/GROUP-member lookup pattern from `SubmissionServiceImpl.scoreByQuestionId`), else `ResponseStatusException(NOT_FOUND, "Question not found in this assessment")`
        3. Load the `Question` (via `Hibernate.unproxy`); if `getType()` is `MCQ` or `GROUP` → `ResponseStatusException(BAD_REQUEST, "Question type is not eligible for AI-assisted marking")` — do not call `AiService`
        4. Load `CandidateAnswer` via `findBySubmissionIdAndQuestionId`; if absent or `textContent` is null/blank → `ResponseStatusException(BAD_REQUEST, "No answer content to evaluate")` — do not call `AiService`
        5. Build prompt via `AiMarkingPromptBuilder.build(question, answer)`
        6. Call `aiService.prompt(promptText)` — do not catch any exception it throws
        7. Parse `SCORE:` and `RATIONALE:` from the response text; if either cannot be parsed → throw `AiMarkingResponseException` — do not persist anything
        8. Clamp parsed score to `[0, question.getMaxScore()]`
        9. Upsert `AiMarkingSuggestion` by `candidateAnswerId` (update in place if present, else create new), set `score`, `rationale`, `generatedAt = Instant.now()`, save
        10. Map and return `AiMarkingSuggestionResponse`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 4.1, 5.1, 6.1_
  - [x] 4.3 Implement AiMarkingServiceImpl.getSuggestion
    - `getSuggestion(submissionId, questionId)`:
      1. Same submission + question-in-assessment validation as steps 1–2 above (404 on mismatch)
      2. Load `CandidateAnswer` via `findBySubmissionIdAndQuestionId`; if absent → `ResponseStatusException(NOT_FOUND, "No suggestion exists for this answer")`
      3. Load `AiMarkingSuggestion` via `findByCandidateAnswerId`; if absent → `ResponseStatusException(NOT_FOUND, "No suggestion exists for this answer")`
      4. Map and return `AiMarkingSuggestionResponse`
    - _Requirements: 1.5, 4.2, 4.3_
  - [ ]* 4.4 Write unit tests for AiMarkingServiceImpl
    - Create `AiMarkingServiceImplTest` — mock all injected repositories/services with Mockito
    - Cover: valid TEXT answer + well-formed AI response → suggestion saved and returned; valid CODE_SUBMISSION with language hint → prompt includes hint; MCQ question → 400, `AiService` never invoked; GROUP question → 400, `AiService` never invoked; no `CandidateAnswer` → 400, `AiService` never invoked; blank `textContent` → 400, `AiService` never invoked; question not in submission's assessment → 404; regenerate after existing suggestion → row updated in place, not duplicated; missing `SCORE:` in response → `AiMarkingResponseException`, nothing saved; missing `RATIONALE:` → `AiMarkingResponseException`, nothing saved; parsed score `-5` with maxScore `10` → stored `0`; parsed score `15` with maxScore `10` → stored `10`; each of the 5 `AiService` exception types → propagates unchanged, nothing saved; `getSuggestion()` with no stored suggestion → 404; `getSuggestion()` with stored suggestion → returned unchanged
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 4.2, 4.3, 6.1_
  - [ ]* 4.5 Write property test: valid answer always produces stored suggestion (Property 1)
    - // Feature: ai-assisted-marking, Property 1: Valid non-MCQ answer with content always produces a stored suggestion
    - For any TEXT/CODE_SUBMISSION question and non-blank answer text, when `AiService.prompt()` returns a well-formed response, `generateSuggestion()` persists and returns a matching suggestion
    - **Property 1: Valid non-MCQ answer with content always produces a stored suggestion**
    - **Validates: Requirements 1.1, 2.1**
  - [ ]* 4.6 Write property test: MCQ/GROUP always rejected without calling AiService (Property 2)
    - // Feature: ai-assisted-marking, Property 2: MCQ and GROUP questions are always rejected without calling AiService
    - **Property 2: MCQ and GROUP questions are always rejected without calling AiService**
    - **Validates: Requirements 1.2**
  - [ ]* 4.7 Write property test: missing/blank answer always rejected without calling AiService (Property 3)
    - // Feature: ai-assisted-marking, Property 3: Missing or blank answer content is always rejected without calling AiService
    - **Property 3: Missing or blank answer content is always rejected without calling AiService**
    - **Validates: Requirements 1.3**
  - [ ]* 4.8 Write property test: regenerate replaces not duplicates (Property 4)
    - // Feature: ai-assisted-marking, Property 4: Regenerating a suggestion always replaces the prior one, never duplicates
    - For any two sequential successful calls with different mocked responses, exactly one row exists afterward reflecting the second response
    - **Property 4: Regenerating a suggestion always replaces the prior one, never duplicates**
    - **Validates: Requirements 1.4**
  - [ ]* 4.9 Write property test: score always clamped to [0, maxScore] (Property 5)
    - // Feature: ai-assisted-marking, Property 5: Score is always clamped to [0, question max score]
    - For any raw parsed integer score and any maxScore, stored score is within [0, maxScore]
    - **Property 5: Score is always clamped to [0, question max score]**
    - **Validates: Requirements 2.2, 2.3**
  - [ ]* 4.10 Write property test: unparseable response never persists (Property 6)
    - // Feature: ai-assisted-marking, Property 6: Unparseable AI response never persists a suggestion
    - For any AI response text lacking a recognizable score or rationale, throws `AiMarkingResponseException` and persists nothing
    - **Property 6: Unparseable AI response never persists a suggestion**
    - **Validates: Requirements 2.4**
  - [ ]* 4.11 Write property test: generating suggestion never touches AnswerScore (Property 9)
    - // Feature: ai-assisted-marking, Property 9: Generating a suggestion never creates or modifies an AnswerScore
    - For any sequence of generate calls (successful or failing), the set of `AnswerScore` rows is unchanged before/after
    - **Property 9: Generating a suggestion never creates or modifies an AnswerScore**
    - **Validates: Requirements 1.1, 4.1, 5.1**
  - [ ]* 4.12 Write property test: retrieval returns most recent suggestion (Property 10)
    - // Feature: ai-assisted-marking, Property 10: Retrieval always returns the most recently generated suggestion
    - For any sequence of N successful generate calls for the same answer, `getSuggestion()` afterward returns the Nth result
    - **Property 10: Retrieval always returns the most recently generated suggestion**
    - **Validates: Requirements 4.2**
  - [ ]* 4.13 Write property test: every AiService exception propagates and persists nothing (Property 12)
    - // Feature: ai-assisted-marking, Property 12: Every AiService exception propagates unchanged and persists nothing
    - For any of the 5 AiService exception types thrown by a mocked `AiService.prompt()`, `generateSuggestion()` propagates the same instance and persists nothing
    - **Property 12: Every AiService exception propagates unchanged and persists nothing**
    - **Validates: Requirements 6.1, 6.2**

- [ ] 5. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Verify non-interference with manual marking (no changes to existing marking code)
  - [x]* 6.1 Write property test: recording Answer_Score ignores existing suggestion (Property 11)
    - // Feature: ai-assisted-marking, Property 11: Recording an Answer_Score is never influenced by an existing AI suggestion
    - For any prior `AiMarkingSuggestion` (or none) and any recruiter-supplied `(score, feedback)`, `SubmissionService.scoreAnswer()` persists exactly the supplied values
    - **Property 11: Recording an Answer_Score is never influenced by an existing AI suggestion**
    - **Validates: Requirements 5.2, 5.3**
  - [x]* 6.2 Write property test: failed generation never blocks manual marking (Property 13)
    - // Feature: ai-assisted-marking, Property 13: A failed suggestion generation never blocks manual marking of the same answer
    - For any answer where `generateSuggestion()` fails (AiService exception or unparseable response), a subsequent `scoreAnswer()` call for the same answer still succeeds
    - **Property 13: A failed suggestion generation never blocks manual marking of the same answer**
    - **Validates: Requirements 6.3**

- [x] 7. Implement AiMarkingController
  - [x] 7.1 Implement REST controller
    - Create `com.psybergate.recruitment.marking.ai.AiMarkingController`:
      - `@RestController @PreAuthorize("hasAnyRole('RECRUITER','ADMIN')") @RequiredArgsConstructor`
      - Inject `AiMarkingService`
      - `POST /api/submissions/{submissionId}/questions/{questionId}/ai-suggestion` → `generateSuggestion`
      - `GET /api/submissions/{submissionId}/questions/{questionId}/ai-suggestion` → `getSuggestion`
    - _Requirements: 7.1, 7.2_
  - [ ]* 7.2 Write integration tests for AiMarkingController
    - Create `AiMarkingControllerIntegrationTest` extending `AbstractIntegrationTest`, mocking `AiService` with `@MockBean`
    - Cover: RECRUITER generates suggestion for TEXT answer → 200 with suggestion body; CANDIDATE role → 403; unauthenticated → 401; generate then `scoreAnswer` → 200, `AnswerScore` reflects recruiter-supplied values unaffected by suggestion; generating a suggestion creates no `answer_scores` row; `GET` before generation → 404
    - _Requirements: 1.1, 4.3, 5.2, 5.3, 7.1, 7.2_
  - [ ]* 7.3 Write property test: only RECRUITER/ADMIN can invoke endpoints (Property 14)
    - // Feature: ai-assisted-marking, Property 14: Only RECRUITER and ADMIN roles can invoke the AI marking endpoints
    - For any role other than RECRUITER/ADMIN (including CANDIDATE) and for unauthenticated requests, both endpoints respond 403/401 and never invoke `AiMarkingService`
    - **Property 14: Only RECRUITER and ADMIN roles can invoke the AI marking endpoints**
    - **Validates: Requirements 7.1, 7.2**

- [ ] 8. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- No changes to `MarkingService`, `MarkingServiceImpl`, `SubmissionService`, `SubmissionServiceImpl`, `SubmissionController`, `AnswerScore`, or `GlobalExceptionHandler` — `AiMarkingResponseException` is picked up automatically via existing `@ResponseStatus` reflection
- `AiService` (from `ai-integration-foundation`) is injected and never mocked at the HTTP level — mock the `AiService` interface directly in unit tests, and via `@MockBean` in integration tests
- Reuse existing repositories (`CandidateAnswerRepository`, `CandidateSubmissionRepository`, `AssessmentQuestionRepository`, `QuestionRepository`) — no new query methods needed beyond `AiMarkingSuggestionRepository.findByCandidateAnswerId`
- Each `@Property` method must include the tag comment: `// Feature: ai-assisted-marking, Property N: <title>` and run minimum 100 tries
- Prompt format uses plain `SCORE:`/`RATIONALE:` text markers (not JSON) to keep parsing simple and deterministic

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "2.2"] },
    { "id": 2, "tasks": ["1.3", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "3.4", "4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["4.3"] },
    { "id": 6, "tasks": ["4.4", "4.5", "4.6", "4.7", "4.8", "4.9", "4.10", "4.11", "4.12", "4.13", "6.1", "6.2", "7.1"] },
    { "id": 7, "tasks": ["7.2", "7.3"] }
  ]
}
```
