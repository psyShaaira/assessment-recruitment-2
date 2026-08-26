# Implementation Plan

## Overview

This plan fixes `FeedbackEmailBodyGenerator` so every `Candidate_Feedback_Email` states the
candidate's percentage mark. Per the bug condition methodology: write a property-based
exploration test that demonstrates the bug on the UNFIXED code (Property 1), write
property-based preservation tests that capture the UNFIXED code's behavior for non-buggy inputs
(Property 2), then implement the two-pronged fix (`buildPrompt` instruction + `validate`
percentage check) and re-run both test suites to confirm the bug is fixed and nothing else
regressed.

`buildPrompt(...)` and `validate(...)` are currently `private`. To make them directly testable
from a test class in the same package (as the design's Testing Strategy assumes), task 1 widens
their access modifier to package-private (no visibility keyword). This is a pure test-scaffolding
change with no behavioral effect and is safe to make before writing the exploration test.

## Tasks

- [ ] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Percentage Is Always Instructed For and Enforced
  - **IMPORTANT**: Write this property-based test BEFORE implementing the fix
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - Create `recruitment-be/src/test/java/com/psybergate/recruitment/feedbackemail/FeedbackEmailBodyGeneratorBugfixTest.java` in the same package as `FeedbackEmailBodyGenerator`
  - Widen `buildPrompt(...)` and `validate(String aiBody, String candidateFirstName)` from `private` to package-private (no modifier) so this test class can call them directly (no behavior change)
  - **Scoped PBT Approach**: Using jqwik, generate `AI_Body` strings that satisfy `isBugCondition` from design: non-blank, contain an arbitrary candidate first name, contain the sign-off "The Psybergate Recruitment Team", contain none of the markdown markers (`#`, `*`, `` ` ``, `_`), and do NOT contain the percentage figure (`percentage + "%"`) for an arbitrary `percentage` in `[0, 100]` — include the stray-percent-sign edge case (a `%` figure attached to a different number than `percentage`)
  - Test 1 (`validate` accepts a percentage-omitting body): call `validate(aiBody, candidateFirstName)` (the current 2-arg signature) with such generated inputs and assert it returns `Optional.empty()` (accepted) — this demonstrates Requirement 1.2's gap
  - Test 2 (`buildPrompt` never instructs restating the percentage): call `buildPrompt(content, result, firstName, null)` with arbitrary valid `FeedbackReportContent`/`ResultSummaryResponse` and assert the returned prompt contains no instruction directing the AI to state the percentage figure in the email body — this demonstrates Requirement 1.1's gap
  - Run both tests on the UNFIXED code
  - **EXPECTED OUTCOME**: Both tests FAIL (this is correct — it proves the bug exists)
  - Document the counterexamples found (e.g. a specific generated `AI_Body` that `validate` wrongly accepts, and the fact that `buildPrompt`'s "Write a plain-text email body that:" block never mentions the percentage)
  - Mark task complete when both tests are written, run, and their failures documented
  - _Requirements: 1.1, 1.2_

- [ ] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Existing Validation, Prompt, Retry, and Fallback Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology — observe behavior on UNFIXED code, then encode it as properties
  - Add tests to `FeedbackEmailBodyGeneratorBugfixTest.java` (same class as task 1)
  - Observe on UNFIXED code and encode as jqwik properties over generated inputs where `isBugCondition` does NOT hold:
    - For `AI_Body` values that are blank, or missing the candidate's first name, or missing the sign-off, or containing a markdown marker (independent of percentage presence): observe `validate(aiBody, candidateFirstName)`'s returned rejection reason string, then assert it matches exactly
    - For `AI_Body` values that already contain the percentage figure and satisfy all four pre-existing checks: observe `validate(...)` returns `Optional.empty()`, then assert acceptance
    - For random `FeedbackReportContent`/`ResultSummaryResponse`/first-name/`previousRejectionReason` combinations: observe `buildPrompt(...)`'s output, then assert it contains the first name, assessment title (when present), the "They scored X% overall." sentence, every strength/weakness topic line, every next-step bullet, the plain-text/no-markdown instruction, the sign-off instruction, and (when `previousRejectionReason` is non-null) the corrective-feedback clause
    - For sequences of `Generation_Attempts` mixing pre-existing rejection reasons and `AiService` exceptions (mocked `AiService`, no percentage-omission cases): observe `generateBody(...)`'s attempt count (≤ 3), retry-reason threading into the next prompt, and fallback to `Static_Body` (which already includes the score sentence) once exhausted
  - Run all preservation tests on the UNFIXED code
  - **EXPECTED OUTCOME**: All preservation tests PASS (this confirms the baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [ ] 3. Fix for missing percentage mark in feedback emails

  - [ ] 3.1 Extract `computePercentage` and thread the percentage into `validate`
    - Add a private `long computePercentage(ResultSummaryResponse result)` to `FeedbackEmailBodyGenerator`, wrapping `Math.round((double) result.totalScore() / result.maxScore() * 100)`
    - Replace the duplicated inline computation in `buildPrompt(...)` and `renderStaticBody(...)` with calls to `computePercentage(result)`
    - Change `validate`'s signature to `validate(String aiBody, String candidateFirstName, long percentage)`
    - Update the sole call site in `attempt(...)` to pass `computePercentage(result)`
    - _Bug_Condition: isBugCondition(input) where input.aiBody omits percentage + "%" but passes the pre-existing checks_
    - _Requirements: 2.2_

  - [ ] 3.2 Add the percentage-restatement instruction to `buildPrompt`
    - In `buildPrompt`'s "Write a plain-text email body that:" bullet block, add a new bullet: `- States the candidate's overall score percentage ("<percentage>%") explicitly in the email body`, interpolating the same `percentage` value used for the earlier "They scored X% overall." context sentence
    - Do not reorder or remove any existing bullet
    - _Expected_Behavior: expectedBehavior(result) from design — Feedback_Prompt built with an explicit instruction to state the percentage_
    - _Requirements: 2.1_

  - [ ] 3.3 Add the percentage-presence check to `validate`
    - After the sign-off check and before the markdown-marker loop, add:
      ```java
      String percentageFigure = percentage + "%";
      if (!aiBody.contains(percentageFigure)) {
          return Optional.of("The response did not state the candidate's score percentage (\""
                  + percentageFigure + "\").");
      }
      ```
    - _Bug_Condition: isBugCondition(input) where input.aiBody omits percentage + "%" but passes the pre-existing checks_
    - _Expected_Behavior: expectedBehavior(result) from design — validate rejects with a reason naming the missing percentage figure_
    - _Preservation: the four pre-existing checks (blank, first name, sign-off, markdown) must still run first and return their original reasons unchanged_
    - _Requirements: 2.2_

  - [ ] 3.4 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Percentage Is Always Instructed For and Enforced
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - Update only the call sites in `FeedbackEmailBodyGeneratorBugfixTest.java` that invoke `validate(...)` to pass the now-required `percentage` argument (matching the same `percentage` used to generate the test's `AI_Body`)
    - Run the exploration tests from task 1 against the fixed code
    - **EXPECTED OUTCOME**: Both tests PASS (confirms the bug is fixed: `validate` rejects percentage-omitting bodies with a reason naming the figure, and `buildPrompt` always includes the restatement instruction)
    - _Requirements: 2.1, 2.2_

  - [ ] 3.5 Verify preservation tests still pass
    - **Property 2: Preservation** - Existing Validation, Prompt, Retry, and Fallback Behavior Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Update only the call sites that invoke `validate(...)` directly to pass the additional `percentage` argument, without changing any assertion
    - Run the preservation tests from task 2 against the fixed code
    - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions to the four pre-existing rejection reasons, prompt content, or the retry/fallback loop)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [ ] 4. Checkpoint - Ensure all tests pass
  - Run `./mvnw test -Dtest=FeedbackEmailBodyGeneratorBugfixTest` and the full `./mvnw test`
  - Ensure all tests pass, ask the user if questions arise

- [ ]* 5. Add regression unit tests with concrete examples
  - Add to a new `FeedbackEmailBodyGeneratorTest.java` (or the existing suite for this class, if one exists by the time this runs):
    - `validate(...)` rejects a concrete `AI_Body` missing the percentage figure, with a reason naming it
    - `validate(...)` accepts a concrete `AI_Body` that states the percentage figure and satisfies all other checks
    - `validate(...)` still rejects concrete blank / missing-first-name / missing-sign-off / markdown-marker `AI_Body` values with their original reasons, independent of percentage presence
    - `buildPrompt(...)` output contains the new percentage-restatement instruction bullet
    - `generateBody(...)` end-to-end: a mocked `AiService` returns a percentage-omitting body on attempt 1, is retried with the rejection reason folded into attempt 2's prompt, and (if attempts 2 and 3 also omit it) falls back to `Static_Body`
    - `generateBody(...)` end-to-end: a mocked `AiService` returns a percentage-including body and it is accepted on the first attempt without retry
  - _Requirements: 2.1, 2.2, 3.2, 3.3_

- [ ]* 6. Add integration test coverage for the fix
  - In the existing `FeedbackEmailServiceImpl` integration test suite, add:
    - Full `sendFeedbackEmail` flow where the mocked AI returns a percentage-omitting body on every attempt: verify the candidate receives the `Static_Body` (which states the score) and `SENT` logging/response shape are unaffected
    - Full `sendFeedbackEmail` flow where the mocked AI returns a percentage-omitting body on attempt 1 and a compliant body on attempt 2: verify the compliant body is sent with no further retries
  - Confirm the existing 404/409 gating and 502-on-`EmailService`-failure integration tests remain green and unmodified
  - _Requirements: 2.2, 3.5, 3.8_

- [ ] 7. Final checkpoint - Ensure all tests pass
  - Run `./mvnw test` and `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`
  - Ensure all tests pass and mutation coverage stays above the PIT threshold (29), ask the user if questions arise

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster fix; the model implementing
  this plan MUST NOT implement tasks marked with `*` unless explicitly asked to.
- `FeedbackEmailBodyGeneratorBugfixTest.java` is the single shared test class for tasks 1, 2, 3.4,
  and 3.5 — each of those tasks adds or updates `@Property` methods on that same file rather than
  creating new files.
- `AiService` is mocked with Mockito wherever `generateBody(...)` is exercised; no real network
  call is made in any test in this plan.
- Property 1 and Property 2 correspond exactly to the two properties in design.md's "Correctness
  Properties" section — no additional properties are introduced here.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3"] },
    { "id": 4, "tasks": ["3.4"] },
    { "id": 5, "tasks": ["3.5"] },
    { "id": 6, "tasks": ["4"] },
    { "id": 7, "tasks": ["5", "6"] },
    { "id": 8, "tasks": ["7"] }
  ]
}
```
