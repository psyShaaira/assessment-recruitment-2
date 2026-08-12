# ATR2-11: Research Spike — Prompt Design & Versioning Approach

## Decision: File-Based Templates + Version String (Option A)

**Date:** 2026-08-12
**Status:** Accepted

---

## Context

Question generation (ATR2-ep-2) needs traceability: given a generated question, we must be
able to tell which prompt version produced it. Two options:

- **Option A:** Prompt templates as files (`src/main/resources/prompts/`), each with a version
  in its filename/header; the version string is logged/stored alongside each generation.
- **Option B:** A DB-backed `prompt_template` table (Flyway-migrated, next id `V23`), queried at
  generation time; generated questions FK to a template version row.

---

## Comparison

| Criterion | Option A (file-based) | Option B (DB-backed) |
|---|---|---|
| Traceability | Version string (e.g. `question-gen-v1`) stored on the generation record — sufficient to answer "which prompt produced this?" | Same, via FK — no stronger guarantee |
| Change audit trail | Free — git history/PR review already covers who changed what, when, why | Needs its own audit columns/history table to match what git gives for free |
| Editability | Requires a deploy to change a prompt | Editable at runtime without a deploy |
| Implementation cost | Zero new schema; a resource file + a version constant | New migration, entity, repository, and (eventually) an admin UI to edit templates safely |
| Consistency with codebase | Matches how `application.yaml` profiles and other static config are already deploy-time | Introduces a new "runtime-editable config" pattern not used elsewhere in this repo |
| Risk | A bad prompt edit goes through the same PR review as any other code change | A bad runtime edit can ship with no review gate unless one is built |

---

## Recommendation

**Option A — file-based templates with a version string** is the right fit for this project's
actual requirement (traceability), not the requirement no one asked for (runtime editing without
a deploy):

1. The acceptance criteria only asks for "which prompt version produced this question" —
   a version string on the generation record satisfies that fully. A DB table doesn't make
   that traceability any stronger, it just moves the audit trail off git and onto something
   we'd have to build ourselves.
2. No new Flyway migration, entity, or repository needed for something that's still a single
   active prompt per question type.
3. If runtime editing without a deploy becomes an actual requirement later (e.g. non-engineers
   need to tune prompts), revisit then — Option B is a reasonable upgrade path at that point,
   not before.

## Implementation shape (for ATR2-12)

```
src/main/resources/prompts/
  question-generation-v1.txt
```

- Prompt version is a constant (e.g. `"question-generation-v1"`) next to the file, bumped when
  the file's content changes meaningfully.
- The version string is stored on whatever record represents a generation event (e.g. a column
  on the generated question, or a generation-request log entry — exact shape decided in ATR2-12).
