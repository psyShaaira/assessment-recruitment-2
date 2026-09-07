# MCQ Distractor Generation — Requirements

## Overview

When authoring a multiple-choice question, a recruiter writes the question stem (body) and the correct answer themselves, then asks Groq to generate plausible-but-wrong answer options (distractors). The generated distractors are appended to the existing option list as unsaved, editable rows — the recruiter reviews and edits before saving. The recruiter always retains ownership of which option is correct; the AI only produces wrong options.

This is distinct from the existing full question generation (`POST /api/questions/generate`), which produces an entire question (stem + options + correct answer) from a topic. Here the recruiter owns the stem and the correct answer; the AI fills only the tedious distractor slots.

## Actors

- **Recruiter / Admin** — authors MCQ questions (`ROLE_RECRUITER` or `ROLE_ADMIN`).
- **System** — builds a guarded prompt, calls Groq, validates + de-duplicates the returned distractors.

## Functional Requirements

### FR-1: Distractor Request

- FR-1.1: A recruiter SHALL be able to request AI-generated distractors via `POST /api/questions/generate-distractors` with a body of `{ questionBody, correctAnswer, count }`.
- FR-1.2: `questionBody` is the MCQ stem (required, non-blank). `correctAnswer` is the recruiter's correct option text (required, non-blank). `count` is the number of distractors to generate (1–5, default 3).
- FR-1.3: The endpoint SHALL return a list of distractor option drafts: `[{ text, correct: false }, ...]`. Every returned option SHALL have `correct = false`.
- FR-1.4: The endpoint SHALL require `ROLE_RECRUITER` or `ROLE_ADMIN` (covered by the existing `/api/questions/**` rule on `QuestionController`).
- FR-1.5: Generated distractors SHALL NOT be persisted by this endpoint. They are unsaved drafts returned for the recruiter to review, edit, and save through the normal question-create/update path.

### FR-2: Answer-Correctness Guardrail

- FR-2.1: The prompt SHALL instruct Groq that every generated option MUST be factually incorrect for the given stem — plausible and tempting, but wrong.
- FR-2.2: A generated distractor SHALL NOT duplicate or be a near-duplicate of the recruiter's `correctAnswer` (reusing the existing near-duplicate heuristic from `QuestionGenerationServiceImpl.checkForNearDuplicateOptions`).
- FR-2.3: Generated distractors SHALL NOT be near-duplicates of one another.
- FR-2.4: If validation fails (a distractor matches the correct answer, distractors are too similar, blank text, or wrong count), the system SHALL retry once with corrective feedback (mirroring the existing generation retry), then surface an error if it still fails.

### FR-3: Frontend Integration (append behaviour)

- FR-3.1: The MCQ section of the question form SHALL present a "Suggest distractors with AI" control.
- FR-3.2: The control SHALL be enabled only when the stem (`body`) and at least one option marked correct (with non-blank text) are present — that option's text is sent as `correctAnswer`.
- FR-3.3: Generated distractors SHALL be **appended** to the existing option rows as new `correct: false` rows. Existing options (including the recruiter's correct answer and any distractors they already typed) SHALL NOT be modified or removed.
- FR-3.4: A count selector SHALL let the recruiter choose how many distractors to request (default 3).
- FR-3.5: While the request is in flight, the control SHALL show a loading state and be disabled.
- FR-3.6: On error (AI unavailable, validation failure), a toast SHALL inform the recruiter; existing options SHALL be left untouched.

## Non-Functional Requirements

### NFR-1: Graceful Degradation
- If Groq is unavailable (missing key, timeout, rate limit), the endpoint SHALL surface the existing typed AI exceptions (mapped to ProblemDetail by `GlobalExceptionHandler`), and the frontend SHALL show a friendly, retry-oriented message — consistent with the existing `friendlyAiError` handling in the question form.

### NFR-2: No PII
- The prompt contains only the question stem and correct-answer text supplied by the recruiter — no candidate or user PII.

### NFR-3: Conventions
- Reuses `AiService`, the `ai` package, `@ResponseStatus`-annotated exceptions, and the near-duplicate validation already present. No new database schema (drafts are transient, like existing generation). Constructor injection via `@RequiredArgsConstructor`.

## Out of Scope

- Persisting a history/audit of distractor requests (no schema change).
- Distractor generation for non-MCQ types.
- Generating or altering the correct answer (recruiter owns it).
- Full question generation (already exists at `POST /api/questions/generate`).
