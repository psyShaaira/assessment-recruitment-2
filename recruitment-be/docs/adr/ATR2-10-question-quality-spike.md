# ATR2-10: Research Spike — Bounding & Validating LLM-Generated Question Quality

**Date:** 2026-08-12
**Status:** Accepted
**Method:** 28 live generations against Groq (`llama-3.3-70b-versatile`, `response_format: json_object`), spanning `MCQ` (10), `TEXT` (10), `CODE_SUBMISSION` (8) across `EASY`/`MEDIUM`/`HARD`. Raw outputs and an automated structural check for each sample are in this branch's scratch history (not committed — throwaway spike script, not a repo deliverable). `GROUP` questions are out of scope for direct generation — see note below.

---

## 1. JSON schema per question subtype

Mirrors the existing `QuestionRequest` DTO (`question/dto/QuestionRequest.java`) and the `Question`/`McqQuestion`/`TextQuestion`/`CodeSubmissionQuestion` domain model exactly, so a parsed response can be mapped straight onto it.

**Common to all types** (base `Question` fields the LLM should populate):
```json
{
  "title": "string, required, <=500 chars",
  "body": "string, required",
  "difficulty": "EASY | MEDIUM | HARD",
  "tags": ["string", "..."]
}
```
(`maxScore` and `createdBy` are set by the service layer, not the LLM.)

**MCQ** — adds:
```json
{
  "options": [
    { "text": "string, required", "correct": "boolean" }
  ]
}
```
Constraint mirrored from `QuestionServiceImpl.validateMcqOptions`: `options.length >= 2`, exactly one `correct: true`.

**TEXT** — no extra fields.

**CODE_SUBMISSION** — adds:
```json
{ "languageHint": "string, <=100 chars" }
```

**GROUP** — **not generated directly.** `GroupQuestion` composes existing question IDs (`memberQuestionIds`, validated in `QuestionServiceImpl.buildGroup`: ≥2 members, each must already exist, no nested `GROUP`). An LLM has no way to know real question UUIDs from a generation prompt, so group assembly is a *selection* UI over already-generated/existing questions (this is exactly what ATR2-13, "AI-assisted assessment assembly," is for) — not a `/generate` request. Worth stating explicitly so ATR2-12's endpoint doesn't try to support `type: GROUP`.

---

## 2. Failure-mode catalog (from 28 live samples)

| Failure mode | Occurrences | Notes |
|---|---|---|
| Malformed JSON | 0/28 | `response_format: json_object` was reliable at this sample size |
| MCQ option count < 2 | 0/10 | |
| MCQ correct-count ≠ 1 | 0/10 | |
| Invalid `difficulty` enum value | 0/28 | |
| Off-topic content | 0/28 | All outputs stayed on the requested topic |
| **`languageHint` unsupported by this platform's sandbox** | **5/8** (unconstrained prompt) → **0/5** (language pinned) | Groq defaults to Python for generic algorithm prompts ("FizzBuzz", "Two Sum", "Binary search", "Merge intervals", "Palindrome check" all came back `languageHint: "python"`) when the prompt doesn't say otherwise. This repo's Piston only has the **Java** runtime installed (`piston-init` in `docker-compose.yml`) — a Python-hinted question is unexecutable as-is. Re-ran all 5 with the language pinned in the system prompt ("MUST always be java, regardless of topic") — **5/5 complied**. This is a prompting fix, not just a validation problem. |
| Answer leakage in `TEXT` bodies | ~3/10 | Some generated `body` text pre-explains the concept in detail before asking the candidate to explain it (e.g. CAP theorem, REST vs SOAP, Git branching) — reduces assessment validity since a candidate can partly parrot the prompt back. |
| Near-duplicate MCQ distractors | 1/10 (soft) | One BST-deletion question had two options differing only by a trailing clause — confusable, not a hard schema violation. |

The `languageHint` mismatch is the one hard, must-fix finding — everything else about structural correctness was solid at this sample size.

---

## 3. Server-side validation rules for ATR2-12

Reuse the exact imperative checks already in `QuestionServiceImpl` (these are enforced in code, not Bean Validation annotations, so a generation endpoint must call the same logic, not duplicate it):

- **`languageHint` — prevent, don't just detect:** the system prompt for `CODE_SUBMISSION` generation must state outright that the sandbox only supports Java and `languageHint` must always be `"java"`, regardless of topic — this is prompt-level, applied unconditionally before the request ever reaches Groq, not left to the model's discretion. Verified live: 5/5 previously-Python topics complied once pinned. Server-side validation against a runtime allowlist (currently just `"java"`) stays in as defense-in-depth — the prompt fix is the primary control, validation is the backstop for whatever slips through.
- **MCQ:** `validateMcqOptions` as-is — ≥2 options, exactly one `correct: true`. Reject + retry (one retry, then surface a "generation failed" error) on violation.
- **`difficulty`:** must parse into `Difficulty` enum; reject + retry on invalid/missing value.
- **`title`/`body`:** non-blank, `title` ≤500 chars — same as `QuestionRequest`'s `@NotBlank`.
- **`GROUP`:** never accepted from the generation endpoint at all (see §1) — a 400 if `type: GROUP` is requested from `/generate`.
- **Retry policy:** one automatic retry on any validation failure that survives the prompt-level prevention (append the specific violation to the prompt as corrective feedback), then fail the request rather than silently persisting something invalid.

---

## 4. Review-before-save confirmation

**Generated questions must never be auto-persisted.** The generation endpoint (ATR2-12) returns a preview payload only (shape matching `QuestionResponse`, no `id`/`createdAt`) — it does not call `QuestionServiceImpl.create` directly. Persisting a reviewed/edited question goes through the **existing** question-creation endpoint and its existing validation, identically to a manually authored question. This is a design commitment for ATR2-12, not something already built — there's no current code path that could violate it since generation doesn't exist yet — but it fixes the contract: recruiter review is the only path to a saved question, generation is suggestion-only.
