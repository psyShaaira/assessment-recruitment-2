# AI Auto-Flagging — Requirements

## Overview

Automatically analyze candidate submissions at completion time using Groq AI to detect potential integrity issues (timing anomalies, AI-generated content, suspicious behavior). Produces risk assessments that either auto-create flags for high-confidence detections or surface medium-confidence concerns for recruiter review — without blocking the submission flow.

## Actors

- **System** — the automated AI analysis process (runs asynchronously post-submission)
- **Recruiter / Admin** — reviews AI-generated flags and risk assessments, takes action
- **Candidate** — submits assessment (unaware of background analysis)

## Functional Requirements

### FR-1: Post-Submission AI Analysis Trigger

- FR-1.1: When a candidate submission transitions to `SUBMITTED` or `AUTO_SUBMITTED`, the system SHALL asynchronously trigger an AI integrity analysis.
- FR-1.2: The analysis MUST NOT block or delay the `submitAssessment()` response to the candidate.
- FR-1.3: If the AI service is unavailable (missing API key, timeout, rate limit), the system SHALL log a warning and skip analysis without affecting the submission.
- FR-1.4: The system SHALL NOT analyze submissions that already have an open flag (status `FLAGGED`, `UNDER_REVIEW`, or `ACTION_REQUIRED`).

### FR-2: Timing Anomaly Detection

- FR-2.1: The system SHALL calculate actual completion time (`submittedAt - startedAt`) and compare against `Assessment.timeLimitMinutes`.
- FR-2.2: The system SHALL analyze per-answer `savedAt` timestamps to detect burst-save patterns (all answers saved within an implausibly short window).
- FR-2.3: The system SHALL consider question count, difficulty distribution, and question types when evaluating whether completion speed is plausible.

### FR-3: AI-Generated Content Detection

- FR-3.1: For TEXT and CODE_SUBMISSION answers, the system SHALL send answer content to Groq for analysis of AI-generation indicators.
- FR-3.2: The prompt MUST NOT include candidate PII (name, email, ID) — only question text, answer content, and timing metadata.
- FR-3.3: The system SHALL evaluate indicators such as: formulaic structure, unnaturally uniform quality across answers, generic phrasing, and excessive hedging language.

### FR-4: Suspicious Behavior Detection

- FR-4.1: The system SHALL detect patterns where answer quality is inconsistent with completion speed (e.g., complex code written in seconds).
- FR-4.2: The system SHALL flag cases where all answers arrive in a single rapid burst after an extended idle period.

### FR-5: Risk Assessment Output

- FR-5.1: The AI analysis SHALL produce a structured result: `{risk: HIGH | MEDIUM | LOW, reasons: [FlagReason], rationale: String, confidence: float}`.
- FR-5.2: HIGH risk → system SHALL automatically create a `SubmissionFlag` via `SubmissionFlagService.createFlag()` with the detected `FlagReason` and a system actor (`actorUsername = "SYSTEM"`).
- FR-5.3: MEDIUM risk → system SHALL persist a `FlaggingRiskAssessment` record for recruiter review (visible on dashboard, no auto-flag).
- FR-5.4: LOW risk → system SHALL persist the risk assessment record (for audit) but take no further action.
- FR-5.5: If an open flag already exists when the system attempts to auto-flag, it SHALL log the conflict and store the assessment without creating a duplicate flag.

### FR-6: Risk Assessment Storage

- FR-6.1: Every AI analysis result SHALL be persisted in a `flagging_risk_assessments` table, regardless of risk level.
- FR-6.2: Each record SHALL include: `submissionId`, `risk`, `reasons` (JSON array), `rationale`, `confidence`, `analyzedAt`, `promptVersion`.
- FR-6.3: Only one risk assessment SHALL exist per submission (upsert on re-analysis).

### FR-7: Recruiter Visibility

- FR-7.1: Recruiters SHALL be able to view AI risk assessments for any submission via a REST endpoint.
- FR-7.2: The submission list/detail view SHALL display an AI risk indicator when a MEDIUM or HIGH assessment exists.
- FR-7.3: The flag detail view SHALL indicate when a flag was auto-created by the system (vs manually created by staff).

### FR-8: Cross-Submission Similarity (Algorithmic — No AI)

- FR-8.1: After AI analysis, the system SHALL compare TEXT/CODE answers against other submissions for the same assessment.
- FR-8.2: Similarity detection SHALL use word-level Jaccard similarity on normalized text.
- FR-8.3: If similarity exceeds a configurable threshold (default 0.8), the system SHALL flag as `COPIED_ANSWERS`.
- FR-8.4: This check is deterministic (no AI call) and runs independently of the Groq analysis.

## Non-Functional Requirements

### NFR-1: Performance
- AI analysis MUST complete within 30 seconds per submission (Groq timeout).
- Cross-submission similarity MUST complete within 10 seconds for up to 100 prior submissions.

### NFR-2: Reliability
- All AI failures are non-fatal — the system degrades gracefully.
- Risk assessments are persisted even on partial failure (e.g., AI succeeds but similarity check fails).

### NFR-3: Security
- No candidate PII in AI prompts.
- Risk assessment endpoints require `ROLE_ADMIN` or `ROLE_RECRUITER`.
- System actor UUID is a well-known constant, not a real staff account.

### NFR-4: Configurability
- Similarity threshold configurable via `application.yaml` (`flagging.similarity-threshold`).
- AI flagging can be disabled entirely via config (`flagging.ai-enabled: true/false`).
- Confidence thresholds for HIGH/MEDIUM configurable (`flagging.high-threshold: 0.8`, `flagging.medium-threshold: 0.5`).

## Out of Scope

- Real-time monitoring during assessment-taking (only post-submission analysis).
- Browser-level proctoring (tab switches, copy-paste events) — no client-side signals exist.
- Candidate notification of flags (flags are internal to recruiters).
- Appeal/dispute flow for AI-generated flags (use existing flag resolution workflow).
