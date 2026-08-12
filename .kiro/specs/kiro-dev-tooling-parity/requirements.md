# Requirements Document

## Introduction

This repository includes a `.claude/` directory with Claude-Code-specific developer support tooling: a Playwright manual-testing issue logger (`playwright-issues.ps1` + a `Stop` hook that flushes to `.claude/playwright-issues.md`), a prompt/usage cost logger (`log-prompt.sh` + `log-usage.sh`), a dev-server launch config (`.claude/launch.json`), and six `opsx` slash-commands (paired with matching skills) that wrap the `openspec` CLI to drive this repo's OpenSpec change-proposal workflow.

This feature builds Kiro-native equivalents of the capabilities that have a meaningful Kiro-native mapping, using Kiro's own primitives (steering documents and hooks created via Kiro's hook tooling) rather than porting Claude's `.claude/` files verbatim. Two capabilities are explicitly out of scope because Kiro has no primitive that can support them, and that exclusion is itself a documented outcome of this feature:

- **Prompt/usage/cost logging** — Kiro's hook system does not expose a session transcript or token-usage payload to hook callbacks, so the USD-cost/token-count logging Claude performs cannot be computed by a Kiro hook.
- **Dev-server launch config** — Kiro has no launch-config primitive equivalent to `.claude/launch.json`.

In scope:

1. A Kiro-native replacement for the Playwright manual-testing issue logger: a steering document describing the pending-issue-file convention, plus a hook that flushes pending issues into a persistent log at the end of an agent turn.
2. A single consolidated Kiro steering document that documents how to drive the repo's OpenSpec workflow (new change, propose, fast-forward, explore, apply, archive) via the `openspec` CLI, replacing the six `opsx` slash-commands/skills.

## Glossary

- **Playwright_Issue_Steering_Doc**: The Kiro steering document (`.kiro/steering/playwright-issue-logging.md`) that documents the convention for recording issues found during Playwright-based testing/debugging sessions.
- **Pending_Issues_File**: A file that accumulates issue entries during an active session, written to before being flushed into the persistent log.
- **Playwright_Issue_Log**: The persistent Markdown log at `.kiro/playwright-issues.md` that accumulates flushed issue entries across sessions.
- **Playwright_Flush_Hook**: The Kiro hook, triggered on the `agentStop` event, that flushes the contents of the Pending_Issues_File into the Playwright_Issue_Log.
- **OpenSpec_Workflow_Steering_Doc**: The single consolidated Kiro steering document (`.kiro/steering/openspec-workflow.md`) that documents how to perform each OpenSpec workflow operation (new, propose, fast-forward, explore, apply, archive) using the `openspec` CLI.
- **OpenSpec_CLI**: The `openspec` command-line tool already used by this repository's `openspec/` workflow (commands include `new`, `status`, `instructions`, `list`).
- **Design_Document**: The `design.md` artifact produced for this feature under `.kiro/specs/kiro-dev-tooling-parity/`.

## Requirements

### Requirement 1: Playwright Issue Logging Convention

**User Story:** As a developer using Kiro to run Playwright-based testing or debugging sessions, I want a documented convention for recording issues encountered during those sessions, so that issues discovered during manual or agent-driven testing are captured for later review, matching the capability Claude Code users have today.

#### Acceptance Criteria

1. THE System SHALL provide a Playwright_Issue_Steering_Doc.
2. THE Playwright_Issue_Steering_Doc SHALL specify the file path of the Pending_Issues_File and the file path of the Playwright_Issue_Log.
3. THE Playwright_Issue_Steering_Doc SHALL specify the entry format used when appending an issue to the Pending_Issues_File.
4. WHEN an agent identifies an issue during a Playwright-based testing session, THE Playwright_Issue_Steering_Doc SHALL instruct the agent to append an entry describing the issue to the Pending_Issues_File.
5. THE Playwright_Issue_Steering_Doc SHALL describe the heading format used when an entry is flushed into the Playwright_Issue_Log, including a session timestamp.

### Requirement 2: Playwright Issue Flush Hook

**User Story:** As a developer, I want pending Playwright issue notes to be automatically flushed into a persistent log at the end of an agent turn, so that issue notes are not lost between sessions and do not require a manual flush step.

#### Acceptance Criteria

1. THE System SHALL provide a Playwright_Flush_Hook configured on the `agentStop` event.
2. WHEN the Pending_Issues_File exists and is non-empty at agent stop, THE Playwright_Flush_Hook SHALL append its contents to the Playwright_Issue_Log under a heading that includes the current timestamp.
3. WHEN the Playwright_Flush_Hook has appended the contents of the Pending_Issues_File to the Playwright_Issue_Log, THE Playwright_Flush_Hook SHALL clear the Pending_Issues_File.
4. IF the Pending_Issues_File does not exist or is empty at agent stop, THEN THE Playwright_Flush_Hook SHALL leave the Playwright_Issue_Log unchanged.
5. WHEN the Playwright_Issue_Log does not yet exist at the time of a flush, THE Playwright_Flush_Hook SHALL create it before appending the flushed entry.

### Requirement 3: OpenSpec Workflow Steering Documentation

**User Story:** As a developer using Kiro on this repository, I want a consolidated steering document that explains how to drive the OpenSpec change-proposal workflow through the `openspec` CLI, so that I get guidance equivalent to Claude Code's `opsx` slash-commands without requiring a slash-command system in Kiro.

#### Acceptance Criteria

1. THE System SHALL provide a single OpenSpec_Workflow_Steering_Doc covering all OpenSpec workflow operations.
2. THE OpenSpec_Workflow_Steering_Doc SHALL describe how to start a new OpenSpec change, including the underlying OpenSpec_CLI command.
3. THE OpenSpec_Workflow_Steering_Doc SHALL describe how to generate all artifacts for a change in a single pass, including the underlying OpenSpec_CLI commands and the artifact creation loop.
4. THE OpenSpec_Workflow_Steering_Doc SHALL describe how to fast-forward through artifact creation up to the point implementation can begin, including the underlying OpenSpec_CLI commands.
5. THE OpenSpec_Workflow_Steering_Doc SHALL describe the explore/thinking-mode stance for investigating ideas before committing to a change, including when OpenSpec artifacts should and should not be created during that stance.
6. THE OpenSpec_Workflow_Steering_Doc SHALL describe how to implement tasks from an existing change, including the underlying OpenSpec_CLI commands used to select a change and retrieve its task list.
7. THE OpenSpec_Workflow_Steering_Doc SHALL describe how to archive a completed change, including the underlying OpenSpec_CLI commands and the pre-archive completeness checks to perform.
8. WHERE the OpenSpec_Workflow_Steering_Doc's guidance overlaps with existing Kiro steering describing when to prefer OpenSpec versus a Kiro spec, THE OpenSpec_Workflow_Steering_Doc SHALL remain consistent with that existing steering.

### Requirement 4: Documented Scope Exclusions

**User Story:** As a developer reviewing this tooling-parity work, I want the reasons for excluding certain Claude-Code capabilities to be explicitly documented, so future maintainers understand the gap without re-investigating it.

#### Acceptance Criteria

1. THE Design_Document SHALL state that prompt, usage, and cost logging is out of scope, with the reason that Kiro hooks do not expose a session transcript or token-usage payload to hook callbacks.
2. THE Design_Document SHALL state that a dev-server launch config equivalent to `.claude/launch.json` is out of scope, with the reason that Kiro has no launch-config primitive.
