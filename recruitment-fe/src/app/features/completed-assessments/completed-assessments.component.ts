import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MarkingService } from '../../core/marking/marking.service';
import { SubmissionSummary } from '../../core/marking/marking.model';
import { FeedbackService } from '../../core/feedback/feedback.service';

@Component({
  selector: 'app-completed-assessments',
  standalone: true,
  imports: [DatePipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Completed Assessments</h1>
          <span class="page-sub">{{ filtered().length }} result{{ filtered().length === 1 ? '' : 's' }}</span>
        </div>
        <div class="header-filters">
          <select class="filter-select" [value]="assessmentFilter()" (change)="assessmentFilter.set($any($event.target).value)">
            <option value="">All assessments</option>
            @for (a of assessments(); track a) {
              <option [value]="a">{{ a }}</option>
            }
          </select>
          <button class="filter-chip" [class.active]="passFilter() === 'all'" (click)="passFilter.set('all')">All</button>
          <button class="filter-chip" [class.active]="passFilter() === 'pass'" (click)="passFilter.set('pass')">Pass only</button>
        </div>
      </div>

      <div class="content">
        <div class="card no-pad">
          <table class="table">
            <thead>
              <tr>
                <th>Candidate</th>
                <th>Assessment</th>
                <th class="align-right">Score</th>
                <th class="align-right">%</th>
                <th>Result</th>
                <th>Submitted</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              @if (loading()) {
                <tr><td colspan="7" class="table-empty">Loading…</td></tr>
              } @else if (filtered().length === 0) {
                <tr><td colspan="7" class="table-empty">No completed assessments match your filters.</td></tr>
              } @else {
                @for (s of filtered(); track s.submissionId) {
                  <tr class="clickable" (click)="viewResult(s)">
                    <td>
                      <div class="candidate-name">{{ s.candidateName }}</div>
                    </td>
                    <td class="text-dim">{{ s.assessmentTitle }}</td>
                    <td class="align-right text-dim">
                      @if (s.markedCount >= s.totalAnswers) { {{ s.totalScore }} / {{ s.maxScore }} }
                      @else { — / {{ s.maxScore }} }
                    </td>
                    <td class="align-right">
                      @if (s.markedCount >= s.totalAnswers) {
                        <span class="score-pct">{{ scorePercent(s) }}</span>
                      } @else {
                        <span class="text-dim">—</span>
                      }
                    </td>
                    <td>
                      @if (s.markedCount < s.totalAnswers) {
                        <span class="badge badge-marking">⏳ Marking</span>
                      } @else if (isPassing(s)) {
                        <span class="badge badge-pass">Pass</span>
                      } @else {
                        <span class="badge badge-fail">Fail</span>
                      }
                      @if (s.aiRiskLevel === 'HIGH') {
                        <span class="badge badge-risk-high">AI: High Risk</span>
                      } @else if (s.aiRiskLevel === 'MEDIUM') {
                        <span class="badge badge-risk-medium">AI: Medium Risk</span>
                      }
                    </td>
                    <td class="text-dim">{{ s.submittedAt | date:'MMM d, y' }}</td>
                    <td class="actions-cell" (click)="$event.stopPropagation()">
                      @if (s.submissionId && s.markedCount >= s.totalAnswers) {
                        <button
                          class="action-btn"
                          [disabled]="emailSending() === s.submissionId"
                          (click)="sendFeedbackEmail(s)">
                          @if (emailSending() === s.submissionId) {
                            Sending…
                          } @else if (emailSent().has(s.submissionId!)) {
                            Sent
                          } @else {
                            Send Feedback
                          }
                        </button>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; min-height: 100vh; }

    .page-header {
      height: var(--topbar-height);
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 24px;
      border-bottom: 1px solid var(--border);
      background: var(--bg-card);
      flex-shrink: 0;
    }

    .page-title { font-size: 15px; font-weight: 600; color: var(--text-1); letter-spacing: -0.01em; }
    .page-sub { font-size: 12px; color: var(--text-3); }

    .header-filters { display: flex; gap: 8px; align-items: center; }

    .filter-select {
      padding: 5px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border);
      background: var(--bg-elevated);
      color: var(--text-1);
      font-size: 12.5px;
      font-family: var(--font);
      cursor: pointer;
      outline: none;
    }

    .filter-chip {
      padding: 5px 12px;
      border-radius: 999px;
      border: 1px solid var(--border);
      background: var(--bg-elevated);
      color: var(--text-2);
      font-size: 12.5px;
      font-family: var(--font);
      cursor: pointer;
      transition: all 100ms;
    }
    .filter-chip.active {
      background: var(--accent-subtle);
      color: var(--accent);
      border-color: var(--accent);
      font-weight: 500;
    }

    .content { padding: 24px; flex: 1; }

    .card {
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }
    .card.no-pad { padding: 0; }

    .table { width: 100%; border-collapse: collapse; }
    .table th {
      padding: 9px 16px;
      text-align: left;
      font-size: 11px;
      font-weight: 600;
      color: var(--text-3);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      border-bottom: 1px solid var(--border);
    }
    .table td {
      padding: 11px 16px;
      font-size: 13.5px;
      color: var(--text-1);
      border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    .table tr:last-child td { border-bottom: none; }
    .table tr.clickable { cursor: pointer; transition: background 100ms; }
    .table tr.clickable:hover td { background: var(--bg-hover); }

    .align-right { text-align: right; }
    .table th.align-right { text-align: right; }
    .text-dim { color: var(--text-3); }
    .table-empty { text-align: center; padding: 32px 16px !important; color: var(--text-3); font-size: 13px; }

    .candidate-name { font-weight: 500; }

    .score-pct { font-weight: 600; }

    .badge {
      display: inline-flex;
      align-items: center;
      padding: 3px 9px;
      border-radius: 999px;
      font-size: 11.5px;
      font-weight: 500;
    }
    .badge-pass { background: var(--success-subtle); color: var(--success); }
    .badge-fail { background: var(--danger-subtle, rgba(239,68,68,.08)); color: var(--danger, #ef4444); }
    .badge-marking { background: var(--warning-subtle); color: var(--warning); }
    .badge-risk-high { background: rgba(239,68,68,.1); color: #dc2626; margin-left: 4px; }
    .badge-risk-medium { background: rgba(234,179,8,.12); color: #b45309; margin-left: 4px; }

    .actions-cell { white-space: nowrap; }

    .action-btn {
      padding: 5px 12px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border);
      background: var(--bg-elevated);
      color: var(--text-2);
      font-size: 12px;
      font-family: var(--font);
      cursor: pointer;
      transition: all 100ms;
    }
    .action-btn:hover:not(:disabled) {
      background: var(--accent-subtle);
      color: var(--accent);
      border-color: var(--accent);
    }
    .action-btn:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  `],
})
export class CompletedAssessmentsComponent implements OnInit {
  private readonly markingService = inject(MarkingService);
  private readonly feedbackService = inject(FeedbackService);
  private readonly router = inject(Router);

  readonly submissions = signal<SubmissionSummary[]>([]);
  readonly loading = signal(true);
  readonly assessmentFilter = signal('');
  readonly passFilter = signal<'all' | 'pass'>('all');
  readonly emailSending = signal<string | null>(null);
  readonly emailSent = signal<Set<string>>(new Set());

  readonly assessments = computed(() =>
    [...new Set(this.submissions().map(s => s.assessmentTitle))].sort(),
  );

  readonly filtered = computed(() => {
    let list = this.submissions();
    const af = this.assessmentFilter();
    if (af) list = list.filter(s => s.assessmentTitle === af);
    if (this.passFilter() === 'pass') list = list.filter(s => this.isPassing(s));
    return list;
  });

  ngOnInit() {
    this.markingService.listCompletedSubmissions().subscribe({
      next: list => { this.submissions.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  isPassing(s: SubmissionSummary): boolean {
    return s.markedCount >= s.totalAnswers && s.maxScore > 0 && s.totalScore / s.maxScore >= 0.5;
  }

  scorePercent(s: SubmissionSummary): string {
    if (s.maxScore === 0) return '—';
    return Math.round((s.totalScore / s.maxScore) * 100) + '%';
  }

  viewResult(s: SubmissionSummary): void {
    if (s.submissionId) {
      this.router.navigate(['/results'], { queryParams: { submission: s.submissionId } });
    }
  }

  sendFeedbackEmail(s: SubmissionSummary): void {
    if (!s.submissionId || this.emailSending()) return;
    this.emailSending.set(s.submissionId);
    this.feedbackService.sendFeedbackEmail(s.submissionId).subscribe({
      next: () => {
        this.emailSent.update(set => new Set(set).add(s.submissionId!));
        this.emailSending.set(null);
      },
      error: () => {
        this.emailSending.set(null);
      },
    });
  }
}
