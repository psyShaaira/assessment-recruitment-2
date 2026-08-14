# OpenSpec Workflow (via the `openspec` CLI)

This document explains how to drive this repo's OpenSpec change-proposal workflow directly through the `openspec` CLI. It is the Kiro-native equivalent of the six `opsx` slash-commands/skills (`new`, `propose`, `ff`, `explore`, `apply`, `archive`) that exist under `.claude/commands/opsx/` and `.claude/skills/openspec-*/` — same CLI, same underlying behavior, just driven conversationally instead of through a slash-command system (Kiro has none).

**Scope note**: This document only covers *how* to execute OpenSpec CLI operations once OpenSpec has already been chosen as the right tool for a piece of work. For guidance on *whether* to use OpenSpec vs. a Kiro spec, see `structure.md` ("Spec Workflows in This Repo" / "Feature Development Workflow") and `product.md` ("Development Methodology") — this document does not restate or override that decision guidance.

Where the original Claude tooling used Claude-specific tools (`AskUserQuestion`, `TodoWrite`), the Kiro equivalents are: ask the user directly (free-text or via the input tool) for clarification, and track artifact/task progress inline in the conversation rather than in a separate todo list.

---

## 1. Starting a new change

Use this when you just want to scaffold a change and see the first artifact template, without generating everything at once.

**Input**: a kebab-case change name, or a description to derive one from (e.g. "add user authentication" → `add-user-auth`).

**Steps**:

1. If no name/description is available, ask the user what they want to build. Do not proceed without understanding the change's intent.
2. Determine the schema: omit `--schema` to use the default unless the user explicitly names a different workflow schema (or asks "what workflows exist", in which case run `openspec schemas --json` and let them pick).
3. Create the change:
   ```bash
   openspec new change "<name>" [--schema <name>]
   ```
   This scaffolds `openspec/changes/<name>/` with `.openspec.yaml` and the schema's artifact set.
4. Show artifact status:
   ```bash
   openspec status --change "<name>"
   ```
5. Find the first artifact with status `ready` in that output, then fetch its template:
   ```bash
   openspec instructions <first-artifact-id> --change "<name>"
   ```
6. **Stop here.** Show the template and wait for user direction — do not create the artifact file yet, and do not advance past the first artifact.

If a change with that name already exists, suggest continuing it instead of creating a new one.

---

## 2. Proposing a change (all artifacts in one step)

Use this when the user wants to go from a description straight to a fully-drafted change (proposal, design, tasks, or whatever the schema defines) ready for implementation.

**Steps**:

1. Same intake as above — get a name/description, ask if missing.
2. Create the change: `openspec new change "<name>"`.
3. Get the build order:
   ```bash
   openspec status --change "<name>" --json
   ```
   From the JSON, note `applyRequires` (artifact IDs needed before implementation, e.g. `["tasks"]`) and `artifacts` (each artifact's status/dependencies).
4. **Artifact-instructions loop** — repeat until every artifact ID in `applyRequires` has `status: "done"`:
   - Pick an artifact currently `ready` (its dependencies are satisfied).
   - Fetch instructions:
     ```bash
     openspec instructions <artifact-id> --change "<name>" --json
     ```
     This returns `context`, `rules`, `template`, `instruction`, `outputPath`, and `dependencies`.
   - Read any completed dependency artifact files for context.
   - Write the artifact file at `outputPath` using `template` as the structure, following `instruction` for schema-specific guidance.
   - **`context` and `rules` are constraints for you, not file content.** They guide what you write but must never be copied into the artifact output (no `<context>`, `<rules>`, or `<project_context>` blocks in the file).
   - Re-run `openspec status --change "<name>" --json` and check `applyRequires` completion before moving to the next artifact.
   - If an artifact's requirements are genuinely unclear, ask the user rather than guessing — but prefer a reasonable decision over stalling.
5. Show final status: `openspec status --change "<name>"`.
6. Summarize what was created and note the change is ready for implementation (see "Applying tasks" below).

If a change with that name already exists, ask whether to continue it or start a new one.

---

## 3. Fast-forwarding through artifact creation

This is **the same CLI sequence as "Proposing a change" above** (`openspec new change`, `openspec status --change "<name>" --json`, the artifact-instructions loop until `applyRequires` are all `done`). The only difference is framing, not mechanics: fast-forwarding means deliberately skipping the intermediate stop-and-review points a more incremental session would pause at, generating every required artifact back-to-back in one go.

Use whichever framing matches the user's intent — "propose this" and "fast-forward this" describe the same operation.

---

## 4. Explore / thinking-mode stance

This is a **stance, not a workflow** — there are no fixed steps, required sequence, or mandatory outputs. Use it when the user wants to think through an idea, investigate a problem, or clarify requirements before (or during) a change, rather than execute one.

**Ground rule: don't implement, don't auto-capture.**
- Never write application code or implement features while in this stance. If the user asks you to implement something, say so and suggest moving to proposing/applying a change first.
- You *may* read files, search the codebase, and investigate freely.
- You *may* create or update OpenSpec artifacts if the user explicitly asks — that's capturing thinking, not implementing.
- When an insight crystallizes, **offer** to capture it; don't capture it unprompted. The user decides.

**Getting context**:
```bash
openspec list --json
```
Run this early to see whether active changes exist, their names/schemas/status, and what the user might already be working on. If the user references a specific change, read its artifacts (`proposal.md`, `design.md`, `tasks.md`, etc.) under `openspec/changes/<name>/` for context.

**No artifact-mutating commands unless the user asks.** Don't run `openspec new`, `openspec instructions`, or edit artifact files as a side effect of exploring — only `openspec list --json` (and reads) are part of the default stance.

**Insight-type → artifact table** — when offering to capture a crystallized insight, this is where it goes:

| Insight Type               | Where to Capture             |
|-----------------------------|-------------------------------|
| New requirement discovered | `specs/<capability>/spec.md` |
| Requirement changed        | `specs/<capability>/spec.md` |
| Design decision made       | `design.md`                  |
| Scope changed               | `proposal.md`                |
| New work identified        | `tasks.md`                   |
| Assumption invalidated      | Relevant artifact            |

---

## 5. Applying (implementing) tasks

Use this to work through an existing change's task list.

**Steps**:

1. **Select the change**: use the name if given; otherwise infer from context, auto-select if exactly one active change exists, or run `openspec list --json` and ask the user to choose if ambiguous. Announce which change is being used.
2. Check status to understand the schema:
   ```bash
   openspec status --change "<name>" --json
   ```
   Note `schemaName` and which artifact holds the tasks (typically `tasks` for the spec-driven schema).
3. Get apply instructions:
   ```bash
   openspec instructions apply --change "<name>" --json
   ```
   This returns `contextFiles` (artifact ID → file paths to read, schema-dependent), progress counts, the task list with status, and a dynamic instruction.
4. **Handle state**:
   - `state: "blocked"` (missing required artifacts) → explain what's missing and stop; the change needs its remaining artifacts created first (see sections 2–3).
   - `state: "all_done"` → congratulate and suggest archiving (see section 6).
   - Otherwise → proceed to implementation.
5. Read every file listed under `contextFiles` before starting.
6. Loop through pending tasks: work one at a time, make minimal focused code changes, then flip its checkbox in the tasks file (`- [ ]` → `- [x]`) immediately after it's done.
7. **Pause conditions** — stop and ask/report rather than guessing when:
   - A task is unclear.
   - Implementation reveals a design issue (suggest updating the relevant artifact instead of pushing through).
   - An error or blocker is encountered.
   - The user interrupts.
8. On completion or pause, report progress ("N/M tasks complete") and, if all done, suggest archiving.

This can be invoked at any point in a change's life — before all artifacts are finished (if a task list already exists), after partial implementation, interleaved with other actions. It is not phase-locked.

---

## 6. Archiving a change

Use this once implementation is finished (or the user wants to close out a change regardless of completeness).

**Steps**:

1. **Select the change**: if no name given, run `openspec list --json` and ask the user to pick from active (non-archived) changes — never guess. Include each candidate's schema if available.
2. **Check artifact completion**:
   ```bash
   openspec status --change "<name>" --json
   ```
   If any artifact's status isn't `done`, warn with the list of incomplete artifacts and ask for confirmation before continuing.
3. **Check task completion**: read the tasks file (typically `tasks.md`) and count `- [ ]` vs `- [x]`. If incomplete tasks exist, warn with the count and ask for confirmation before continuing. If no tasks file exists, skip this check.
4. **Assess delta spec sync state**: look for delta specs at `openspec/changes/<name>/specs/`. If none exist, skip this step. If they exist, diff each against the corresponding main spec at `openspec/specs/<capability>/spec.md` (adds/modifications/removals/renames), show a combined summary, and offer to sync now vs. archive without syncing (or, if already in sync, offer archive/sync-anyway/cancel). Proceed to archive regardless of the sync choice.
5. **Perform the archive**:
   ```bash
   mkdir -p openspec/changes/archive
   mv openspec/changes/<name> openspec/changes/archive/YYYY-MM-DD-<name>
   ```
   `YYYY-MM-DD` is today's date. If a directory already exists at that target path, stop and surface the conflict (suggest renaming the existing archive, removing it if it's a duplicate, or archiving on a different date) — do not overwrite.
6. Show a summary: change name, schema, archive location, spec-sync outcome (synced / sync skipped / no delta specs), and any completeness warnings from steps 2–3.

**None of the completeness checks in steps 2–3 block archiving** — they inform and require confirmation, but the user can always proceed with warnings noted in the summary.
