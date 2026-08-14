# Product Overview

A full-stack recruitment assessment platform for creating, distributing, and evaluating candidate assessments.

Recruiters build assessments from a question bank (multiple-choice, text/essay, and in-browser Java coding challenges), invite candidates via password-protected links, and review/score submissions through a dashboard. Candidates take assessments without a full account — access is via a password-protected invitation token.

## Portals

- **Recruiter / staff portal** — authenticated (JWT), roles `ADMIN` / `RECRUITER`.
- **Candidate portal** — unauthenticated entry via invitation password, short-lived candidate JWT scoped to a single assessment.

## Core Features

### Recruiter / staff side
- Dashboard with pipeline statistics and recent activity feed
- Question bank: MCQ, TEXT, and CODE_SUBMISSION (Java) questions, tagging, grouped questions
- Assessment builder: compose ordered questions, randomisation quotas, preview before publishing
- Candidate invitations (password-protected), duplicate-invite and already-completed guards
- Submission review and manual marking (text/code answers scored by staff; MCQ auto-marked)
- Flagged submissions workflow for disputes/audits, with an audit trail
- Scheduled reminder emails for pending invitations (with send-history logging)
- Staff account management
- AI-assisted features backed by Groq (optional — degrades gracefully if `GROQ_API_KEY` is unset; fails at call time, not startup)

### Candidate side
- Enter an assessment via password-protected invitation link
- Take assessment with question randomisation and submission snapshots (what the candidate saw is preserved)
- Save progress incrementally; submit when done
- In-browser Java code editor (Monaco) with real-time sandboxed execution via Piston
- View results once marking is complete

## Domain Model Summary

| Domain | Key Entities / Concepts |
|--------|--------------------------|
| `question` | `Question` base + `McqQuestion`, `TextQuestion`, `CodeSubmissionQuestion`, `QuestionOption`, `GroupQuestion`/`GroupQuestionMember`, `Difficulty` |
| `assessment` | `Assessment`, `AssessmentQuestion` (ordered), `AssessmentStatus`, `RandomisationQuota` |
| `invitation` | `CandidateInvitation`, `InvitationStatus` — links a candidate to an assessment with a password |
| `take` | `CandidateSubmission`, `CandidateAnswer`, `SubmissionQuestionSnapshot`, `SubmissionStatus` — candidate's in-progress/completed session |
| `marking` | `AnswerScore` — recruiter scores for text/code answers; MCQ auto-marked |
| `flag` | `SubmissionFlag`, `FlagReason`, `SubmissionFlagAudit` — dispute/audit records on submissions |
| `candidate` | `Candidate`, blacklist flag, history of past assessments |
| `reminder` | `ReminderSendLog`/`ReminderSendType` — scheduled email reminders for pending invitations |
| `auth` | `User` (staff, with `Role`), JWT for staff; short-lived scoped token for candidates |
| `staff` | `User`/`Staff` accounts with `Role` (`ADMIN`, `RECRUITER`) |
| `dashboard` | `DashboardStats`, `PipelineStats`, `ActivityEvent` |
| `ai` | Groq-backed AI assistance (`AiService`, `GroqClient`) — optional, additive feature |

## Development Methodology

This project is spec-driven. Two spec systems coexist:
- **OpenSpec** (`openspec/`) — the primary, established workflow for this codebase; ~75 capability specs live in `openspec/specs/`, and every change is proposed/archived under `openspec/changes/`. Use `/openspec-new-change` or `/opsx:new` to start a structured change here.
- **Kiro specs** (`.kiro/specs/`) — used for newer feature work initiated through Kiro's spec workflow (e.g. `ai-integration-foundation`).

When adding a feature, check whether it fits an existing OpenSpec capability before starting a new spec of either kind.
