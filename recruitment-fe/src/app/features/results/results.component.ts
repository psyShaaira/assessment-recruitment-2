import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MarkingService } from '../../core/marking/marking.service';
import { ResultQuestion, ResultSummary, SubmissionSummary } from '../../core/marking/marking.model';
import { FlagService } from '../../core/flag/flag.service';
import { FlagAuditEntry, FlagListItem, FlagReason, FlagResponse, FlagStatus, RiskAssessmentResponse } from '../../core/flag/flag.model';
import { ReminderService } from '../../core/reminder/reminder.service';
import { ReminderSendLogDto } from '../../core/reminder/reminder.model';
import { HttpErrorResponse } from '@angular/common/http';
import { FeedbackService } from '../../core/feedback/feedback.service';
import { FeedbackReportResponse } from '../../core/feedback/feedback.model';
import { FeedbackEmailService } from '../../core/feedback-email/feedback-email.service';
import { FeedbackEmailSendLogEntry } from '../../core/feedback-email/feedback-email.model';
import { Subscription, timeout } from 'rxjs';
import { CodeEditorComponent } from '../../shared/code-editor/code-editor.component';
import { AiMarkingService } from '../../core/ai-marking/ai-marking.service';
import { AiMarkingSuggestionResponse, AiSuggestionErrorKind } from '../../core/ai-marking/ai-marking.model';

/**
 * True if the answer content is non-null and contains at least one
 * non-whitespace character.
 */
export function hasAnswerContent(answer: string | null): boolean {
  return answer != null && answer.trim().length > 0;
}

/**
 * True if a question is eligible for AI-assisted marking: its type is
 * TEXT or CODE_SUBMISSION and its candidate answer has content. GROUP
 * questions are never eligible themselves (their type excludes them) —
 * their individually-markable sub-questions are checked separately via
 * this same predicate.
 */
export function isAiEligibleQuestion(q: ResultQuestion): boolean {
  return (
    (q.questionType === 'TEXT' || q.questionType === 'CODE_SUBMISSION') &&
    hasAnswerContent(q.candidateAnswer)
  );
}

@Component({
  selector: 'app-results',
  imports: [CodeEditorComponent],
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Results & Evaluation</h1>
          <span class="page-sub">{{ filteredSubmissions().length }} submissions</span>
        </div>
        <div class="header-filters">
          <select class="assessment-select" [value]="assessmentFilter()" (change)="assessmentFilter.set($any($event.target).value)">
            <option value="">All Assessments</option>
            @for (a of availableAssessments(); track a.assessmentId) {
              <option [value]="a.assessmentId">{{ a.assessmentTitle }}</option>
            }
          </select>
          @for (f of statusFilters; track f.value) {
            <button class="filter-chip" [class.active]="statusFilter() === f.value" (click)="statusFilter.set(f.value)">{{ f.label }}</button>
          }
        </div>
      </div>

      <div class="split-layout">
        <!-- Left panel: submission list -->
        <div class="submissions-panel">
          @if (loadingList()) {
            <div class="empty-panel">Loading…</div>
          } @else if (filteredSubmissions().length === 0) {
            <div class="empty-panel">No submissions.</div>
          } @else {
            @for (g of groupedSubmissions(); track g.assessmentId) {
              <div class="group-header">
                <span class="group-title">{{ g.assessmentTitle }}</span>
                <span class="group-count">{{ g.items.length }}</span>
              </div>
              @for (s of g.items; track s.invitationId) {
                <div class="submission-item"
                     [class.active]="selectedSummary()?.invitationId === s.invitationId"
                     (click)="selectSubmission(s)">
                  <div class="sub-avatar" [style.background]="avatarColor(s.candidateName)">{{ initials(s.candidateName) }}</div>
                  <div class="sub-info">
                    <div class="sub-name-row">
                      <span class="sub-name">{{ s.candidateName }}</span>
                      @if (s.candidateBlacklisted) {
                        <span class="sub-bl-symbol" title="Blacklisted">⊘</span>
                      }
                      <span class="sub-score">{{ scorePercent(s) }}</span>
                    </div>
                    <div class="sub-tags">
                      <span class="sub-status" [class]="statusClass(s)">{{ statusLabel(s) }}</span>
                      @if ((s.status === 'SUBMITTED' || s.status === 'AUTO_SUBMITTED') && s.markedCount < s.totalAnswers) {
                        <span class="pending-badge">⏳ Pending</span>
                      }
                      @if (s.flagStatus === 'FLAGGED' || s.flagStatus === 'UNDER_REVIEW') {
                        <span class="flag-badge">⚑ Flagged</span>
                      }
                      @if (s.flagStatus === 'ACTION_REQUIRED') {
                        <span class="flag-badge flag-badge-action">⚠ Action Required</span>
                      }
                      @if (s.aiRiskLevel === 'HIGH') {
                        <span class="ai-risk-badge ai-risk-high">AI: High Risk</span>
                      } @else if (s.aiRiskLevel === 'MEDIUM') {
                        <span class="ai-risk-badge ai-risk-medium">AI: Medium Risk</span>
                      }
                    </div>
                    <div class="sub-meta-row">
                      <span>{{ formatDate(s.submittedAt) }}</span>
                      @if (s.status !== 'NOT_STARTED' && s.totalAnswers > 0) {
                        <span>{{ s.markedCount }}/{{ s.totalAnswers }} marked</span>
                      }
                    </div>
                    @if (s.status !== 'NOT_STARTED' && s.totalAnswers > 0) {
                      <div class="sub-progress-bar">
                        <div class="sub-progress-fill"
                             [class.fill-done]="s.markedCount >= s.totalAnswers"
                             [style.width.%]="markedPercent(s)"></div>
                      </div>
                    }
                  </div>
                </div>
              }
            }
          }
        </div>

        <!-- Right panel: result detail -->
        <div class="detail-panel">
          @if (!result()) {
            @if (loadingResult()) {
              <div class="no-selection">Loading result…</div>
            } @else if (selectedSummary()?.status === 'NOT_STARTED') {
              <!-- Invited but hasn't started yet -->
              <div class="detail-scroll">
                <div class="detail-header">
                  <div class="detail-avatar" [style.background]="avatarColor(selectedSummary()!.candidateName)">{{ initials(selectedSummary()!.candidateName) }}</div>
                  <div class="detail-candidate-info">
                    <span class="detail-name">{{ selectedSummary()!.candidateName }}</span>
                    <span class="detail-assessment" style="margin-top:4px">
                      <span class="sub-status status-not-started">Not Started</span>
                    </span>
                    <span class="detail-submitted">Invited — hasn't opened the assessment yet</span>
                  </div>
                </div>
                <!-- Reminder section for not-started candidates -->
                <div class="reminder-section">
                  @if (!showReminderConfirm()) {
                    <button class="btn-reminder" (click)="showReminderConfirm.set(true)" [disabled]="reminderSending()">✉ Send Reminder</button>
                  } @else {
                    <div class="reminder-confirm">
                      <span class="reminder-confirm-text">Send a reminder email to this candidate?</span>
                      <button class="save-btn" (click)="sendReminder()" [disabled]="reminderSending()">{{ reminderSending() ? 'Sending…' : 'Confirm' }}</button>
                      <button class="save-btn secondary" (click)="showReminderConfirm.set(false)">Cancel</button>
                    </div>
                  }
                  @if (reminderSuccess()) {
                    <span class="reminder-toast">✓ Reminder sent</span>
                  }
                </div>
                <!-- Reminder history -->
                <div class="audit-section">
                  <div class="audit-title">Reminder History</div>
                  @if (reminderHistory().length === 0) {
                    <div class="audit-empty">No reminders sent yet</div>
                  } @else {
                    @for (r of reminderHistory(); track r.id) {
                      <div class="audit-entry">
                        <span class="audit-action">
                          <span class="reminder-type-badge" [class.type-auto]="r.sendType === 'AUTOMATED'">{{ r.sendType === 'AUTOMATED' ? 'Automated' : 'Manual' }}</span>
                          {{ r.sentBy ? 'by recruiter' : 'by system' }}
                        </span>
                        <span class="audit-meta">{{ formatDate(r.sentAt) }}</span>
                      </div>
                    }
                  }
                </div>
              </div>
            } @else {
              <div class="no-selection">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8"/>
                </svg>
                <span>Select a submission to review</span>
              </div>
            }
          } @else {
            <div class="detail-scroll">
              <!-- Header -->
              <div class="detail-header">
                <div class="detail-avatar" [style.background]="avatarColor(result()!.candidateName)">{{ initials(result()!.candidateName) }}</div>
                <div class="detail-candidate-info">
                  <div class="detail-name-row">
                    <span class="detail-name">{{ result()!.candidateName }}</span>
                    @if (selectedSummary()?.candidateBlacklisted) {
                      <span class="detail-bl-tag">Blacklisted</span>
                    }
                  </div>
                  <span class="detail-assessment">{{ result()!.assessmentTitle }}</span>
                  <span class="detail-submitted">Submitted: {{ formatDate(result()!.submittedAt) }}</span>
                </div>
                <div class="detail-score-block">
                  <div class="score-ring">
                    <svg viewBox="0 0 80 80" width="86" height="86">
                      <defs>
                        <linearGradient id="scoreGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                          <stop offset="0%" stop-color="var(--accent)"/>
                          <stop offset="100%" stop-color="var(--accent-2)"/>
                        </linearGradient>
                      </defs>
                      <circle cx="40" cy="40" r="34" class="ring-track"/>
                      <circle cx="40" cy="40" r="34" class="ring-fill"
                              stroke="url(#scoreGrad)"
                              [style.stroke-dasharray]="ringDash()"/>
                    </svg>
                    <div class="ring-center">
                      @if (result()!.markingStatus === 'FULLY_MARKED') {
                        <span class="ring-percent">{{ resultPercent() }}%</span>
                        <span class="ring-pts">{{ result()!.totalScore }}/{{ result()!.maxScore }}</span>
                      } @else {
                        <span class="ring-percent ring-pending">—</span>
                        <span class="ring-pts">awaiting marks</span>
                      }
                    </div>
                  </div>
                  <span class="marking-badge" [class.badge-done]="result()!.markingStatus === 'FULLY_MARKED'" [class.badge-pending]="result()!.markingStatus === 'PENDING_REVIEW'">
                    {{ result()!.markingStatus === 'FULLY_MARKED' ? '✓ Fully Marked' : '⏳ Pending Review' }}
                  </span>
                  <div class="answered-stat">{{ markedQuestionCount() }}/{{ totalQuestionCount() }} questions marked</div>
                  @if (activeFlag()) {
                    <span class="flag-badge-detail">⚑ {{ activeFlag()!.status === 'FLAGGED' ? 'Flagged' : activeFlag()!.status === 'ACTION_REQUIRED' ? 'Action Required' : 'Under Review' }}</span>
                    @if (activeFlag()!.createdBy === 'SYSTEM') {
                      <span class="ai-flagged-badge">🤖 AI Flagged</span>
                    }
                  }
                  @if (result()!.aiRiskLevel === 'HIGH' || result()!.aiRiskLevel === 'MEDIUM') {
                    <div class="ai-risk-tooltip-wrapper">
                      <span class="ai-risk-badge-detail"
                            [class.ai-risk-high]="result()!.aiRiskLevel === 'HIGH'"
                            [class.ai-risk-medium]="result()!.aiRiskLevel === 'MEDIUM'"
                            (click)="toggleRiskTooltip()"
                            style="cursor: pointer;">
                        AI: {{ result()!.aiRiskLevel === 'HIGH' ? 'High' : 'Medium' }} Risk
                        <span class="tooltip-arrow">{{ riskTooltipOpen() ? '▴' : '▾' }}</span>
                      </span>
                      @if (riskTooltipOpen()) {
                        <div class="ai-risk-tooltip">
                          @if (riskAssessmentLoading()) {
                            <div class="tooltip-loading"><span class="loading-dot"></span> Loading…</div>
                          } @else if (riskAssessment()) {
                            <div class="tooltip-row">
                              <span class="tooltip-label">Confidence:</span>
                              <span class="tooltip-value">{{ (riskAssessment()!.confidence * 100).toFixed(0) }}%</span>
                            </div>
                            @if (riskReasonsFormatted()) {
                              <div class="tooltip-row">
                                <span class="tooltip-label">Reasons:</span>
                                <span class="tooltip-value">{{ riskReasonsFormatted() }}</span>
                              </div>
                            }
                            <div class="tooltip-rationale">{{ riskAssessment()!.rationale }}</div>
                            <div class="tooltip-meta">Analyzed: {{ formatDateTime(riskAssessment()!.analyzedAt) }}</div>
                          } @else {
                            <div class="tooltip-error">Could not load details.</div>
                          }
                        </div>
                      }
                    </div>
                  }
                </div>
              </div>

              <!-- Flag controls -->
              <div class="flag-section">
                @if (!activeFlag()) {
                  @if (!showFlagForm()) {
                    <button class="btn-flag" (click)="showFlagForm.set(true)">⚑ Flag Submission</button>
                  } @else {
                    <div class="flag-form">
                      <select class="field-select-sm" [value]="flagReason()" (change)="flagReason.set($any($event.target).value)">
                        <option value="">Select reason…</option>
                        <option value="COPIED_ANSWERS">Copied Answers</option>
                        <option value="TIMING_ANOMALY">Timing Anomaly</option>
                        <option value="AI_GENERATED_CONTENT">AI-Generated Content</option>
                        <option value="SUSPICIOUS_BEHAVIOUR">Suspicious Behaviour</option>
                        <option value="OTHER">Other</option>
                      </select>
                      <button class="save-btn" (click)="submitFlag()" [disabled]="!flagReason() || flagSaving()">Submit Flag</button>
                      <button class="save-btn secondary" (click)="showFlagForm.set(false)">Cancel</button>
                    </div>
                  }
                } @else {
                  <div class="flag-transition">
                    @if (activeFlag()!.status === 'FLAGGED') {
                      <button class="btn-flag-action" (click)="transitionFlag('UNDER_REVIEW', null)">Mark Under Review</button>
                    }
                    @if (activeFlag()!.status === 'UNDER_REVIEW') {
                      @if (!showResolveForm()) {
                        <button class="btn-flag-action resolve" (click)="showResolveForm.set('RESOLVED')">Resolve</button>
                        <button class="btn-flag-action dismiss" (click)="showResolveForm.set('DISMISSED')">Dismiss</button>
                      } @else {
                        <div class="flag-form">
                          <input type="text" class="field-input-sm" [value]="resolveNotes()"
                                 (input)="resolveNotes.set($any($event.target).value)"
                                 placeholder="Resolution notes (required)…" />
                          <button class="save-btn" (click)="transitionFlag(showResolveForm()!, resolveNotes())"
                                  [disabled]="!resolveNotes().trim() || flagSaving()">
                            Confirm {{ showResolveForm() }}
                          </button>
                          <button class="save-btn secondary" (click)="showResolveForm.set(null)">Cancel</button>
                        </div>
                      }
                    }
                  </div>
                }
              </div>

              <!-- Flag audit trail -->
              @if (auditTrail().length > 0) {
                <div class="audit-section">
                  <div class="audit-title">Flag Activity</div>
                  @for (entry of auditTrail(); track entry.id) {
                    <div class="audit-entry">
                      <span class="audit-action">{{ entry.action === 'CREATED' ? 'Flagged' : entry.fromStatus + ' → ' + entry.toStatus }}</span>
                      @if (entry.action === 'CREATED' && entry.actorUsername === 'SYSTEM') {
                        <span class="ai-flagged-badge">🤖 AI Flagged</span>
                      }
                      <span class="audit-meta">by {{ entry.actorUsername }} · {{ formatDate(entry.occurredAt) }}</span>
                    </div>
                  }
                </div>
              }

              <!-- Reminder section -->
              @if (selectedSummary()!.status !== 'SUBMITTED' && selectedSummary()!.status !== 'AUTO_SUBMITTED') {
                <div class="reminder-section">
                  @if (!showReminderConfirm()) {
                    <button class="btn-reminder" (click)="showReminderConfirm.set(true)" [disabled]="reminderSending()">
                      ✉ Send Reminder
                    </button>
                  } @else {
                    <div class="reminder-confirm">
                      <span class="reminder-confirm-text">Send a reminder email to this candidate?</span>
                      <button class="save-btn" (click)="sendReminder()" [disabled]="reminderSending()">
                        {{ reminderSending() ? 'Sending…' : 'Confirm' }}
                      </button>
                      <button class="save-btn secondary" (click)="showReminderConfirm.set(false)">Cancel</button>
                    </div>
                  }
                  @if (reminderSuccess()) {
                    <span class="reminder-toast">✓ Reminder sent</span>
                  }
                </div>
              }

              <!-- Histories side by side -->
              <div class="history-grid">
                <div class="audit-section">
                  <div class="audit-title">Reminder History</div>
                  @if (reminderHistory().length === 0) {
                    <div class="audit-empty">No reminders sent yet</div>
                  } @else {
                    @for (r of reminderHistory(); track r.id) {
                      <div class="audit-entry">
                        <span class="audit-action">
                          <span class="reminder-type-badge" [class.type-auto]="r.sendType === 'AUTOMATED'">
                            {{ r.sendType === 'AUTOMATED' ? 'Automated' : 'Manual' }}
                          </span>
                          {{ r.sentBy ? 'by recruiter' : 'by system' }}
                        </span>
                        <span class="audit-meta">{{ formatDate(r.sentAt) }}</span>
                      </div>
                    }
                  }
                </div>

                <div class="audit-section">
                  <div class="audit-title">Flag History</div>
                  @if (submissionFlags().length === 0) {
                    <div class="audit-empty">No flags raised</div>
                  } @else {
                    @for (f of submissionFlags(); track f.flagId) {
                      <div class="flag-history-entry">
                        <div class="flag-history-row">
                          <span class="flag-history-reason">{{ flagReasonLabel(f.reason) }}</span>
                          <span class="flag-history-status" [class]="'fh-status-' + f.status.toLowerCase()">{{ f.status }}</span>
                          <span class="audit-meta">{{ formatDate(f.createdAt) }}</span>
                        </div>
                      </div>
                    }
                  }
                </div>
              </div>

              <!-- AI Feedback Report Section -->
              @if (result()!.markingStatus === 'FULLY_MARKED') {
                <section class="feedback-section" [attr.aria-busy]="feedbackLoading() !== null">
                  @if (!feedbackExpanded()) {
                    <button class="btn-ai-feedback" (click)="toggleFeedbackReport()">
                      <span class="ai-icon">✦</span>
                      <span>AI Feedback Report</span>
                      <span class="ai-badge">AI Generated</span>
                    </button>
                  } @else {
                    <div class="feedback-header">
                      <span class="feedback-title">AI Feedback Report</span>
                      <span class="ai-badge">AI Generated</span>
                      <button class="save-btn secondary" (click)="feedbackExpanded.set(false)">Hide</button>
                      @if (feedbackReport()) {
                        <button class="save-btn secondary regenerate-btn"
                                (click)="regenerateReport()"
                                [disabled]="regenerating()">
                          {{ regenerating() ? 'Regenerating…' : 'Regenerate' }}
                        </button>
                      }
                    </div>
                    @if (regenerateError()) {
                      <div class="feedback-inline-error">
                        {{ regenerateError() }}
                        <button class="dismiss-btn" (click)="regenerateError.set(null)">✕</button>
                      </div>
                    }
                    @if (feedbackLoading()) {
                      <div class="feedback-loading">
                        <span class="loading-dot"></span>
                        {{ feedbackLoading() === 'generating' ? 'AI is generating feedback report…' : 'Loading feedback report…' }}
                      </div>
                    } @else if (feedbackError()) {
                      <div class="feedback-error">
                        <span>{{ feedbackError() }}</span>
                        <button class="save-btn" (click)="retryFeedback()">Retry</button>
                      </div>
                    } @else if (feedbackReport()) {
                      <p class="feedback-summary">{{ feedbackReport()!.content.overallSummary }}</p>
                      @if (feedbackReport()!.content.topics.length > 0) {
                        <div class="feedback-topics">
                          @for (topic of feedbackReport()!.content.topics; track topic.topic) {
                            <div class="topic-card">
                              <h4 class="topic-name">{{ topic.topic }}</h4>
                              @if (topic.strengths) {
                                <div class="topic-field"><span class="topic-label">Strengths:</span> {{ topic.strengths }}</div>
                              }
                              @if (topic.weaknesses) {
                                <div class="topic-field"><span class="topic-label">Weaknesses:</span> {{ topic.weaknesses }}</div>
                              }
                            </div>
                          }
                        </div>
                      }
                      @if (feedbackReport()!.content.nextSteps.length > 0) {
                        <div class="feedback-next-steps">
                          <h4 class="next-steps-heading">Next Steps</h4>
                          <ol class="next-steps-list">
                            @for (step of feedbackReport()!.content.nextSteps; track step) {
                              <li>{{ step }}</li>
                            }
                          </ol>
                        </div>
                      }
                      <div class="feedback-meta">Generated: {{ formatDateTime(feedbackReport()!.generatedAt) }}</div>
                    }
                  }
                </section>
              }

              <!-- Feedback Email Section -->
              @if (showFeedbackEmailSection()) {
                <div class="feedback-email-section">
                  <div class="feedback-email-controls">
                    @if (!showFeedbackEmailConfirm()) {
                      <button class="btn-reminder" (click)="showFeedbackEmailConfirm.set(true)" [disabled]="feedbackEmailSending()">
                        ✉ {{ feedbackEmailSending() ? 'Sending…' : feedbackEmailButtonLabel() }}
                      </button>
                    } @else {
                      <div class="reminder-confirm">
                        <span class="reminder-confirm-text">Send the feedback report email to this candidate?</span>
                        <button class="save-btn" (click)="sendFeedbackEmail()" [disabled]="feedbackEmailSending()">
                          {{ feedbackEmailSending() ? 'Sending…' : 'Confirm' }}
                        </button>
                        <button class="save-btn secondary" (click)="showFeedbackEmailConfirm.set(false)" [disabled]="feedbackEmailSending()">Cancel</button>
                      </div>
                    }
                    @if (feedbackEmailSuccess()) {
                      <span class="reminder-toast">✓ Feedback email sent</span>
                    }
                    @if (feedbackEmailError()) {
                      <div class="feedback-inline-error">
                        {{ feedbackEmailError() }}
                        <button class="dismiss-btn" (click)="feedbackEmailError.set(null)">✕</button>
                      </div>
                    }
                  </div>
                  <div class="audit-section">
                    <div class="audit-title">Feedback Email History</div>
                    @if (feedbackEmailHistoryLoading()) {
                      <div class="feedback-loading">
                        <span class="loading-dot"></span> Loading send history…
                      </div>
                    } @else if (feedbackEmailHistoryError()) {
                      <div class="feedback-error">
                        <span>{{ feedbackEmailHistoryError() }}</span>
                        <button class="save-btn" (click)="retryFeedbackEmailHistory()">Retry</button>
                      </div>
                    } @else if (feedbackEmailHistory().length === 0) {
                      <div class="audit-empty">No feedback emails sent yet</div>
                    } @else {
                      @for (entry of feedbackEmailHistory(); track $index) {
                        <div class="audit-entry">
                          <span class="audit-action">
                            <span class="marking-badge" [class.badge-done]="entry.status === 'SENT'" [class.badge-pending]="entry.status === 'FAILED'">
                              {{ entry.status === 'SENT' ? '✓ Sent' : '✕ Failed' }}
                            </span>
                            @if (entry.sentBy) {
                              <span>by {{ entry.sentBy }}</span>
                            }
                          </span>
                          <span class="audit-meta">{{ formatDateTime(entry.sentAt) }}</span>
                        </div>
                        @if (entry.status === 'FAILED' && entry.failureReason) {
                          <div class="feedback-email-failure-reason">{{ entry.failureReason }}</div>
                        }
                      }
                    }
                  </div>
                </div>
              }

              <!-- Per-question answers -->
              <div class="answers-title">
                Answers
                <span class="answers-count">{{ totalQuestionCount() }} question{{ totalQuestionCount() === 1 ? '' : 's' }}</span>
              </div>
              <div class="answers-list">
                @for (q of result()!.questions; track q.questionId; let i = $index) {
                  @if (q.questionType === 'GROUP') {
                    <!-- GROUP: preamble header + individually-markable sub-questions -->
                    <div class="answer-card group-preamble-card">
                      <div class="answer-card-header">
                        <div class="q-num">Q{{ i + 1 }}</div>
                        <span class="q-type-badge type-group">Group</span>
                        <span class="q-title">{{ q.questionTitle }}</span>
                        <span class="q-max-score">/ {{ q.maxScore }} pt{{ q.maxScore !== 1 ? 's' : '' }}</span>
                      </div>
                      @for (sub of q.subQuestions ?? []; track sub.questionId; let si = $index) {
                        <div class="sub-answer-card">
                          <div class="answer-card-header">
                            <div class="q-num sub-q-num">{{ si + 1 }}.</div>
                            <span class="q-type-badge type-{{ sub.questionType.toLowerCase() }}">{{ typeLabel(sub.questionType) }}</span>
                            <span class="q-title">{{ sub.questionTitle }}</span>
                            @if (sub.autoMarked) {
                              <span class="auto-badge">Auto-scored</span>
                            }
                            <span class="q-max-score">/ {{ sub.maxScore }} pt{{ sub.maxScore !== 1 ? 's' : '' }}</span>
                            @if (sub.score !== null) {
                              <span class="score-display">{{ sub.score }}/{{ sub.maxScore }}</span>
                            }
                          </div>
                          @if (sub.questionType === 'CODE_SUBMISSION' && sub.candidateAnswer) {
                            <app-code-editor [value]="sub.candidateAnswer" language="java" [readOnly]="true" height="260px" />
                          } @else {
                            <div class="answer-content">{{ sub.candidateAnswer ?? '(No answer)' }}</div>
                          }
                          @if (sub.feedback) {
                            <div class="feedback-display">{{ sub.feedback }}</div>
                          }
                          @if (!sub.autoMarked || sub.questionType !== 'MCQ') {
                            <div class="mark-row">
                              <input type="number" class="score-input" [min]="0" [max]="sub.maxScore"
                                     [value]="editScores()[sub.questionId] ?? sub.score ?? ''"
                                     (input)="onScoreInput(sub.questionId, $event)" (blur)="onScoreBlur(sub.questionId, $event, sub.maxScore)"
                                     placeholder="Score (max {{ sub.maxScore }})" />
                              <input type="text" class="feedback-input-inline"
                                     [value]="editFeedback()[sub.questionId] ?? sub.feedback ?? ''"
                                     (input)="onFeedbackInput(sub.questionId, $event)"
                                     placeholder="Feedback (optional)" />
                              <button class="save-btn" (click)="saveScore(sub)" [disabled]="saving()">Save</button>
                            </div>
                          } @else {
                            <div class="mark-row">
                              <span class="override-hint">Override auto-score:</span>
                              <input type="number" class="score-input" [min]="0" [max]="sub.maxScore"
                                     [value]="editScores()[sub.questionId] ?? ''"
                                     (input)="onScoreInput(sub.questionId, $event)" (blur)="onScoreBlur(sub.questionId, $event, sub.maxScore)"
                                     placeholder="{{ sub.score }}" />
                              <button class="save-btn secondary" (click)="saveScore(sub)" [disabled]="saving()">Override</button>
                            </div>
                          }
                          @if (isAiEligibleQuestion(sub)) {
                            <section class="ai-suggestion-panel" [attr.aria-busy]="aiLoading()[sub.questionId]">
                              @if (aiAccessDenied()[sub.questionId]) {
                                <div class="ai-error ai-access-denied">Access denied — you do not have permission to request AI suggestions.</div>
                              } @else if (aiLoading()[sub.questionId]) {
                                <div class="ai-loading"><span class="loading-dot"></span> Requesting AI suggestion…</div>
                              } @else if (aiSuggestions()[sub.questionId]) {
                                <div class="ai-suggestion-header">
                                  <span class="ai-badge">AI Suggestion</span>
                                  <span class="ai-score">{{ aiSuggestions()[sub.questionId]!.score }}/{{ aiSuggestions()[sub.questionId]!.maxScore }}</span>
                                  <button class="save-btn secondary" (click)="copyAiScoreToMark(sub.questionId)">Use this score</button>
                                  <button class="save-btn secondary" (click)="requestAiSuggestion(result()!.submissionId, sub.questionId)">Regenerate</button>
                                </div>
                                <p class="ai-rationale">{{ aiSuggestions()[sub.questionId]!.rationale }}</p>
                                <div class="ai-meta">Generated: {{ formatDateTime(aiSuggestions()[sub.questionId]!.generatedAt) }}</div>
                              } @else {
                                <div class="ai-request-row">
                                  <button class="save-btn secondary" (click)="requestAiSuggestion(result()!.submissionId, sub.questionId)">Get AI Suggestion</button>
                                  @if (aiError()[sub.questionId] === 'ineligible') {
                                    <span class="ai-error">Not eligible for AI-assisted marking.</span>
                                  } @else if (aiError()[sub.questionId] === 'error') {
                                    <span class="ai-error">Could not get AI suggestion. Please try again.</span>
                                  }
                                </div>
                              }
                            </section>
                          }
                        </div>
                      }
                    </div>
                  } @else {
                    <div class="answer-card">
                      <div class="answer-card-header">
                        <div class="q-num">Q{{ i + 1 }}</div>
                        <span class="q-type-badge type-{{ q.questionType.toLowerCase() }}">{{ typeLabel(q.questionType) }}</span>
                        <span class="q-title">{{ q.questionTitle }}</span>
                        @if (q.autoMarked) {
                          <span class="auto-badge">Auto-scored</span>
                        }
                        <span class="q-max-score">/ {{ q.maxScore }} pt{{ q.maxScore !== 1 ? 's' : '' }}</span>
                        @if (q.score !== null) {
                          <span class="score-display">{{ q.score }}/{{ q.maxScore }}</span>
                        }
                      </div>

                      @if (q.questionType === 'CODE_SUBMISSION' && q.candidateAnswer) {
                        <app-code-editor [value]="q.candidateAnswer" language="java" [readOnly]="true" height="260px" />
                      } @else {
                        <div class="answer-content">{{ q.candidateAnswer ?? '(No answer)' }}</div>
                      }

                      @if (q.feedback) {
                        <div class="feedback-display">{{ q.feedback }}</div>
                      }

                      <!-- Score input for text/code or override for MCQ -->
                      @if (!q.autoMarked || q.questionType !== 'MCQ') {
                        <div class="mark-row">
                          <input type="number" class="score-input" [min]="0" [max]="q.maxScore"
                                 [value]="editScores()[q.questionId] ?? q.score ?? ''"
                                 (input)="onScoreInput(q.questionId, $event)" (blur)="onScoreBlur(q.questionId, $event, q.maxScore)"
                                 placeholder="Score (max {{ q.maxScore }})" />
                          <input type="text" class="feedback-input-inline"
                                 [value]="editFeedback()[q.questionId] ?? q.feedback ?? ''"
                                 (input)="onFeedbackInput(q.questionId, $event)"
                                 placeholder="Feedback (optional)" />
                          <button class="save-btn" (click)="saveScore(q)" [disabled]="saving()">Save</button>
                        </div>
                      } @else {
                        <!-- MCQ auto-scored — allow override -->
                        <div class="mark-row">
                          <span class="override-hint">Override auto-score:</span>
                          <input type="number" class="score-input" [min]="0" [max]="q.maxScore"
                                 [value]="editScores()[q.questionId] ?? ''"
                                 (input)="onScoreInput(q.questionId, $event)" (blur)="onScoreBlur(q.questionId, $event, q.maxScore)"
                                 placeholder="{{ q.score }}" />
                          <button class="save-btn secondary" (click)="saveScore(q)" [disabled]="saving()">Override</button>
                        </div>
                      }
                      @if (isAiEligibleQuestion(q)) {
                        <section class="ai-suggestion-panel" [attr.aria-busy]="aiLoading()[q.questionId]">
                          @if (aiAccessDenied()[q.questionId]) {
                            <div class="ai-error ai-access-denied">Access denied — you do not have permission to request AI suggestions.</div>
                          } @else if (aiLoading()[q.questionId]) {
                            <div class="ai-loading"><span class="loading-dot"></span> Requesting AI suggestion…</div>
                          } @else if (aiSuggestions()[q.questionId]) {
                            <div class="ai-suggestion-header">
                              <span class="ai-badge">AI Suggestion</span>
                              <span class="ai-score">{{ aiSuggestions()[q.questionId]!.score }}/{{ aiSuggestions()[q.questionId]!.maxScore }}</span>
                              <button class="save-btn secondary" (click)="copyAiScoreToMark(q.questionId)">Use this score</button>
                              <button class="save-btn secondary" (click)="requestAiSuggestion(result()!.submissionId, q.questionId)">Regenerate</button>
                            </div>
                            <p class="ai-rationale">{{ aiSuggestions()[q.questionId]!.rationale }}</p>
                            <div class="ai-meta">Generated: {{ formatDateTime(aiSuggestions()[q.questionId]!.generatedAt) }}</div>
                          } @else {
                            <div class="ai-request-row">
                              <button class="save-btn secondary" (click)="requestAiSuggestion(result()!.submissionId, q.questionId)">Get AI Suggestion</button>
                              @if (aiError()[q.questionId] === 'ineligible') {
                                <span class="ai-error">Not eligible for AI-assisted marking.</span>
                              } @else if (aiError()[q.questionId] === 'error') {
                                <span class="ai-error">Could not get AI suggestion. Please try again.</span>
                              }
                            </div>
                          }
                        </section>
                      }
                    </div>
                  }
                }
              </div>
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* Lock to the viewport so the submission list and the detail
       panel scroll independently instead of sharing one page scroll */
    .page { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }

    .page-header {
      height: var(--topbar-height);
      display: flex; align-items: center; justify-content: space-between;
      padding: 0 24px; border-bottom: 1px solid var(--border);
      background: var(--bg-card); flex-shrink: 0;
    }

    .page-title { font-size: 15px; font-weight: 600; color: var(--text-1); letter-spacing: -0.01em; }
    .page-sub { font-size: 12px; color: var(--text-3); }

    .header-filters { display: flex; gap: 5px; }

    .assessment-select {
      padding: 5px 10px; border-radius: var(--radius-sm); cursor: pointer;
      font-family: var(--font); font-size: 12.5px;
      background: var(--bg-elevated); color: var(--text-2); border: 1px solid var(--border);
      outline: none; transition: border-color 120ms;
    }
    .assessment-select:focus { border-color: var(--accent); color: var(--text-1); }

    .filter-chip {
      padding: 5px 12px; border-radius: 999px; cursor: pointer;
      font-family: var(--font); font-size: 12.5px; font-weight: 400;
      background: transparent; color: var(--text-2); border: 1px solid var(--border);
      transition: all 120ms;
    }
    .filter-chip:hover { background: var(--bg-hover); color: var(--text-1); }
    .filter-chip.active { background: var(--accent-subtle); color: var(--accent); border-color: var(--accent); font-weight: 600; }

    .split-layout {
      display: grid; grid-template-columns: 324px 1fr;
      flex: 1; min-height: 0;
    }

    /* Solid glass panel so cards read clearly against the backdrop */
    .submissions-panel {
      border-right: 1px solid var(--border);
      overflow-y: auto;
      background: var(--bg-card);
      backdrop-filter: var(--glass-blur);
      -webkit-backdrop-filter: var(--glass-blur);
      padding: 10px;
      display: flex; flex-direction: column; gap: 6px;
    }

    .group-header {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      padding: 12px 6px 4px;
    }
    .group-header:first-child { padding-top: 4px; }
    .group-title {
      font-size: 10.5px; font-weight: 600; letter-spacing: 0.09em;
      text-transform: uppercase; color: var(--text-3);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .group-count {
      font-size: 10px; font-weight: 600; color: var(--text-2);
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: 999px; padding: 1px 7px; flex-shrink: 0;
    }

    .submission-item {
      display: flex; align-items: flex-start; gap: 10px;
      padding: 11px 12px;
      background: var(--bg-elevated);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      cursor: pointer;
      transition: background 120ms, border-color 120ms, box-shadow 120ms;
    }
    .submission-item:hover { background: var(--bg-hover); border-color: var(--border-hover); }
    .submission-item.active {
      background: var(--accent-subtle);
      border-color: var(--accent);
      box-shadow: 0 0 16px rgba(255, 107, 44, 0.14);
    }

    .sub-avatar {
      width: 32px; height: 32px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      font-size: 11px; font-weight: 700; color: #fff; flex-shrink: 0;
    }

    .sub-info { flex: 1; min-width: 0; }
    .sub-name-row { display: flex; align-items: center; gap: 5px; margin-bottom: 3px; }
    .sub-name {
      font-size: 13px; font-weight: 600; color: var(--text-1);
      flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .sub-bl-symbol { font-size: 13px; color: var(--danger); flex-shrink: 0; }
    .sub-score {
      font-size: 13px; font-weight: 700; flex-shrink: 0;
      background: var(--gradient-accent);
      -webkit-background-clip: text; background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .sub-tags { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-bottom: 4px; }

    .sub-status { font-size: 10px; padding: 1px 6px; border-radius: 999px; font-weight: 500; }
    .status-submitted { background: var(--success-subtle); color: var(--success); }
    .status-auto { background: var(--info-subtle); color: var(--info); }
    .status-progress { background: var(--warning-subtle); color: var(--warning); }
    .status-not-started { background: var(--bg-elevated); color: var(--text-3); border: 1px solid var(--border); }

    .sub-meta-row {
      display: flex; justify-content: space-between; gap: 8px;
      font-size: 11px; color: var(--text-3);
    }

    .sub-progress-bar {
      height: 3px; border-radius: 3px; background: var(--border);
      overflow: hidden; margin-top: 6px;
    }
    .sub-progress-fill {
      height: 100%; border-radius: 3px;
      background: var(--gradient-accent);
      transition: width 400ms ease;
    }
    .sub-progress-fill.fill-done { background: var(--success); }

    .detail-panel { overflow-y: auto; }

    .no-selection {
      height: 100%; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 10px;
      color: var(--text-3); font-size: 13px;
    }

    .detail-scroll { padding: 20px; }

    .detail-header {
      position: relative; z-index: 10;
      display: flex; align-items: flex-start; gap: 14px;
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 16px 20px; margin-bottom: 16px;
    }

    .detail-avatar {
      width: 42px; height: 42px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      font-size: 14px; font-weight: 700; color: #fff; flex-shrink: 0;
    }

    .detail-candidate-info { flex: 1; }
    .detail-name-row { display: flex; align-items: center; gap: 8px; }
    .detail-name { font-size: 15px; font-weight: 600; color: var(--text-1); }
    .detail-bl-tag { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px; background: var(--danger-subtle); color: var(--danger); white-space: nowrap; }
    .detail-assessment { display: block; font-size: 12px; color: var(--text-2); margin-top: 2px; }
    .detail-submitted { display: block; font-size: 11px; color: var(--text-3); margin-top: 2px; }

    .detail-score-block {
      text-align: center; flex-shrink: 0;
      display: flex; flex-direction: column; align-items: center; gap: 2px;
    }

    .score-ring { position: relative; width: 86px; height: 86px; }
    .score-ring svg { transform: rotate(-90deg); }
    .ring-track {
      fill: none; stroke: var(--border); stroke-width: 6;
    }
    .ring-fill {
      fill: none; stroke-width: 6; stroke-linecap: round;
      transition: stroke-dasharray 600ms cubic-bezier(0.22, 1, 0.36, 1);
    }
    .ring-center {
      position: absolute; inset: 0;
      display: flex; flex-direction: column; align-items: center; justify-content: center;
    }
    .ring-percent { font-size: 17px; font-weight: 700; color: var(--text-1); line-height: 1.1; }
    .ring-percent.ring-pending { color: var(--text-3); }
    .ring-pts { font-size: 10.5px; color: var(--text-3); }

    .marking-badge {
      display: inline-block; margin-top: 6px; padding: 2px 8px;
      border-radius: 999px; font-size: 11px; font-weight: 500;
    }
    .answered-stat { font-size: 11px; color: var(--text-3); margin-top: 4px; }
    .badge-done { background: var(--success-subtle); color: var(--success); }
    .badge-pending { background: var(--warning-subtle); color: var(--warning); }

    .answers-list { display: flex; flex-direction: column; gap: 12px; }

    .answer-card {
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 14px 16px;
    }

    .answer-card-header {
      display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap;
    }

    .q-num {
      width: 26px; height: 26px; border-radius: 50%;
      background: var(--bg-elevated); color: var(--text-2);
      font-size: 11.5px; font-weight: 700;
      display: flex; align-items: center; justify-content: center; flex-shrink: 0;
    }

    .q-type-badge {
      display: inline-flex; padding: 2px 7px; border-radius: 999px;
      font-size: 11px; font-weight: 500; flex-shrink: 0;
    }
    .type-mcq { background: var(--accent-subtle); color: var(--accent); }
    .type-text { background: var(--info-subtle); color: var(--info); }
    .type-code_submission { background: rgba(168,85,247,0.13); color: #a855f7; }
    .type-group { background: rgba(20,184,166,0.13); color: #14b8a6; }

    .group-preamble-card { display: flex; flex-direction: column; gap: 10px; }

    .sub-answer-card {
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); padding: 12px 14px;
      display: flex; flex-direction: column; gap: 8px;
    }

    .sub-q-num {
      width: 22px; height: 22px; border-radius: 50%;
      background: var(--bg); color: var(--text-3);
      font-size: 11px; font-weight: 700;
      display: flex; align-items: center; justify-content: center; flex-shrink: 0;
    }

    .q-title { font-size: 13px; font-weight: 500; color: var(--text-1); flex: 1; }

    .auto-badge {
      font-size: 10.5px; padding: 2px 7px; border-radius: 999px;
      background: var(--success-subtle); color: var(--success); font-weight: 500;
    }

    .q-max-score {
      font-size: 11px; color: var(--text-3); margin-left: auto; white-space: nowrap;
    }

    .score-display {
      font-size: 12px; font-weight: 700; color: var(--text-1);
      background: var(--bg-elevated); padding: 2px 8px; border-radius: 999px;
    }

    .answer-content {
      font-size: 13px; color: var(--text-2); line-height: 1.65;
      padding: 10px 12px; background: var(--bg-elevated);
      border-radius: var(--radius-sm); margin-bottom: 10px;
      white-space: pre-wrap; font-family: var(--font-mono);
    }

    .feedback-display {
      font-size: 12.5px; color: var(--text-2); font-style: italic;
      padding: 6px 10px; border-left: 3px solid var(--accent-subtle);
      margin-bottom: 10px;
    }

    .mark-row {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
    }

    .override-hint { font-size: 12px; color: var(--text-3); }

    .score-input {
      width: 64px; padding: 5px 8px; text-align: center;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1);
      font-size: 13px; outline: none;
    }
    .score-input:focus { border-color: var(--accent); }

    .feedback-input-inline {
      flex: 1; padding: 5px 10px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1);
      font-size: 12.5px; outline: none; font-family: var(--font);
    }
    .feedback-input-inline:focus { border-color: var(--accent); }
    .feedback-input-inline::placeholder { color: var(--text-3); }

    .save-btn {
      padding: 5px 14px; border-radius: var(--radius-sm);
      background: var(--accent); color: #fff; border: none;
      font-size: 12.5px; font-weight: 600; cursor: pointer;
      font-family: var(--font); transition: background 120ms;
    }
    .save-btn:hover:not(:disabled) { background: var(--accent-hover); }
    .save-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .save-btn.secondary { background: var(--bg-elevated); color: var(--text-2); border: 1px solid var(--border); }
    .save-btn.secondary:hover:not(:disabled) { background: var(--bg-hover); }

    .empty-panel { padding: 40px; text-align: center; color: var(--text-3); font-size: 13px; }

    .pending-badge { font-size: 10px; padding: 1px 6px; border-radius: 999px; background: var(--warning-subtle); color: var(--warning); font-weight: 600; flex-shrink: 0; }
    .flag-badge { font-size: 10px; padding: 1px 6px; border-radius: 999px; background: var(--danger-subtle); color: var(--danger); font-weight: 600; flex-shrink: 0; }
    .flag-badge-action { background: rgba(234,88,12,.12); color: #ea580c; }
    .flag-badge-detail { display: inline-block; margin-top: 4px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; background: var(--danger-subtle); color: var(--danger); }

    .ai-risk-badge { font-size: 10px; padding: 1px 6px; border-radius: 999px; font-weight: 600; flex-shrink: 0; }
    .ai-risk-high { background: rgba(239,68,68,.1); color: #dc2626; }
    .ai-risk-medium { background: rgba(234,179,8,.12); color: #b45309; }
    .ai-risk-badge-detail { display: inline-block; margin-top: 4px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
    .ai-risk-badge-detail.ai-risk-high { background: rgba(239,68,68,.1); color: #dc2626; }
    .ai-risk-badge-detail.ai-risk-medium { background: rgba(234,179,8,.12); color: #b45309; }

    .ai-flagged-badge { display: inline-flex; align-items: center; gap: 2px; margin-top: 4px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; background: rgba(99,102,241,.1); color: #4f46e5; }

    .flag-section {
      margin: 0 0 14px;
      padding: 12px 14px; background: var(--bg-card);
      border: 1px solid var(--border); border-radius: var(--radius-lg);
    }

    .btn-flag {
      background: none; border: 1px solid var(--danger); color: var(--danger);
      padding: 5px 12px; border-radius: var(--radius-sm); font-size: 12.5px; font-weight: 500;
      cursor: pointer; font-family: var(--font); transition: all 120ms;
    }
    .btn-flag:hover { background: var(--danger-subtle); }

    .flag-form { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

    .field-select-sm, .field-input-sm {
      padding: 5px 10px; background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1); font-size: 13px; outline: none;
      font-family: var(--font);
    }
    .field-select-sm:focus, .field-input-sm:focus { border-color: var(--accent); }
    .field-input-sm { flex: 1; min-width: 220px; }

    .flag-transition { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }

    .btn-flag-action {
      padding: 5px 12px; border-radius: var(--radius-sm); font-size: 12.5px; font-weight: 500;
      cursor: pointer; font-family: var(--font); border: 1px solid var(--border);
      background: var(--bg-elevated); color: var(--text-2); transition: all 120ms;
    }
    .btn-flag-action:hover { background: var(--bg-hover); color: var(--text-1); }
    .btn-flag-action.resolve { border-color: var(--success); color: var(--success); }
    .btn-flag-action.resolve:hover { background: var(--success-subtle); }
    .btn-flag-action.dismiss { border-color: var(--text-3); }

    .audit-section {
      margin: 0 0 14px; padding: 12px 14px;
      background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius-lg);
    }

    .history-grid {
      display: grid; grid-template-columns: 1fr 1fr; gap: 14px;
      margin-bottom: 14px;
    }
    .history-grid .audit-section { margin: 0; }
    @media (max-width: 1100px) {
      .history-grid { grid-template-columns: 1fr; gap: 0; }
      .history-grid .audit-section { margin: 0 0 14px; }
    }

    .answers-title {
      display: flex; align-items: baseline; gap: 8px;
      font-size: 13px; font-weight: 600; color: var(--text-1);
      text-transform: uppercase; letter-spacing: 0.06em;
      margin: 6px 2px 10px;
    }
    .answers-count {
      font-size: 11.5px; font-weight: 500; color: var(--text-3);
      text-transform: none; letter-spacing: 0;
    }

    /* Glass treatment so detail cards read clearly over the backdrop */
    .detail-header, .answer-card, .flag-section, .audit-section, .reminder-section, .feedback-email-section {
      backdrop-filter: var(--glass-blur);
      -webkit-backdrop-filter: var(--glass-blur);
    }
    .audit-title { font-size: 12px; font-weight: 600; color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 8px; }
    .audit-entry { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; border-bottom: 1px solid var(--border); }
    .audit-entry:last-child { border-bottom: none; }
    .flag-history-entry { padding: 6px 0; border-bottom: 1px solid var(--border); }
    .flag-history-entry:last-child { border-bottom: none; }
    .flag-history-row { display: flex; align-items: center; gap: 8px; }
    .flag-history-reason { font-size: 12px; color: var(--text-1); flex: 1; }
    .flag-history-status { font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 999px; }
    .flag-history-notes { font-size: 11.5px; color: var(--text-3); margin-top: 3px; font-style: italic; }
    .fh-status-flagged { background: var(--warning-subtle); color: var(--warning); }
    .fh-status-under_review { background: var(--info-subtle); color: var(--info); }
    .fh-status-action_required { background: rgba(234,88,12,.12); color: #ea580c; }
    .fh-status-resolved { background: var(--success-subtle); color: var(--success); }
    .fh-status-dismissed { background: var(--bg-elevated); color: var(--text-3); }
    .audit-action { font-size: 12.5px; color: var(--text-1); font-weight: 500; display: flex; align-items: center; gap: 6px; }
    .audit-meta { font-size: 11.5px; color: var(--text-3); }
    .audit-empty { font-size: 12px; color: var(--text-3); font-style: italic; }

    .reminder-section {
      margin: 0 0 14px; padding: 12px 14px;
      background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius-lg);
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
    }
    .feedback-email-section {
      margin: 0 0 14px; padding: 12px 14px;
      background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius-lg);
    }
    .feedback-email-controls {
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px;
    }
    .feedback-email-failure-reason {
      font-size: 11.5px; color: var(--danger); margin: 2px 0 6px 0; padding-left: 2px;
    }
    .btn-reminder {
      background: none; border: 1px solid var(--accent); color: var(--accent);
      padding: 5px 12px; border-radius: var(--radius-sm); font-size: 12.5px; font-weight: 500;
      cursor: pointer; font-family: var(--font); transition: all 120ms;
    }
    .btn-reminder:hover:not(:disabled) { background: var(--accent-subtle); }
    .btn-reminder:disabled { opacity: 0.5; cursor: not-allowed; }
    .reminder-confirm { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .reminder-confirm-text { font-size: 12.5px; color: var(--text-2); }
    .reminder-toast { font-size: 12px; color: var(--success); font-weight: 500; }
    .reminder-type-badge {
      display: inline-block; padding: 1px 6px; border-radius: 999px; font-size: 10.5px; font-weight: 500;
      background: var(--info-subtle); color: var(--info);
    }
    .reminder-type-badge.type-auto { background: var(--accent-subtle); color: var(--accent); }

    .feedback-section {
      margin: 0 0 14px;
      padding: 16px 18px;
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      backdrop-filter: var(--glass-blur);
      -webkit-backdrop-filter: var(--glass-blur);
    }

    .btn-ai-feedback {
      display: flex; align-items: center; gap: 10px;
      width: 100%;
      padding: 12px 16px;
      background: var(--bg-elevated);
      border: 1px dashed var(--accent);
      border-radius: var(--radius-sm);
      cursor: pointer;
      font-size: 13px; font-weight: 500;
      color: var(--accent);
      transition: background 0.15s, border-color 0.15s;
    }

    .btn-ai-feedback:hover {
      background: var(--accent-subtle);
      border-style: solid;
    }

    .btn-ai-feedback .ai-icon {
      font-size: 16px;
    }

    .feedback-loading {
      display: flex; align-items: center; gap: 10px;
      font-size: 13px; color: var(--text-2);
    }

    .loading-dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--accent);
      animation: pulse 1.2s ease-in-out infinite;
    }

    @keyframes pulse {
      0%, 100% { opacity: 0.3; transform: scale(0.8); }
      50% { opacity: 1; transform: scale(1); }
    }

    .feedback-header {
      display: flex; align-items: center; gap: 10px; margin-bottom: 12px;
    }

    .feedback-title {
      font-size: 14px; font-weight: 600; color: var(--text-1);
    }

    .ai-badge {
      font-size: 10.5px; padding: 2px 8px; border-radius: 999px;
      background: var(--accent-subtle); color: var(--accent);
      font-weight: 600;
    }

    .regenerate-btn {
      margin-left: auto;
    }

    .feedback-summary {
      font-size: 13px; color: var(--text-2); line-height: 1.6;
      margin: 0 0 14px;
    }

    .feedback-topics {
      margin-bottom: 14px;
    }

    .topic-card {
      padding: 12px 14px;
      background: var(--bg-elevated);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      margin-bottom: 8px;
    }

    .topic-name {
      font-size: 13px; font-weight: 600; color: var(--text-1); margin: 0 0 8px;
    }

    .topic-label {
      font-weight: 600; color: var(--text-2);
    }

    .topic-field {
      font-size: 12.5px; color: var(--text-2); margin-bottom: 4px; line-height: 1.5;
    }

    .feedback-next-steps {
      margin-bottom: 14px;
    }

    .next-steps-heading {
      font-size: 13px; font-weight: 600; color: var(--text-1); margin: 0 0 8px;
    }

    .next-steps-list {
      font-size: 12.5px; color: var(--text-2); padding-left: 20px; margin: 0;
      line-height: 1.6;
    }

    .feedback-meta {
      font-size: 11.5px; color: var(--text-3); margin-top: 10px;
    }

    .feedback-error {
      display: flex; align-items: center; gap: 10px;
      font-size: 13px; color: var(--danger);
    }

    .feedback-inline-error {
      display: flex; align-items: center; gap: 8px;
      font-size: 12px; color: var(--danger);
      background: var(--danger-subtle, rgba(220, 38, 38, 0.08));
      padding: 6px 10px; border-radius: var(--radius-sm);
      margin-bottom: 10px;
    }

    .dismiss-btn {
      background: none; border: none; cursor: pointer;
      color: var(--text-3); font-size: 14px; padding: 0 4px;
      line-height: 1;
    }
    .dismiss-btn:hover { color: var(--text-1); }

    .ai-suggestion-panel {
      margin-top: 10px;
      padding: 12px 14px;
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
    }

    .ai-suggestion-header {
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
      margin-bottom: 8px;
    }

    .ai-score {
      font-size: 13px; font-weight: 600; color: var(--text-1);
    }

    .ai-rationale {
      font-size: 12.5px; color: var(--text-2); line-height: 1.6;
      margin: 0 0 8px;
    }

    .ai-meta {
      font-size: 11.5px; color: var(--text-3);
    }

    .ai-loading {
      display: flex; align-items: center; gap: 8px;
      font-size: 12.5px; color: var(--text-2);
    }

    .ai-error {
      font-size: 12.5px; color: var(--danger);
    }

    .ai-access-denied {
      font-size: 12.5px; color: var(--danger);
    }

    .ai-request-row {
      display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
    }

    .ai-risk-tooltip-wrapper { position: relative; display: inline-block; }

    .tooltip-arrow { font-size: 9px; margin-left: 3px; }

    .ai-risk-tooltip {
      position: absolute; top: calc(100% + 6px); right: 0;
      min-width: 260px; max-width: 340px;
      padding: 12px 14px;
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      box-shadow: 0 8px 24px rgba(0,0,0,.12);
      z-index: 100;
      display: flex; flex-direction: column; gap: 6px;
    }

    .tooltip-loading { font-size: 12px; color: var(--text-2); display: flex; align-items: center; gap: 6px; }

    .tooltip-row { display: flex; gap: 6px; font-size: 12px; }
    .tooltip-label { color: var(--text-3); font-weight: 600; white-space: nowrap; }
    .tooltip-value { color: var(--text-1); }

    .tooltip-rationale {
      font-size: 12px; color: var(--text-2); line-height: 1.5;
      padding: 6px 0; border-top: 1px solid var(--border);
      margin-top: 2px;
    }

    .tooltip-meta { font-size: 11px; color: var(--text-3); }

    .tooltip-error { font-size: 12px; color: var(--danger); }
  `],
})
export class ResultsComponent implements OnInit {
  private readonly markingSvc = inject(MarkingService);
  private readonly flagSvc = inject(FlagService);
  private readonly reminderSvc = inject(ReminderService);
  private readonly route = inject(ActivatedRoute);
  private readonly feedbackSvc = inject(FeedbackService);
  private readonly feedbackEmailSvc = inject(FeedbackEmailService);
  private readonly aiMarkingSvc = inject(AiMarkingService);

  readonly submissions = signal<SubmissionSummary[]>([]);
  readonly selectedSummary = signal<SubmissionSummary | null>(null);
  readonly result = signal<ResultSummary | null>(null);
  readonly loadingList = signal(false);
  readonly loadingResult = signal(false);
  readonly saving = signal(false);
  readonly statusFilter = signal('');
  readonly assessmentFilter = signal('');

  // Flag state
  readonly activeFlag = signal<FlagResponse | null>(null);
  readonly auditTrail = signal<FlagAuditEntry[]>([]);
  readonly showFlagForm = signal(false);
  readonly flagReason = signal<FlagReason | ''>('');
  readonly flagSaving = signal(false);
  readonly showResolveForm = signal<FlagStatus | null>(null);
  readonly resolveNotes = signal('');

  // Flag history for selected submission
  readonly submissionFlags = signal<FlagListItem[]>([]);

  // Reminder state
  readonly showReminderConfirm = signal(false);
  readonly reminderSending = signal(false);
  readonly reminderSuccess = signal(false);
  readonly reminderHistory = signal<ReminderSendLogDto[]>([]);

  // Feedback state
  readonly feedbackReport = signal<FeedbackReportResponse | null>(null);
  readonly feedbackLoading = signal<'fetching' | 'generating' | null>(null);
  readonly feedbackError = signal<string | null>(null);
  readonly feedbackExpanded = signal(false);
  readonly regenerating = signal(false);
  readonly regenerateError = signal<string | null>(null);
  private feedbackSub?: Subscription;

  // Feedback-email history state
  readonly feedbackEmailHistory = signal<FeedbackEmailSendLogEntry[]>([]);
  readonly feedbackEmailHistoryLoading = signal(false);
  readonly feedbackEmailHistoryError = signal<string | null>(null);

  // Feedback-email send-action state
  readonly showFeedbackEmailConfirm = signal(false);
  readonly feedbackEmailSending = signal(false);
  readonly feedbackEmailSuccess = signal(false);
  readonly feedbackEmailError = signal<string | null>(null);
  private feedbackEmailSub?: Subscription;

  readonly editScores = signal<Record<string, number | undefined>>({});
  readonly editFeedback = signal<Record<string, string | undefined>>({});

  // Per-question AI suggestion state — Record keyed by questionId
  readonly aiSuggestions = signal<Record<string, AiMarkingSuggestionResponse | undefined>>({});
  readonly aiLoading = signal<Record<string, boolean>>({});
  readonly aiError = signal<Record<string, AiSuggestionErrorKind | undefined>>({});
  readonly aiAccessDenied = signal<Record<string, boolean>>({});

  // Stale-response guarding — plain fields, not signals (internal bookkeeping only)
  private aiGeneration = 0;
  private aiRequestSeq: Record<string, number> = {};

  // Risk assessment tooltip state
  readonly riskAssessment = signal<RiskAssessmentResponse | null>(null);
  readonly riskTooltipOpen = signal(false);
  readonly riskAssessmentLoading = signal(false);

  // Exposed so the template can call the standalone eligibility predicate directly.
  readonly isAiEligibleQuestion = isAiEligibleQuestion;

  readonly statusFilters = [
    { value: '', label: 'All' },
    { value: 'NOT_STARTED', label: 'Not Started' },
    { value: 'IN_PROGRESS', label: 'In Progress' },
    { value: 'SUBMITTED', label: 'Submitted' },
    { value: 'AUTO_SUBMITTED', label: 'Auto-submitted' },
    { value: 'PENDING_REVIEW', label: 'Pending Review' },
  ];

  readonly availableAssessments = computed(() => {
    const seen = new Set<string>();
    const result: { assessmentId: string; assessmentTitle: string }[] = [];
    for (const s of this.submissions()) {
      if (s.assessmentId && !seen.has(s.assessmentId)) {
        seen.add(s.assessmentId);
        result.push({ assessmentId: s.assessmentId, assessmentTitle: s.assessmentTitle });
      }
    }
    return result;
  });

  readonly groupedSubmissions = computed(() => {
    const groups = new Map<string, { assessmentId: string; assessmentTitle: string; items: SubmissionSummary[] }>();
    for (const s of this.filteredSubmissions()) {
      const key = s.assessmentId ?? '—';
      let g = groups.get(key);
      if (!g) {
        g = { assessmentId: key, assessmentTitle: s.assessmentTitle ?? 'Unknown Assessment', items: [] };
        groups.set(key, g);
      }
      g.items.push(s);
    }
    return [...groups.values()];
  });

  readonly filteredSubmissions = computed(() => {
    const statusF = this.statusFilter();
    const assessmentF = this.assessmentFilter();
    let list = this.submissions();
    if (assessmentF) {
      list = list.filter(s => s.assessmentId === assessmentF);
    }
    if (!statusF) return list;
    if (statusF === 'PENDING_REVIEW') {
      return list.filter(s =>
        (s.status === 'SUBMITTED' || s.status === 'AUTO_SUBMITTED') &&
        s.markedCount < s.totalAnswers,
      );
    }
    return list.filter(s => s.status === statusF);
  });

  // Feedback Email Section is only shown once a feedback report has
  // actually loaded for the selected submission — visibility is the
  // conjunction of markingStatus and feedbackReport availability, not
  // markingStatus alone.
  readonly showFeedbackEmailSection = computed(
    () => this.result()?.markingStatus === 'FULLY_MARKED' && this.feedbackReport() !== null,
  );

  // Send/resend button label reflects whether any send attempt has been
  // made yet — the template overrides this with "Sending…" while a send
  // request is in flight, regardless of history length.
  readonly feedbackEmailButtonLabel = computed(() =>
    this.feedbackEmailHistory().length === 0 ? 'Send Feedback Email' : 'Resend Feedback Email',
  );

  // AI marking suggestion eligibility — applies to top-level questions and,
  // separately, to each GROUP question's subQuestions; never to the GROUP
  // question itself.
  readonly eligibleQuestionIds = computed<string[]>(() => {
    const r = this.result();
    if (!r) return [];
    const ids: string[] = [];
    for (const q of r.questions) {
      if (q.questionType === 'GROUP') {
        for (const sub of q.subQuestions ?? []) {
          if (isAiEligibleQuestion(sub)) ids.push(sub.questionId);
        }
      } else if (isAiEligibleQuestion(q)) {
        ids.push(q.questionId);
      }
    }
    return ids;
  });

  ngOnInit() {
    this.loadingList.set(true);
    this.markingSvc.listAllSubmissions().subscribe({
      next: list => {
        this.submissions.set(list);
        this.loadingList.set(false);
        const targetId = this.route.snapshot.queryParamMap.get('submission');
        if (targetId) {
          const match = list.find(s => s.submissionId === targetId);
          if (match) this.selectSubmission(match);
        }
      },
      error: () => this.loadingList.set(false),
    });
  }

  selectSubmission(s: SubmissionSummary) {
    this.selectedSummary.set(s);
    this.result.set(null);
    this.editScores.set({});
    this.editFeedback.set({});
    this.activeFlag.set(null);
    this.auditTrail.set([]);
    this.showFlagForm.set(false);
    this.flagReason.set('');
    this.showResolveForm.set(null);
    this.resolveNotes.set('');
    this.showReminderConfirm.set(false);
    this.reminderSending.set(false);
    this.reminderSuccess.set(false);
    this.reminderHistory.set([]);
    this.submissionFlags.set([]);
    this.feedbackSub?.unsubscribe();
    this.feedbackReport.set(null);
    this.feedbackLoading.set(null);
    this.feedbackError.set(null);
    this.feedbackExpanded.set(false);
    this.regenerating.set(false);
    this.regenerateError.set(null);
    this.feedbackEmailSub?.unsubscribe();
    this.feedbackEmailHistory.set([]);
    this.feedbackEmailHistoryLoading.set(false);
    this.feedbackEmailHistoryError.set(null);
    this.showFeedbackEmailConfirm.set(false);
    this.feedbackEmailSending.set(false);
    this.feedbackEmailSuccess.set(false);
    this.feedbackEmailError.set(null);
    this.aiGeneration++;
    this.aiRequestSeq = {};
    this.aiSuggestions.set({});
    this.aiLoading.set({});
    this.aiError.set({});
    this.aiAccessDenied.set({});
    this.riskAssessment.set(null);
    this.riskTooltipOpen.set(false);
    this.riskAssessmentLoading.set(false);
    // NOT_STARTED candidates have no submission to load
    if (s.status !== 'NOT_STARTED' && s.submissionId) {
      const submissionId = s.submissionId;
      this.loadingResult.set(true);
      this.markingSvc.getResult(submissionId).subscribe({
        next: r => {
          this.result.set(r);
          this.loadingResult.set(false);
          this.loadAiSuggestions(submissionId);
        },
        error: () => this.loadingResult.set(false),
      });
      if (s.flagStatus === 'FLAGGED' || s.flagStatus === 'UNDER_REVIEW' || s.flagStatus === 'ACTION_REQUIRED') {
        this.loadActiveFlagForSubmission(submissionId, s.flagStatus as FlagStatus);
      }
      this.flagSvc.getCandidateFlags(s.candidateId).subscribe({
        next: flags => this.submissionFlags.set(flags.filter(f => f.submissionId === submissionId)),
      });
    }
    // Load reminder history via invitationId
    this.reminderSvc.getReminderHistory(s.invitationId).subscribe({
      next: history => this.reminderHistory.set(history),
    });
  }

  private loadFeedbackReport(submissionId: string): void {
    this.feedbackLoading.set('fetching');
    this.feedbackError.set(null);

    this.feedbackSub = this.feedbackSvc.getReport(submissionId).subscribe({
      next: report => {
        this.feedbackReport.set(report);
        this.feedbackLoading.set(null);
        this.loadFeedbackEmailHistory(submissionId);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 404) {
          this.feedbackLoading.set('generating');
          this.feedbackSub = this.feedbackSvc.generateReport(submissionId).subscribe({
            next: report => {
              if (this.selectedSummary()?.submissionId === submissionId) {
                this.feedbackReport.set(report);
                this.loadFeedbackEmailHistory(submissionId);
              }
              this.feedbackLoading.set(null);
            },
            error: () => {
              if (this.selectedSummary()?.submissionId === submissionId) {
                this.feedbackError.set('Could not generate feedback report. Please try again.');
              }
              this.feedbackLoading.set(null);
            },
          });
        } else {
          this.feedbackError.set('Could not load feedback report. Please try again.');
          this.feedbackLoading.set(null);
        }
      },
    });
  }

  private loadFeedbackEmailHistory(submissionId: string): void {
    this.feedbackEmailHistoryLoading.set(true);
    this.feedbackEmailHistoryError.set(null);

    this.feedbackEmailSub = this.feedbackEmailSvc.getSendHistory(submissionId).subscribe({
      next: history => {
        // Guard: discard if the user has since switched submissions (Req 4.7)
        if (this.selectedSummary()?.submissionId === submissionId) {
          this.feedbackEmailHistory.set(history);
          this.feedbackEmailHistoryLoading.set(false);
        }
      },
      error: () => {
        if (this.selectedSummary()?.submissionId === submissionId) {
          this.feedbackEmailHistoryError.set('Could not load send history. Please try again.');
          this.feedbackEmailHistoryLoading.set(false);
        }
      },
    });
  }

  retryFeedbackEmailHistory(): void {
    const submissionId = this.selectedSummary()?.submissionId;
    if (!submissionId) return;
    this.loadFeedbackEmailHistory(submissionId);
  }

  sendFeedbackEmail(): void {
    const submissionId = this.selectedSummary()?.submissionId;
    if (!submissionId) return;

    // Req 7.6: clear prior success/error before the new request begins
    this.feedbackEmailSuccess.set(false);
    this.feedbackEmailError.set(null);
    this.feedbackEmailSending.set(true);

    this.feedbackEmailSvc.sendEmail(submissionId).subscribe({
      next: response => {
        this.feedbackEmailSending.set(false);
        this.showFeedbackEmailConfirm.set(false);
        this.feedbackEmailSuccess.set(true);
        // Req 7.1: optimistic update — prepend without a re-fetch/reload.
        // sentBy is unknown from this response shape; omitted (null) here is
        // self-correcting on the next natural history refresh (re-selecting
        // the submission), and satisfies Req 5.4's "omit if null" rule.
        this.feedbackEmailHistory.update(h => [
          { sentAt: response.sentAt, status: response.status, sentBy: null, failureReason: null },
          ...h,
        ]);
        setTimeout(() => this.feedbackEmailSuccess.set(false), 3000);
      },
      error: (err: HttpErrorResponse) => {
        this.feedbackEmailSending.set(false);
        const { message, refetch } = this.classifySendError(err);
        this.feedbackEmailError.set(message);
        if (refetch) {
          this.loadFeedbackEmailHistory(submissionId);
        }
      },
    });
  }

  /**
   * Pure classification function (Requirements 7.2–7.4, 8.1): maps any send
   * failure to a sanitized, non-technical message and a refetch flag. Only a
   * 502 (email genuinely attempted and failed server-side, recorded as a
   * FAILED log row) warrants re-fetching history to surface that new row —
   * 404/409 and network errors never reached a log-write, so there is nothing
   * new to fetch.
   */
  private classifySendError(err: HttpErrorResponse): { message: string; refetch: boolean } {
    if (err.status === 502) {
      return { message: 'The feedback email could not be delivered to the candidate.', refetch: true };
    }
    return { message: 'The feedback email could not be sent. Please try again.', refetch: false };
  }

  private isCurrentAiRequest(questionId: string, generation: number, seq: number): boolean {
    return this.aiGeneration === generation && this.aiRequestSeq[questionId] === seq;
  }

  /**
   * Total, mutually-exclusive mapping from a failed generate/fetch HTTP
   * outcome to one of the named AI suggestion failure states:
   *  - fetch 404            -> "no suggestion yet" (no state mutated, no error shown)
   *  - 401 / 403            -> sticky aiAccessDenied[questionId] = true
   *  - 400 on generate only -> aiError[questionId] = 'ineligible'
   *  - anything else        -> aiError[questionId] = 'error'
   *    (other 4xx/5xx statuses, or no response at all e.g. network error/timeout)
   */
  private applyAiErrorClassification(questionId: string, err: HttpErrorResponse, isGetFetch: boolean): void {
    if (err.status === 404 && isGetFetch) {
      return;
    }
    if (err.status === 401 || err.status === 403) {
      this.aiAccessDenied.update(s => ({ ...s, [questionId]: true }));
      return;
    }
    if (err.status === 400 && !isGetFetch) {
      this.aiError.update(s => ({ ...s, [questionId]: 'ineligible' }));
      return;
    }
    this.aiError.update(s => ({ ...s, [questionId]: 'error' }));
  }

  private fetchAiSuggestion(submissionId: string, questionId: string): void {
    const generation = this.aiGeneration;
    const seq = (this.aiRequestSeq[questionId] ?? 0) + 1;
    this.aiRequestSeq[questionId] = seq;
    this.aiLoading.update(s => ({ ...s, [questionId]: true }));

    this.aiMarkingSvc.getSuggestion(submissionId, questionId).subscribe({
      next: suggestion => {
        if (!this.isCurrentAiRequest(questionId, generation, seq)) return;
        this.aiSuggestions.update(s => ({ ...s, [questionId]: suggestion }));
        this.aiError.update(s => ({ ...s, [questionId]: undefined }));
        this.aiLoading.update(s => ({ ...s, [questionId]: false }));
      },
      error: (err: HttpErrorResponse) => {
        if (!this.isCurrentAiRequest(questionId, generation, seq)) return;
        this.applyAiErrorClassification(questionId, err, /* isGetFetch */ true);
        this.aiLoading.update(s => ({ ...s, [questionId]: false }));
      },
    });
  }

  private loadAiSuggestions(submissionId: string): void {
    for (const questionId of this.eligibleQuestionIds()) {
      this.fetchAiSuggestion(submissionId, questionId);
    }
  }

  /**
   * Requests (or regenerates) an AI marking suggestion for a single answer.
   * Handles both the first request and every subsequent regeneration — the
   * backend endpoint and dispatch path are identical either way. No-op while
   * a request is already in flight for this answer, or once access has been
   * denied for it.
   */
  requestAiSuggestion(submissionId: string, questionId: string): void {
    if (this.aiLoading()[questionId] || this.aiAccessDenied()[questionId]) return;

    const generation = this.aiGeneration;
    const seq = (this.aiRequestSeq[questionId] ?? 0) + 1;
    this.aiRequestSeq[questionId] = seq;
    this.aiLoading.update(s => ({ ...s, [questionId]: true }));
    this.aiError.update(s => ({ ...s, [questionId]: undefined }));

    this.aiMarkingSvc.generateSuggestion(submissionId, questionId).subscribe({
      next: suggestion => {
        if (!this.isCurrentAiRequest(questionId, generation, seq)) return;
        this.aiSuggestions.update(s => ({ ...s, [questionId]: suggestion }));
        this.aiError.update(s => ({ ...s, [questionId]: undefined }));
        this.aiLoading.update(s => ({ ...s, [questionId]: false }));
      },
      error: (err: HttpErrorResponse) => {
        if (!this.isCurrentAiRequest(questionId, generation, seq)) return;
        this.applyAiErrorClassification(questionId, err, /* isGetFetch */ false);
        this.aiLoading.update(s => ({ ...s, [questionId]: false }));
      },
    });
  }

  /**
   * Copies the currently displayed AI suggestion's score into the manual
   * mark input for the given answer. Pure client-side signal write — no
   * HTTP call — reusing the existing `editScores` map so `saveScore()`
   * needs no changes. No-op if no suggestion is currently displayed for
   * this answer.
   */
  copyAiScoreToMark(questionId: string): void {
    const suggestion = this.aiSuggestions()[questionId];
    if (!suggestion) return;
    this.editScores.update(s => ({ ...s, [questionId]: suggestion.score }));
  }

  readonly riskReasonsFormatted = computed(() => {
    const ra = this.riskAssessment();
    if (!ra || ra.reasons.length === 0) return '';
    return ra.reasons.map(r => this.flagReasonLabel(r)).join(', ');
  });

  toggleRiskTooltip(): void {
    if (this.riskTooltipOpen()) {
      this.riskTooltipOpen.set(false);
      return;
    }
    const submissionId = this.selectedSummary()?.submissionId;
    if (!submissionId) return;

    if (this.riskAssessment()) {
      this.riskTooltipOpen.set(true);
      return;
    }

    this.riskAssessmentLoading.set(true);
    this.riskTooltipOpen.set(true);
    this.flagSvc.getRiskAssessment(submissionId).subscribe({
      next: assessment => {
        this.riskAssessment.set(assessment);
        this.riskAssessmentLoading.set(false);
      },
      error: () => {
        this.riskAssessmentLoading.set(false);
      },
    });
  }

  regenerateReport(): void {
    const submissionId = this.selectedSummary()?.submissionId;
    if (!submissionId) return;

    this.regenerating.set(true);
    this.regenerateError.set(null);

    this.feedbackSvc.generateReport(submissionId)
      .pipe(timeout(30_000))
      .subscribe({
        next: report => {
          this.feedbackReport.set(report);
          this.regenerating.set(false);
        },
        error: (err) => {
          this.regenerating.set(false);
          if (err.name === 'TimeoutError') {
            this.regenerateError.set('Regeneration timed out. Please try again.');
          } else {
            this.regenerateError.set('Regeneration failed. Please try again.');
          }
        },
      });
  }

  toggleFeedbackReport(): void {
    this.feedbackExpanded.set(true);
    // Only fetch if we don't already have it loaded
    if (!this.feedbackReport() && !this.feedbackLoading()) {
      const submissionId = this.selectedSummary()?.submissionId;
      if (submissionId) {
        this.loadFeedbackReport(submissionId);
      }
    }
  }

  retryFeedback(): void {
    const submissionId = this.selectedSummary()?.submissionId;
    if (!submissionId) return;
    this.feedbackError.set(null);
    this.loadFeedbackReport(submissionId);
  }

  private loadActiveFlagForSubmission(submissionId: string, status: FlagStatus) {
    // We'll load via the candidate flags endpoint to find the open flag
    // Since we don't have a GET single flag endpoint, we store flag info from create/transition responses
    // and seed activeFlag from submission list flagStatus
    const stub: FlagResponse = {
      flagId: '', submissionId, reason: 'OTHER', status,
      resolutionNotes: null, createdBy: '', createdAt: ''
    };
    this.activeFlag.set(stub);
  }

  submitFlag() {
    const s = this.selectedSummary();
    const reason = this.flagReason();
    if (!s || !s.submissionId || !reason) return;
    this.flagSaving.set(true);
    this.flagSvc.createFlag(s.submissionId, { reason }).subscribe({
      next: flag => {
        this.activeFlag.set(flag);
        this.showFlagForm.set(false);
        this.flagReason.set('');
        this.flagSaving.set(false);
        this.loadAuditTrail(s.submissionId!, flag.flagId);
        this.submissions.update(list => list.map(sub =>
          sub.submissionId === s.submissionId ? { ...sub, flagStatus: 'FLAGGED' as any } : sub
        ));
      },
      error: () => this.flagSaving.set(false),
    });
  }

  transitionFlag(newStatus: FlagStatus, notes: string | null) {
    const s = this.selectedSummary();
    const flag = this.activeFlag();
    if (!s || !s.submissionId || !flag?.flagId) return;
    this.flagSaving.set(true);
    this.flagSvc.transitionFlag(s.submissionId, flag.flagId, { status: newStatus, resolutionNotes: notes }).subscribe({
      next: updated => {
        this.activeFlag.set(updated);
        this.showResolveForm.set(null);
        this.resolveNotes.set('');
        this.flagSaving.set(false);
        this.loadAuditTrail(s.submissionId!, updated.flagId);
        const flagStatus = (newStatus === 'RESOLVED' || newStatus === 'DISMISSED') ? null : newStatus;
        this.submissions.update(list => list.map(sub =>
          sub.submissionId === s.submissionId ? { ...sub, flagStatus: flagStatus as any } : sub
        ));
      },
      error: () => this.flagSaving.set(false),
    });
  }

  private loadAuditTrail(submissionId: string, flagId: string) {
    if (!flagId) return;
    this.flagSvc.getAuditTrail(submissionId, flagId).subscribe({
      next: entries => this.auditTrail.set(entries),
    });
  }

  sendReminder() {
    const s = this.selectedSummary();
    if (!s) return;
    this.reminderSending.set(true);
    this.reminderSvc.sendReminder(s.invitationId).subscribe({
      next: log => {
        this.reminderSending.set(false);
        this.showReminderConfirm.set(false);
        this.reminderSuccess.set(true);
        this.reminderHistory.update(h => [log, ...h]);
        setTimeout(() => this.reminderSuccess.set(false), 3000);
      },
      error: () => this.reminderSending.set(false),
    });
  }

  onScoreInput(questionId: string, event: Event) {
    const val = parseFloat((event.target as HTMLInputElement).value);
    this.editScores.update(s => ({ ...s, [questionId]: isNaN(val) ? undefined : val }));
  }

  onScoreBlur(questionId: string, event: Event, maxScore: number) {
    const input = event.target as HTMLInputElement;
    const raw = parseFloat(input.value);
    if (!isNaN(raw)) {
      const clamped = Math.min(Math.max(raw, 0), maxScore);
      if (clamped !== raw) {
        input.value = String(clamped);
        this.editScores.update(s => ({ ...s, [questionId]: clamped }));
      }
    }
  }

  onFeedbackInput(questionId: string, event: Event) {
    const val = (event.target as HTMLInputElement).value;
    this.editFeedback.update(f => ({ ...f, [questionId]: val }));
  }

  saveScore(q: ResultQuestion) {
    const r = this.result();
    const s = this.selectedSummary();
    if (!r || !s || !s.submissionId) return;

    const rawScore = this.editScores()[q.questionId] ?? q.score;
    if (rawScore == null) return;
    const scoreVal = Math.min(Math.max(rawScore, 0), q.maxScore);

    const feedbackVal = this.editFeedback()[q.questionId] ?? q.feedback ?? undefined;
    this.saving.set(true);

    const score$ = q.answerId
      ? this.markingSvc.scoreAnswer(r.submissionId, q.answerId, { score: scoreVal, feedback: feedbackVal })
      : this.markingSvc.scoreAnswerByQuestion(r.submissionId, q.questionId, { score: scoreVal, feedback: feedbackVal });

    score$.subscribe({
      next: saved => {
        this.saving.set(false);
        // Refresh the result to show updated score/marking status
        this.markingSvc.getResult(r.submissionId).subscribe({
          next: updated => {
            this.result.set(updated);
            this.editScores.update(e => { const c = {...e}; delete c[q.questionId]; return c; });
            this.editFeedback.update(e => { const c = {...e}; delete c[q.questionId]; return c; });
            // Update marking progress and score in list — reuse the same GROUP-aware
            // count as the detail view's "X/Y questions marked" badge so the two stay
            // consistent (a flat filter() undercounts submissions with GROUP questions).
            const markedCount = this.markedQuestionCount();
            this.submissions.update(list => list.map(sub =>
              sub.submissionId === s.submissionId
                ? {
                    ...sub,
                    markedCount,
                    totalScore: updated.totalScore,
                    maxScore: updated.maxScore,
                  }
                : sub
            ));
          },
        });
      },
      error: () => this.saving.set(false),
    });
  }

  statusClass(s: SubmissionSummary): string {
    if (s.status === 'SUBMITTED') return 'sub-status status-submitted';
    if (s.status === 'AUTO_SUBMITTED') return 'sub-status status-auto';
    if (s.status === 'NOT_STARTED') return 'sub-status status-not-started';
    return 'sub-status status-progress';
  }

  statusLabel(s: SubmissionSummary): string {
    if (s.status === 'SUBMITTED') return 'Submitted';
    if (s.status === 'AUTO_SUBMITTED') return 'Auto-submitted';
    if (s.status === 'NOT_STARTED') return 'Not Started';
    return 'In Progress';
  }

  initials(name: string): string {
    return name.split(' ').slice(0, 2).map(p => p[0]).join('').toUpperCase();
  }

  avatarColor(name: string): string {
    const colors = ['#6366f1', '#8b5cf6', '#ec4899', '#10b981', '#f59e0b', '#3b82f6', '#ef4444', '#14b8a6'];
    return colors[name.charCodeAt(0) % colors.length];
  }

  formatDate(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('en-ZA', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  formatDateTime(iso: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', second: '2-digit',
    });
  }

  flagReasonLabel(reason: FlagReason): string {
    const map: Record<FlagReason, string> = {
      COPIED_ANSWERS: 'Copied Answers',
      TIMING_ANOMALY: 'Timing Anomaly',
      AI_GENERATED_CONTENT: 'AI-Generated Content',
      SUSPICIOUS_BEHAVIOUR: 'Suspicious Behaviour',
      OTHER: 'Other',
    };
    return map[reason] ?? reason;
  }

  markedPercent(s: SubmissionSummary): number {
    if (s.totalAnswers <= 0) return 0;
    return Math.min(100, Math.round((s.markedCount / s.totalAnswers) * 100));
  }

  resultPercent(): number {
    const r = this.result();
    if (!r || r.maxScore <= 0) return 0;
    return Math.round((r.totalScore / r.maxScore) * 100);
  }

  ringDash(): string {
    const circumference = 2 * Math.PI * 34;
    // No fill until marking is complete — a partial score reads as a final one
    const pct = this.result()?.markingStatus === 'FULLY_MARKED' ? this.resultPercent() : 0;
    const filled = (pct / 100) * circumference;
    return filled + ' ' + circumference;
  }

  scorePercent(s: SubmissionSummary): string {
    if (s.maxScore <= 0 || s.markedCount < s.totalAnswers || s.totalAnswers === 0) return '—';
    return Math.round((s.totalScore / s.maxScore) * 100) + '%';
  }

  typeLabel(type: string): string {
    return { MCQ: 'MCQ', TEXT: 'Text', CODE_SUBMISSION: 'Code', GROUP: 'Group' }[type] ?? type;
  }

  markedQuestionCount(): number {
    return (this.result()?.questions ?? []).reduce(
      (sum, q) => sum + (q.questionType === 'GROUP'
        ? (q.subQuestions ?? []).filter(sub => sub.score !== null).length
        : (q.score !== null ? 1 : 0)),
      0
    );
  }

  totalQuestionCount(): number {
    return (this.result()?.questions ?? []).reduce(
      (sum, q) => sum + (q.questionType === 'GROUP' ? (q.subQuestions?.length ?? 0) : 1),
      0
    );
  }
}
