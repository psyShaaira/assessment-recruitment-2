# Playwright Issue Log

## 2026-06-10 14:32:00

**Issue: Locator times out on dynamic candidate list**
- Tool: playwright-mcp click
- Input: role=button[name="Invite Candidate"] on /candidates page
- Error: Timeout 5000ms exceeded waiting for locator to become visible
- Fix: wait for network idle before interacting, or use getByRole with exact text match

---
## 2026-06-10 15:47:00

**Issue: Verification checkpoint sample entry**
- Tool: manual-test (task 3 checkpoint)
- Input: n/a - sample content written to confirm flush behavior
- Error: n/a
- Fix: n/a - this entry exists only to verify the playwright-issue-flush hook appends and clears correctly

---
