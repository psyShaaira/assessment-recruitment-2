# Implementation Plan: Kiro Dev Tooling Parity

## Overview

This plan creates two Kiro-native steering documents and one Kiro hook, replacing the Claude-Code-specific `.claude/` tooling for Playwright issue logging and OpenSpec workflow guidance. There is no application code to write — all tasks produce configuration/documentation artifacts (steering markdown files and a hook definition created via Kiro's hook tooling). No property-based tests apply (per design.md, this feature has no algorithmic logic); verification is manual/example-based.

## Tasks

- [x] 1. Create the Playwright issue logging steering document
  - Create `.kiro/steering/playwright-issue-logging.md`
  - Document the Pending_Issues_File path (`.kiro/pw-pending.txt`) and Playwright_Issue_Log path (`.kiro/playwright-issues.md`)
  - Document the entry format for appending an issue to the pending file (title/tool/input/error/fix template, matching the legacy `.claude/playwright-issues.md` structure)
  - Document the instruction for agents to append an entry immediately when a reproducible issue, tool quirk, or workaround is found during a Playwright-based testing session
  - Document the flush heading format (`## <timestamp>`) so agents know not to add their own top-level heading when appending to the pending file
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Create the Playwright issue flush hook
  - Use the hook creation tool to define an `agentStop` event hook (`askAgent` action, id `playwright-issue-flush`) that: checks whether `.kiro/pw-pending.txt` exists and is non-empty; if empty/missing, does nothing; otherwise creates `.kiro/playwright-issues.md` with a `# Playwright Issue Log` heading if it doesn't exist, appends a `## <timestamp>` section containing the pending file's full contents followed by a `---` separator, then clears the pending file
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 3. Checkpoint - verify Playwright issue flush behavior
  - Manually create `.kiro/pw-pending.txt` with sample issue text and confirm a subsequent agent turn end appends a correctly formatted, timestamped section to `.kiro/playwright-issues.md` and clears the pending file
  - Confirm that ending a turn with no pending file present leaves `.kiro/playwright-issues.md` unchanged
  - Confirm that if `.kiro/playwright-issues.md` does not exist, it is created with the correct top-level heading on first flush
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Create the consolidated OpenSpec workflow steering document
  - Create `.kiro/steering/openspec-workflow.md`
  - Document starting a new change: `openspec new change "<name>" [--schema <name>]`, `openspec status --change "<name>"`, `openspec instructions <first-artifact-id> --change "<name>"`, stopping after showing the first artifact template
  - Document proposing a change (all artifacts in one step): `openspec new change`, `openspec status --change "<name>" --json`, the artifact-instructions loop until `applyRequires` are all `done`, and that `context`/`rules` fields are constraints for the agent, not file content
  - Document fast-forwarding through artifact creation as the same CLI sequence as proposing, framed as skipping intermediate review stops
  - Document the explore/thinking-mode stance: `openspec list --json` for context, no artifact-mutating commands unless requested, the "don't implement, don't auto-capture" rule, and the insight-type-to-artifact table
  - Document applying (implementing) tasks: `openspec status --change "<name>" --json`, `openspec instructions apply --change "<name>" --json`, reading `contextFiles`, editing task checkboxes, and the blocked/all_done/in-progress pause conditions
  - Document archiving a change: `openspec list --json` for selection, `openspec status --change "<name>" --json` for artifact completion, tasks-file checkbox counting, delta spec diffing, and moving the change directory to `openspec/changes/archive/YYYY-MM-DD-<name>/`
  - Ensure the document does not restate or contradict the existing `structure.md`/`product.md` guidance on choosing between OpenSpec and a Kiro spec
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 5. Final checkpoint - review documentation completeness
  - Re-read `playwright-issue-logging.md` against Requirement 1's acceptance criteria and `openspec-workflow.md` against Requirement 3's acceptance criteria, confirming each is addressed
  - Confirm design.md's documented exclusions (prompt/usage/cost logging, dev-server launch config) remain accurate and no code was added for either
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- No tasks are marked optional (`*`) — this feature has no automated test suite; verification is manual and folded into the checkpoint tasks above.
- All file paths and CLI command references trace back to design.md's Components and Interfaces section.
- Once all tasks are complete, this workflow is finished. Do not implement further features as part of this workflow — open `tasks.md` and click "Start task" to execute any task.
