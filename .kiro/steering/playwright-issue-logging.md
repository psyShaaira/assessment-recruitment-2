# Playwright Issue Logging

Convention for capturing issues, tool quirks, and workarounds discovered during Playwright-based testing or debugging sessions, so they aren't lost between sessions and can be reviewed later.

This document is only relevant while actively running a Playwright-based testing/debugging session (manual or agent-driven) — it is not auto-included in every context.

## Files

- **Pending_Issues_File**: `.kiro/pw-pending.txt` — a plain-text scratch file that accumulates issue entries during the current session. Append to it as issues are found; do not wait until the end of the session to write anything down.
- **Playwright_Issue_Log**: `.kiro/playwright-issues.md` — the persistent Markdown log that issues eventually land in. A separate hook (`playwright-issue-flush`, triggered on `agentStop`) reads `.kiro/pw-pending.txt`, appends its contents to this log under a timestamped heading, and clears the pending file. Agents should not write directly to `.kiro/playwright-issues.md` — always go through the pending file.

## When to log an entry

The moment a Playwright-based testing session surfaces any of the following, append an entry to the Pending_Issues_File immediately, using a file-append action (never overwrite the file, and never wait until the end of the session to batch-write findings):

- A reproducible issue or bug (application or environment).
- A Playwright/MCP tool quirk (a tool behaving unexpectedly, an unsupported selector syntax, a hook that doesn't fire for certain tools, etc.).
- A workaround or reliable pattern discovered while working around one of the above.

## Entry format

Each append should read like an entry from the existing `.claude/playwright-issues.md` log — that format has already proven useful in this repo. Use whichever of the two shapes below fits best; both are acceptable in the same file.

**Structured issue entry** (preferred when there's a concrete tool/input/error/fix):

```markdown
**Issue: <short title>**
- Tool: <tool or command name>
- Input: <the input/target/args that triggered it>
- Error: <the error message or observed bad behavior>
- Fix: <the fix or workaround, if found>
```

**Looser narrative/pattern entry** (fine for general observations that don't map cleanly to tool/input/error):

```markdown
### Pattern: <short title>
- **Type**: <category, e.g. Docker / environment issue, Playwright MCP quirk>
- **Tool / Command**: <tool or command name>
- **Input**: <input, if applicable>
- **Error**: <observed behavior>
- **Fix**: <fix or workaround>
```

Only use issue-level headings like the ones above (`**Issue: ...**`, `### Pattern: ...`, or a `### Session: <name> (<date>)` sub-heading if grouping several entries from the same session). Do **not** add your own top-level (`#`/`##`) heading when appending to the Pending_Issues_File.

## Flush heading format

The Playwright_Flush_Hook wraps whatever is in the Pending_Issues_File under a top-level heading of the form:

```markdown
## <timestamp>

<verbatim contents of .kiro/pw-pending.txt>

---
```

The hook supplies this `## <timestamp>` heading and the trailing `---` separator when it moves content into `.kiro/playwright-issues.md`. Because the hook always adds this wrapper, entries appended to the pending file should stick to issue-level sub-headings (as shown above) and never include their own top-level heading — otherwise the log ends up with duplicate/nested top-level headings.
