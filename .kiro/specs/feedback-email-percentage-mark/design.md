# Feedback Email Percentage Mark Bugfix Design

## Overview

`FeedbackEmailBodyGenerator` can produce an AI-generated `Candidate_Feedback_Email` body that
never states the candidate's percentage mark. The root cause has two parts: `buildPrompt(...)`
gives the AI the score as context ("They scored X% overall.") but never instructs it to restate
that figure in the email it writes, and `validate(String aiBody, String candidateFirstName)` never
checks whether the returned `AI_Body` actually contains the percentage — so an `AI_Body` that
omits it can still pass every existing structural check (non-blank, contains the candidate's first
name, contains the sign-off, no markdown markers) and gets sent as-is.

The fix is two-pronged and defense-in-depth: (a) `buildPrompt(...)` gains an explicit instruction
directing the AI to state the whole-number percentage in the body it produces, reducing how often
the omission happens in the first place, and (b) `validate(...)` gains a new structural check that
rejects any `AI_Body` missing the percentage figure (e.g. "42%"), catching the omission whenever it
still occurs. A rejection from the new check is threaded through the existing retry/fallback
machinery in `generateBody(...)` exactly like any other Requirement 3.1 rejection — no changes are
needed to the retry loop itself. If all 3 attempts are exhausted, the existing `Static_Body`
fallback already includes the score sentence, so the candidate always ends up with an email that
states their percentage mark, either from a validated `AI_Body` or from the `Static_Body`.

## Glossary

- **Bug_Condition (C)**: An `AI_Body` that omits the candidate's whole-number score percentage
  figure (e.g. "42%") but would otherwise pass the pre-existing structural checks in `validate(...)`
  — i.e. exactly the case the original `validate(...)` fails to catch.
- **Property (P)**: The fixed system's guarantee that such an `AI_Body` is rejected (with a
  rejection reason naming the missing percentage) and, symmetrically, that the `Feedback_Prompt`
  always instructs the AI to state that percentage.
- **Preservation**: The existing four structural rejection reasons (blank, missing first name,
  missing sign-off, markdown marker), the retry/fallback loop in `generateBody(...)`, the
  `Static_Body`'s score sentence, and all other `Feedback_Prompt` content and `sendFeedbackEmail`
  behavior described in `.kiro/specs/ai-feedback-email-format/requirements.md`, none of which this
  fix may change.
- **AI_Body**: The plain-text string returned by a `Generation_Attempt`'s `AiService.prompt(...)`
  call, before or after `validate(...)` is applied.
- **Static_Body**: The deterministic fallback body produced by `renderStaticBody(...)`, used once
  all `Generation_Attempts` are rejected.
- **Feedback_Prompt**: The plain-text prompt string built by `buildPrompt(...)` and sent to
  `AiService.prompt(...)`.
- **Percentage figure**: The candidate's whole-number score percentage rendered as text with a
  trailing `%` (e.g. `"42%"`), computed as `Math.round((double) result.totalScore() / result.maxScore() * 100)`.
- **Generation_Attempt**: One iteration of the `generateBody(...)` retry loop (build prompt → call
  `AiService` → validate), as defined in `.kiro/specs/ai-feedback-email-format/design.md`.

## Bug Details

### Bug Condition

The bug manifests when an `AiService.prompt(...)` call returns an `AI_Body` that does not contain
the candidate's percentage figure. `buildPrompt(...)` never asked the AI to restate that figure
(it only supplied it as unlabelled context), and `validate(...)` never checks for the figure's
presence, so such an `AI_Body` — as long as it happens to be non-blank, mention the candidate's
first name, contain the sign-off, and contain no markdown markers — is accepted on the spot and
used as the `Candidate_Feedback_Email` body.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type ValidationInput { aiBody: String, candidateFirstName: String, percentage: long }
  OUTPUT: boolean

  percentageFigure := percentage + "%"

  passesPreExistingChecks :=
    input.aiBody IS NOT blank
    AND input.aiBody CONTAINS input.candidateFirstName
    AND input.aiBody CONTAINS "The Psybergate Recruitment Team"
    AND input.aiBody CONTAINS NONE OF ['#', '*', '`', '_']

  RETURN (input.aiBody DOES NOT CONTAIN percentageFigure)
         AND passesPreExistingChecks
END FUNCTION
```

### Examples

- An `AI_Body` reading `"Hi John,\n\nHere is your feedback...\n\nYou did a great job overall and
  should keep practicing.\n\n...\n\nThe Psybergate Recruitment Team"` for a candidate who scored
  73% — contains "John" and the sign-off, no markdown, but never states "73%". Expected: rejected
  with a reason naming the missing percentage. Actual (unfixed): accepted and sent to the
  candidate without their score.
- An `AI_Body` that discusses the score only in words (`"You performed well, achieving a solid
  majority of the available marks"`) with no digit-plus-`%` figure anywhere. Expected: rejected,
  since Requirement 2.2 requires the figure "rendered as a percentage figure, e.g. \"42%\"", not a
  paraphrase. Actual (unfixed): accepted.
- An `AI_Body` that contains a `%` character attached to an unrelated number (e.g. a topic-level
  aside such as `"only around 10% of candidates reach this stage"`) but never the candidate's own
  score figure (e.g. "73%"). Expected: rejected — a `%` occurring elsewhere in the body must not be
  mistaken for the candidate's percentage. Actual (unfixed): accepted (no such check exists at
  all).
- Edge case — an `AI_Body` that correctly states `"You scored 73% overall."`, along with the first
  name, sign-off, and no markdown: passes both before and after the fix (not the bug condition;
  see Preservation Requirement 3.1).

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- `validate(...)`'s four pre-existing rejection checks (blank, missing first name, missing
  sign-off, markdown marker) must continue to reject exactly the same inputs, for exactly the same
  reasons, as before.
- An `AI_Body` that already states the percentage figure and satisfies the pre-existing checks
  must continue to be accepted without triggering any additional retry.
- `generateBody(...)`'s retry loop (up to 3 total attempts, previous rejection reason folded into
  the next `Feedback_Prompt`) and its fallback to `Static_Body` once attempts are exhausted must
  continue to behave exactly as today — no changes to that control flow are needed or permitted.
- `Static_Body`'s score sentence ("You scored X% overall.") must continue to be rendered exactly as
  today.
- All pre-existing `Feedback_Prompt` content (candidate first name, assessment title, score
  context sentence, topic strengths/weaknesses, next steps, PII exclusions, the corrective-feedback
  clause on retry) must remain byte-for-byte identical, aside from the one new instruction bullet
  being added.
- `FeedbackEmailServiceImpl`'s 404/409 gating, `SENT`/`FAILED` send-log persistence, fixed email
  subject, and 502 path on `EmailService` failure must remain completely unaffected.

**Scope:**
All inputs that do NOT involve an otherwise-acceptable `AI_Body` omitting the percentage figure
should be completely unaffected by this fix. This includes:
- `AI_Body` values already rejected for a pre-existing reason (blank, missing first name, missing
  sign-off, markdown marker), regardless of whether they also happen to omit the percentage.
- `AI_Body` values that already contain the percentage figure.
- `AiService` exceptions of any of the five listed types (unrelated to `validate(...)`'s logic).
- Mouse-click/UI-level and non-generation behavior (candidate lookup, gating, logging, subject
  line) — none of which this fix touches.

## Hypothesized Root Cause

Based on the bug description and the source of `FeedbackEmailBodyGenerator`, the causes are:

1. **Missing instruction in the prompt**: `buildPrompt(...)`'s "Write a plain-text email body
   that:" instruction block lists tone/content/formatting/sign-off requirements but has no bullet
   telling the AI to restate the score. The percentage is only given as unlabelled context
   ("They scored X% overall."), which the AI is free to summarize, paraphrase, or drop entirely
   when writing the actual body.

2. **Missing check in validation**: `validate(String aiBody, String candidateFirstName)` only
   checks blankness, first-name presence, sign-off presence, and markdown markers. It has no
   parameter carrying the percentage and no corresponding `contains(...)` check, so there is no
   mechanism by which an `AI_Body` omitting the score could ever be rejected — the omission is
   structurally invisible to the validator.

3. **No shared percentage value threaded through the attempt**: `buildPrompt(...)` and
   `renderStaticBody(...)` each independently compute
   `Math.round((double) result.totalScore() / result.maxScore() * 100)`; `validate(...)` has never
   needed this value and isn't passed `result` at all, so adding the check requires threading the
   already-computed percentage from `attempt(...)` into `validate(...)`.

## Correctness Properties

Property 1: Bug Condition - Percentage Is Always Instructed For and Enforced

_For any_ input where the bug condition holds (isBugCondition returns true) — i.e. a
`Generation_Attempt`'s `AI_Body` omits the candidate's percentage figure but would otherwise pass
the pre-existing structural checks — the fixed system SHALL (a) have built that attempt's
`Feedback_Prompt` with an explicit instruction directing the AI to state the candidate's
whole-number percentage in the email body, and (b) have `validate(aiBody, candidateFirstName,
percentage)` reject that `AI_Body`, returning a rejection reason that names the missing percentage
figure, so that the rejection is retried (per Requirement 3.2 of `ai-feedback-email-format`, if
fewer than 3 total attempts have been made) or causes fallback to the `Static_Body` (per
Requirement 3.3, once exhausted) exactly as any other structural rejection does.

**Validates: Requirements 2.1, 2.2**

Property 2: Preservation - Existing Validation, Prompt, Retry, and Fallback Behavior Unchanged

_For any_ input where the bug condition does NOT hold (isBugCondition returns false) — i.e. an
`AI_Body` that already contains the percentage figure, or one rejected for a pre-existing reason
(blank, missing first name, missing sign-off, markdown marker), or any `AiService` exception —
the fixed `validate(...)` SHALL produce exactly the same accept/reject outcome and, where rejected
for a pre-existing reason, exactly the same rejection reason as the original `validate(...)`; the
fixed `buildPrompt(...)` SHALL produce exactly the same content as the original `buildPrompt(...)`
aside from the one added percentage-statement instruction bullet; and `generateBody(...)`'s
retry count, fallback behavior, `Static_Body` rendering, and `FeedbackEmailServiceImpl`'s gating,
logging, and subject-line behavior SHALL continue exactly as before.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `recruitment-be/src/main/java/com/psybergate/recruitment/feedbackemail/FeedbackEmailBodyGenerator.java`

**Functions**: `attempt(...)`, `buildPrompt(...)`, `validate(...)`

**Specific Changes**:
1. **Extract a shared percentage helper**: add a private `long computePercentage(ResultSummaryResponse result)` wrapping the existing
   `Math.round((double) result.totalScore() / result.maxScore() * 100)` expression, currently
   duplicated in `buildPrompt` and `renderStaticBody`. This gives `attempt(...)` a single value it
   can pass to both `buildPrompt(...)` and the new `validate(...)` parameter, guaranteeing the
   instructed figure and the checked figure are always identical for a given attempt.

2. **Thread the percentage into `validate`**: change `validate`'s signature from
   `validate(String aiBody, String candidateFirstName)` to `validate(String aiBody, String
   candidateFirstName, long percentage)`. Update the sole call site inside `attempt(...)` to pass
   `computePercentage(result)`.

3. **Add an explicit percentage-restatement instruction to the prompt**: in `buildPrompt`, add a
   new bullet to the existing "Write a plain-text email body that:" block, e.g.:
   `- States the candidate's overall score percentage ("<percentage>%") explicitly in the email body`,
   interpolating the same `percentage` value already used for the "They scored X% overall." context
   sentence earlier in the same method. This is additive — no existing bullet is reordered or
   removed.

4. **Add a percentage-presence check to `validate`**: add a new check (after the sign-off check,
   before the markdown-marker loop) of the form:
   ```java
   String percentageFigure = percentage + "%";
   if (!aiBody.contains(percentageFigure)) {
       return Optional.of("The response did not state the candidate's score percentage (\""
               + percentageFigure + "\").");
   }
   ```
   This follows the exact same `Optional<String>` rejection-reason shape as the four pre-existing
   checks, so it composes with them without any special-casing.

5. **No change to `generateBody` or the retry loop**: `generateBody(...)`'s `MAX_ATTEMPTS` loop and
   rejection-reason threading already treat any non-empty `Optional<String>` from `validate`
   uniformly (Requirement 2.2's "treat identically to any other Requirement 3.1 structural
   rejection" is satisfied without touching that method). `renderStaticBody(...)` is unchanged —
   it already includes the score sentence.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that
demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing
behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or
refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Call `buildPrompt(...)` and `validate(...)` directly (both are package-private, so
directly testable from a test in the same package) with inputs designed to trigger the bug, and
also drive `generateBody(...)` end-to-end with a mocked `AiService`. Run these against the UNFIXED
code to observe failures and confirm the root cause.

**Test Cases**:
1. **Prompt Instruction Absence Test**: call `buildPrompt(...)` with arbitrary valid inputs and
   assert the returned prompt contains an instruction directing the AI to state the percentage
   figure — fails on unfixed code (no such bullet exists).
2. **Validate Accepts Percentage-Omitting Body Test**: call `validate(aiBody, firstName)` (unfixed
   2-arg signature) with an `AI_Body` that has the first name, the sign-off, no markdown, but no
   percentage figure; assert it is rejected — fails on unfixed code (`validate` returns
   `Optional.empty()`, i.e. accepts it).
3. **End-to-End Generation Test**: mock `AiService.prompt(...)` to always return an otherwise-valid
   `AI_Body` missing the percentage; call `generateBody(...)`; assert the returned body is NOT that
   `AI_Body` (it should have been rejected, retried, and ultimately replaced by `Static_Body`) —
   fails on unfixed code (attempt 1 is accepted and returned as-is).
4. **Stray Percent Sign Edge Case**: call `validate(...)` with an `AI_Body` containing an unrelated
   `%` figure (e.g. "10% of candidates...") but not the candidate's own score figure; assert it is
   rejected — may already "fail" on unfixed code in the sense that it's wrongly accepted, same as
   case 2, confirming the check is entirely absent rather than merely imprecise.

**Expected Counterexamples**:
- `buildPrompt(...)` output never contains a percentage-restatement instruction.
- `validate(...)` returns `Optional.empty()` (accepted) for `AI_Body` values that plainly omit the
  candidate's score, regardless of how the omission occurs.
- Possible causes: no instruction bullet in the prompt template; no percentage parameter or check
  in `validate`; percentage never threaded from `attempt(...)` into validation.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces
the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := validate_fixed(input.aiBody, input.candidateFirstName, input.percentage)
  ASSERT expectedBehavior(result)
END FOR

FUNCTION expectedBehavior(result)
  INPUT: result of type Optional<String>  // rejection reason, or empty if accepted
  OUTPUT: boolean

  RETURN result.isPresent() AND result.get() mentions the missing percentage figure
END FUNCTION
```

Additionally, for every `input` used above, `buildPrompt(...)` built with the same `percentage`
SHALL contain an instruction directing the AI to state that percentage figure.

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function
produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT validate_original(input.aiBody, input.candidateFirstName)
       = validate_fixed(input.aiBody, input.candidateFirstName, input.percentage)
END FOR

FOR ALL (content, result, firstName, previousRejectionReason) DO
  ASSERT buildPrompt_fixed(content, result, firstName, previousRejectionReason)
       = buildPrompt_original(content, result, firstName, previousRejectionReason)
         WITH the one new instruction bullet inserted
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many random `AI_Body`/name/percentage combinations automatically, including
  adversarial strings (blank, missing name, missing sign-off, markdown markers, combinations
  thereof) that manual unit tests might miss.
- It generates many random `FeedbackReportContent`/`ResultSummaryResponse` combinations for
  `buildPrompt`, exercising varying topic/strength/weakness/next-step shapes.
- It provides strong guarantees that none of the four pre-existing rejection reasons, the prompt's
  pre-existing content, or the retry/fallback loop have regressed.

**Test Plan**: Observe behavior on UNFIXED code first for `AI_Body` values that already state the
percentage or fail for a pre-existing reason, and for `buildPrompt(...)`'s pre-existing content,
then write property-based tests capturing that behavior against the fixed code.

**Test Cases**:
1. **Pre-Existing Rejection Reasons Preserved**: for `AI_Body` values that are blank, missing the
   first name, missing the sign-off, or containing a markdown marker (independently of whether they
   also omit the percentage), verify the fixed `validate(...)` returns the identical rejection
   reason string the original did.
2. **Already-Compliant AI_Body Still Accepted**: for `AI_Body` values that already contain the
   percentage figure and pass all pre-existing checks, verify the fixed `validate(...)` still
   returns `Optional.empty()` (accepted), matching the original.
3. **Prompt Content Otherwise Unchanged**: verify the fixed `buildPrompt(...)` output equals the
   original output with exactly one additional instruction bullet inserted — first name, assessment
   title, score context sentence, topic strengths/weaknesses, next steps, PII exclusions, and the
   corrective-feedback clause on retry are all still present, unchanged, and in the same relative
   order.
4. **Retry/Fallback Loop Preserved**: verify `generateBody(...)`'s 3-attempt cap, rejection-reason
   threading into the next prompt, and `Static_Body` fallback behave identically whether the
   rejection came from a pre-existing reason, an `AiService` exception, or the new percentage
   check.

### Unit Tests

- `validate(...)` rejects an `AI_Body` missing the percentage figure, with a reason naming it.
- `validate(...)` accepts an `AI_Body` that states the percentage figure and satisfies all other
  checks.
- `validate(...)` continues to reject blank / missing-first-name / missing-sign-off / markdown-marker
  `AI_Body` values with their original reasons, independent of percentage presence.
- `buildPrompt(...)` output contains the new percentage-restatement instruction bullet.
- `generateBody(...)` end-to-end: an `AI_Body` missing the percentage on attempt 1 is rejected,
  retried with the rejection reason folded into attempt 2's prompt, and (if attempt 2 also omits
  the percentage, attempt 3 too) ultimately falls back to `Static_Body`.
- `generateBody(...)` end-to-end: an `AI_Body` that includes the percentage is accepted on the first
  attempt without any retry.

### Property-Based Tests

- Generate random `AI_Body` strings (including ones that omit the percentage figure, ones that
  contain an unrelated `%` figure, and ones that state it correctly) crossed with random candidate
  first names and percentages, and verify `validate(...)` rejects if and only if the bug condition
  holds or a pre-existing check fails (Property 1 and Property 2 combined coverage).
- Generate random `FeedbackReportContent`/`ResultSummaryResponse`/first-name combinations and
  verify `buildPrompt(...)`'s output always contains both the score context sentence and the new
  percentage-restatement instruction, alongside all pre-existing content (Property 1, prompt half).
- Generate random sequences of rejected/accepted `Generation_Attempts` (mixing percentage
  omissions, pre-existing rejection reasons, and `AiService` exceptions) and verify
  `generateBody(...)`'s attempt count, retry-reason threading, and fallback-to-`Static_Body`
  behavior are unchanged from `ai-feedback-email-format`'s existing Properties 6 and 7 (Property 2).

### Integration Tests

- Full `sendFeedbackEmail` flow where the mocked AI returns a percentage-omitting body on every
  attempt: verify the candidate ultimately receives the `Static_Body` (which states the score),
  and that `SENT`/`FAILED` logging and the response shape are unaffected.
- Full `sendFeedbackEmail` flow where the mocked AI returns a percentage-omitting body on attempt 1
  and a compliant body on attempt 2: verify the compliant body is sent and no further retries
  occur.
- Confirm the existing 404/409 gating and 502-on-`EmailService`-failure integration tests
  (`ai-feedback-email-format`'s existing integration coverage) remain green and unmodified by this
  fix.
