import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { AssessmentService } from '../../core/assessment/assessment.service';
import { AuthService } from '../../core/auth/auth.service';
import { AssessmentPreview } from '../../core/assessment/assessment.model';
import { CandidateTakeService } from '../../core/take/candidate-take.service';
import { AssessmentTakeResponse, SubmitResponse } from '../../core/take/candidate-take.model';
import { CodeEditorComponent } from '../../shared/code-editor/code-editor.component';
import { CodeRunnerPanelComponent } from '../../shared/code-runner/code-runner-panel.component';

@Component({
  selector: 'app-assessment-take',
  imports: [DatePipe, CodeEditorComponent, CodeRunnerPanelComponent],
  template: `
    <div class="take-page">
      @if (loading()) {
        <div class="loading-screen">
          <div class="loading-spinner"></div>
          <span>Loading assessment…</span>
        </div>
      } @else if (error()) {
        <div class="error-screen">
          <p>{{ error() }}</p>
        </div>
      } @else if (phase() === 'submitted') {
        <div class="submitted-screen">
          <div class="submitted-card">
            <div class="submitted-icon">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
            </div>
            <h2 class="submitted-title">Assessment Submitted</h2>
            @if (submitResult(); as r) {
              <p class="submitted-body">
                <strong>{{ r.assessmentTitle }}</strong><br>
                {{ r.answeredCount }} of {{ r.totalQuestionCount }} questions answered.<br>
                Submitted at {{ r.submittedAt | date:'medium' }}.
              </p>
            } @else {
              <p class="submitted-body">Your answers have been recorded. You will be notified once your submission has been reviewed.</p>
            }
          </div>
        </div>
      } @else if (awaitingPassword()) {
        <div class="password-screen">
          <div class="password-card">
            <div class="password-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
              </svg>
            </div>
            <h2 class="password-title">Assessment Protected</h2>
            <p class="password-sub">This assessment requires a password to begin.</p>
            <input type="password" class="password-input" [value]="passwordInput()"
                   (input)="passwordInput.set($any($event.target).value)"
                   (keydown.enter)="submitPassword()"
                   placeholder="Enter password…" />
            @if (passwordError()) {
              <p class="password-error">{{ passwordError() }}</p>
            }
            <button class="password-btn" (click)="submitPassword()" [disabled]="checkingPassword() || !passwordInput()">
              {{ checkingPassword() ? 'Verifying…' : 'Continue →' }}
            </button>
          </div>
        </div>
      } @else if (phase() === 'guide') {
        <div class="guide-screen">
          <div class="guide-card">
            <div class="guide-header">
              <h2 class="guide-title">{{ preview()?.title }}</h2>
              <div class="guide-meta">
                <span class="guide-meta-item">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
                  {{ preview()?.timeLimitMinutes }} minutes
                </span>
                <span class="guide-meta-item">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
                  {{ preview()?.questions?.length ?? 0 }} questions
                </span>
              </div>
            </div>
            <div class="guide-rules">
              <p class="guide-rules-heading">Before you begin</p>
              <ul class="guide-rules-list">
                <li>The timer starts when you click <strong>Start Assessment</strong> and cannot be paused.</li>
                <li>Closing or refreshing the tab does not pause the timer.</li>
                <li>Submit your answers before time runs out — the assessment auto-submits when the timer expires.</li>
                <li>You may end the attempt early at any time using the <strong>Give Up</strong> button.</li>
              </ul>
            </div>
            <button class="guide-start-btn" (click)="beginAssessment()">Start Assessment →</button>
          </div>
        </div>
      } @else if (phase() === 'in-progress') {
        <!-- Submit confirmation modal -->
        @if (showSubmitModal()) {
          <div class="modal-overlay" (click)="cancelSubmit()">
            <div class="modal-card" (click)="$event.stopPropagation()">
              <h3 class="modal-title">Submit assessment?</h3>
              @if (zeroAnswerWarning()) {
                <div class="modal-zero-warning">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                  You have not answered any questions. Are you sure you want to submit?
                </div>
              } @else {
                <p class="modal-body">
                  You have answered {{ answeredCount() }} of {{ preview()!.questions.length }} questions.
                  @if (preview()!.questions.length - answeredCount() > 0) {
                    <br><span class="modal-unanswered">{{ preview()!.questions.length - answeredCount() }} question{{ preview()!.questions.length - answeredCount() === 1 ? '' : 's' }} left unanswered.</span>
                  }
                </p>
              }
              <div class="modal-actions">
                <button class="modal-btn-cancel" (click)="cancelSubmit()">Cancel</button>
                <button class="modal-btn-confirm" (click)="doSubmitFromModal()" [disabled]="submitting()">
                  {{ submitting() ? 'Submitting…' : 'Submit' }}
                </button>
              </div>
            </div>
          </div>
        }

        <!-- Give Up confirmation modal -->
        @if (showGiveUpModal()) {
          <div class="modal-overlay" (click)="cancelGiveUp()">
            <div class="modal-card" (click)="$event.stopPropagation()">
              <h3 class="modal-title">Give up this attempt?</h3>
              <p class="modal-body">Your current answers will be saved, but the attempt will be marked as incomplete. This cannot be undone.</p>
              <div class="modal-actions">
                <button class="modal-btn-cancel" (click)="cancelGiveUp()">Cancel</button>
                <button class="modal-btn-danger" (click)="confirmGiveUp()" [disabled]="submitting()">
                  {{ submitting() ? 'Ending…' : 'Give Up' }}
                </button>
              </div>
            </div>
          </div>
        }

        <!-- Top bar -->
        <div class="topbar">
          <div class="topbar-logo">
            <div class="logo-mark">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M6 4h8a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z"/><path d="M6 12h9a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z"/>
              </svg>
            </div>
            <span class="topbar-title">{{ preview()!.title }}</span>
          </div>
          <div class="topbar-center">
            <div class="timer-pill" [class.timer-amber]="timePercent() < 40 && timePercent() > 15" [class.timer-red]="timePercent() <= 15">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>
              </svg>
              {{ timeDisplay() }}
            </div>
          </div>
          <div class="topbar-right">
            <span class="answered-count">{{ answeredCount() }} / {{ preview()!.questions.length }} answered</span>
            <button class="give-up-btn" (click)="openGiveUpModal()" [disabled]="submitting()">Give Up</button>
            <button class="submit-btn" (click)="confirmSubmit()" [disabled]="submitting()">
              {{ submitting() ? 'Submitting…' : 'Submit Assessment' }}
            </button>
          </div>
        </div>

        <!-- Body -->
        <div class="take-body">
          <!-- Nav panel -->
          <div class="nav-panel">
            <div class="nav-label">Questions</div>
            <div class="nav-grid">
              @for (q of preview()!.questions; track q.id; let i = $index) {
                <button class="nav-cell"
                        [class.nav-current]="currentIndex() === i"
                        [class.nav-answered]="currentIndex() !== i && isAnswered(q.id)"
                        [class.nav-flagged]="flagged().has(q.id)"
                        (click)="currentIndex.set(i)">
                  {{ i + 1 }}
                </button>
              }
            </div>
            <div class="nav-legend">
              <div class="legend-item"><div class="legend-dot dot-current"></div> Current</div>
              <div class="legend-item"><div class="legend-dot dot-answered"></div> Answered</div>
              <div class="legend-item"><div class="legend-dot dot-flagged"></div> Flagged</div>
            </div>
          </div>

          <!-- Question panel -->
          <div class="question-panel">
            @if (currentQuestion(); as q) {
              <div class="question-card">
                <div class="progress-bar-wrap">
                  <div class="progress-bar" [style.width]="progressPercent() + '%'"></div>
                </div>
                <div class="question-meta">
                  <div class="q-position">
                    <span class="q-num">Q{{ currentIndex() + 1 }}</span>
                    <span class="q-total">of {{ preview()!.questions.length }}</span>
                  </div>
                  <span class="type-badge type-{{ q.type.toLowerCase() }}">{{ typeLabel(q.type) }}</span>
                  <span class="pts-badge">{{ q.maxScore === 1 ? '1 pt' : q.maxScore + ' pts' }}</span>
                  <button class="flag-btn" [class.flagged]="flagged().has(q.id)" (click)="toggleFlag(q.id)" title="Flag for review">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><line x1="4" y1="22" x2="4" y2="15"/>
                    </svg>
                    {{ flagged().has(q.id) ? 'Flagged' : 'Flag' }}
                  </button>
                  <button class="clarify-btn" (click)="toggleClarify()" [class.active]="clarifyOpen()" title="Ask what this question means">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                    </svg>
                    Need clarification?
                  </button>
                </div>
                <p class="question-body">{{ q.body }}</p>

                @if (clarifyOpen()) {
                  <div class="clarify-panel">
                    <p class="clarify-hint">
                      Ask what the question means. You'll get a plain-language explanation — not the answer.
                    </p>
                    <textarea
                      class="clarify-note"
                      rows="2"
                      maxlength="500"
                      placeholder="Optional: what specifically is unclear?"
                      [value]="clarifyNote()"
                      (input)="clarifyNote.set($any($event.target).value)"
                      [disabled]="clarifyLoading()"></textarea>
                    <div class="clarify-actions">
                      <button
                        class="clarify-ask-btn"
                        (click)="askClarification(q.id)"
                        [disabled]="clarifyLoading() || clarifyExhausted()">
                        {{ clarifyLoading() ? 'Asking…' : 'Ask' }}
                      </button>
                      @if (clarifyRemaining() !== null) {
                        <span class="clarify-remaining">{{ clarifyRemaining() }} left for this question</span>
                      }
                    </div>
                    @if (clarifyError()) {
                      <p class="clarify-error">{{ clarifyError() }}</p>
                    }
                    @if (clarifyText()) {
                      <div class="clarify-response" [class.degraded]="clarifyDegraded()">{{ clarifyText() }}</div>
                    }
                  </div>
                }

                @if (q.type === 'MCQ' && q.options) {
                  <div class="mcq-options">
                    @for (opt of q.options; track opt.id; let j = $index) {
                      <div class="mcq-option" [class.mcq-selected]="answers()[q.id] === opt.id" (click)="setAnswer(q.id, opt.id)">
                        <div class="option-radio" [class.radio-selected]="answers()[q.id] === opt.id">
                          @if (answers()[q.id] === opt.id) {
                            <div class="radio-dot"></div>
                          }
                        </div>
                        <span class="option-letter">{{ optionLetter(j) }}</span>
                        <span class="option-text">{{ opt.text }}</span>
                      </div>
                    }
                  </div>
                }

                @if (q.type === 'TEXT') {
                  <div class="text-answer">
                    <textarea rows="7"
                              class="answer-textarea"
                              [value]="answers()[q.id] ?? ''"
                              (input)="setAnswer(q.id, $any($event.target).value)"
                              placeholder="Type your answer here…"></textarea>
                    <span class="word-count">{{ wordCount(answers()[q.id]) }} words</span>
                  </div>
                }

                @if (q.type === 'CODE_SUBMISSION') {
                  <div class="code-answer">
                    <div class="code-bar">
                      @if (q.languageHint) {
                        <span class="lang-tag">{{ q.languageHint }}</span>
                      }
                      <span class="code-hint">Your program must declare <code>public class Main</code> with a <code>main</code> method</span>
                    </div>
                    <app-code-editor
                      [value]="answers()[q.id] ?? javaStarter"
                      language="java"
                      height="360px"
                      (valueChange)="setAnswer(q.id, $event)"
                      (runRequested)="runner.run()" />
                    <app-code-runner-panel #runner
                      [code]="answers()[q.id] ?? javaStarter"
                      [sessionToken]="sessionToken()!" />
                  </div>
                }

                @if (q.type === 'GROUP' && q.subQuestions) {
                  <div class="group-sub-questions">
                    @for (sub of q.subQuestions; track sub.id; let si = $index) {
                      <div class="sub-question-block">
                        <div class="sub-q-header">
                          <span class="sub-q-num">{{ si + 1 }}.</span>
                          <span class="type-badge type-{{ sub.type.toLowerCase() }}">{{ typeLabel(sub.type) }}</span>
                          <span class="pts-badge">{{ sub.maxScore === 1 ? '1 pt' : sub.maxScore + ' pts' }}</span>
                          <span class="sub-q-title">{{ sub.body }}</span>
                        </div>
                        @if (sub.type === 'MCQ' && sub.options) {
                          <div class="mcq-options">
                            @for (opt of sub.options; track opt.id; let j = $index) {
                              <div class="mcq-option" [class.mcq-selected]="answers()[sub.id] === opt.id" (click)="setAnswer(sub.id, opt.id)">
                                <div class="option-radio" [class.radio-selected]="answers()[sub.id] === opt.id">
                                  @if (answers()[sub.id] === opt.id) { <div class="radio-dot"></div> }
                                </div>
                                <span class="option-letter">{{ optionLetter(j) }}</span>
                                <span class="option-text">{{ opt.text }}</span>
                              </div>
                            }
                          </div>
                        }
                        @if (sub.type === 'TEXT') {
                          <div class="text-answer">
                            <textarea rows="5" class="answer-textarea"
                                      [value]="answers()[sub.id] ?? ''"
                                      (input)="setAnswer(sub.id, $any($event.target).value)"
                                      placeholder="Type your answer here…"></textarea>
                            <span class="word-count">{{ wordCount(answers()[sub.id]) }} words</span>
                          </div>
                        }
                        @if (sub.type === 'CODE_SUBMISSION') {
                          <div class="code-answer">
                            <div class="code-bar">
                              @if (sub.languageHint) { <span class="lang-tag">{{ sub.languageHint }}</span> }
                              <span class="code-hint">Your program must declare <code>public class Main</code> with a <code>main</code> method</span>
                            </div>
                            <app-code-editor
                              [value]="answers()[sub.id] ?? javaStarter"
                              language="java"
                              height="300px"
                              (valueChange)="setAnswer(sub.id, $event)"
                              (runRequested)="subRunner.run()" />
                            <app-code-runner-panel #subRunner
                              [code]="answers()[sub.id] ?? javaStarter"
                              [sessionToken]="sessionToken()!" />
                          </div>
                        }
                      </div>
                    }
                  </div>
                }

                <div class="question-nav">
                  <button class="nav-btn" (click)="prev()" [disabled]="currentIndex() === 0">← Previous</button>
                  @if (currentIndex() < preview()!.questions.length - 1) {
                    <button class="nav-btn primary" (click)="next()">Next →</button>
                  } @else {
                    <button class="nav-btn primary" (click)="confirmSubmit()">Submit →</button>
                  }
                </div>
              </div>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }

    .take-page {
      min-height: 100vh; background: var(--bg);
      display: flex; flex-direction: column;
    }

    .loading-screen, .error-screen, .submitted-screen {
      flex: 1; display: flex; align-items: center; justify-content: center;
      flex-direction: column; gap: 14px; color: var(--text-2); font-size: 14px;
    }

    .loading-spinner {
      width: 32px; height: 32px; border-radius: 50%;
      border: 3px solid var(--border); border-top-color: var(--accent);
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .submitted-card {
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 40px 48px;
      text-align: center; max-width: 420px;
    }

    .submitted-icon {
      width: 56px; height: 56px; border-radius: 50%;
      background: var(--success-subtle); color: var(--success);
      display: flex; align-items: center; justify-content: center;
      margin: 0 auto 20px;
    }

    .submitted-title { font-size: 20px; font-weight: 700; color: var(--text-1); margin: 0 0 10px; }

    .submitted-body { font-size: 13.5px; color: var(--text-2); line-height: 1.6; margin: 0; }

    /* Guide screen */
    .guide-screen {
      flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px;
    }

    .guide-card {
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 40px 44px;
      width: 100%; max-width: 560px; display: flex; flex-direction: column; gap: 24px;
    }

    .guide-header { display: flex; flex-direction: column; gap: 12px; }

    .guide-title { font-size: 22px; font-weight: 700; color: var(--text-1); margin: 0; }

    .guide-meta { display: flex; gap: 20px; }

    .guide-meta-item {
      display: flex; align-items: center; gap: 6px;
      font-size: 13.5px; color: var(--text-2);
    }

    .guide-rules {
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); padding: 16px 20px;
    }

    .guide-rules-heading {
      font-size: 12px; font-weight: 600; color: var(--text-3);
      text-transform: uppercase; letter-spacing: 0.06em; margin: 0 0 10px;
    }

    .guide-rules-list {
      margin: 0; padding-left: 18px;
      display: flex; flex-direction: column; gap: 7px;
    }

    .guide-rules-list li { font-size: 13.5px; color: var(--text-2); line-height: 1.5; }

    .guide-start-btn {
      padding: 12px 24px; background: var(--accent); color: #fff;
      border: none; border-radius: var(--radius-sm);
      font-size: 14px; font-weight: 600; cursor: pointer;
      font-family: var(--font); transition: background 150ms; align-self: flex-start;
    }
    .guide-start-btn:hover { background: var(--accent-hover); }

    /* Modal overlay */
    .modal-overlay {
      position: fixed; inset: 0; background: rgba(0,0,0,0.5);
      display: flex; align-items: center; justify-content: center;
      z-index: 100; padding: 20px;
    }

    .modal-card {
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 28px 32px;
      width: 100%; max-width: 400px; display: flex; flex-direction: column; gap: 16px;
    }

    .modal-title { font-size: 17px; font-weight: 700; color: var(--text-1); margin: 0; }

    .modal-body { font-size: 13.5px; color: var(--text-2); line-height: 1.6; margin: 0; }

    .modal-unanswered { color: var(--warning); }

    .modal-zero-warning {
      display: flex; align-items: flex-start; gap: 10px;
      padding: 12px 14px; background: var(--danger-subtle);
      border: 1px solid rgba(239,68,68,.3); border-radius: var(--radius-sm);
      font-size: 13.5px; color: var(--danger); line-height: 1.5;
    }

    .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }

    .modal-btn-cancel {
      padding: 8px 18px; background: var(--bg-elevated); color: var(--text-2);
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 500; cursor: pointer; font-family: var(--font);
    }
    .modal-btn-cancel:hover { background: var(--bg-hover); color: var(--text-1); }

    .modal-btn-confirm {
      padding: 8px 18px; background: var(--accent); color: #fff;
      border: none; border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 600; cursor: pointer; font-family: var(--font);
    }
    .modal-btn-confirm:hover:not(:disabled) { background: var(--accent-hover); }
    .modal-btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

    .modal-btn-danger {
      padding: 8px 18px; background: var(--danger); color: #fff;
      border: none; border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 600; cursor: pointer; font-family: var(--font);
    }
    .modal-btn-danger:hover:not(:disabled) { background: #dc2626; }
    .modal-btn-danger:disabled { opacity: 0.5; cursor: not-allowed; }

    /* Topbar */
    .topbar {
      height: 54px; background: var(--bg-card); border-bottom: 1px solid var(--border);
      display: flex; align-items: center; justify-content: space-between;
      padding: 0 20px; flex-shrink: 0; gap: 12px;
    }

    .topbar-logo { display: flex; align-items: center; gap: 10px; }

    .logo-mark {
      width: 30px; height: 30px; border-radius: 7px;
      background: var(--accent); color: #fff;
      display: flex; align-items: center; justify-content: center; flex-shrink: 0;
    }

    .topbar-title { font-size: 14px; font-weight: 600; color: var(--text-1); }

    .topbar-center { flex: 1; display: flex; justify-content: center; }

    .timer-pill {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 5px 14px; border-radius: 999px;
      background: var(--success-subtle); color: var(--success);
      font-size: 13.5px; font-weight: 700; font-family: var(--font-mono);
      transition: background 300ms, color 300ms;
    }
    .timer-amber { background: var(--warning-subtle); color: var(--warning); }
    .timer-red { background: var(--danger-subtle); color: var(--danger); }

    .topbar-right { display: flex; align-items: center; gap: 10px; }

    .answered-count { font-size: 12.5px; color: var(--text-3); white-space: nowrap; }

    .give-up-btn {
      padding: 7px 14px; background: transparent; color: var(--text-2);
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 500; cursor: pointer;
      font-family: var(--font); transition: all 120ms; white-space: nowrap;
    }
    .give-up-btn:hover:not(:disabled) { border-color: var(--danger); color: var(--danger); background: var(--danger-subtle); }
    .give-up-btn:disabled { opacity: 0.5; cursor: not-allowed; }

    .submit-btn {
      padding: 7px 16px; background: var(--accent); color: #fff;
      border: none; border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 600; cursor: pointer;
      font-family: var(--font); transition: background 120ms;
      white-space: nowrap;
    }
    .submit-btn:hover:not(:disabled) { background: var(--accent-hover); }
    .submit-btn:disabled { opacity: 0.5; cursor: not-allowed; }

    /* Body */
    .take-body {
      display: flex; flex: 1; min-height: 0;
    }

    /* Nav panel */
    .nav-panel {
      width: 220px; flex-shrink: 0;
      background: var(--bg-card); border-right: 1px solid var(--border);
      padding: 16px; overflow-y: auto;
    }

    .nav-label { font-size: 11px; font-weight: 600; color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 10px; }

    .nav-grid {
      display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;
      margin-bottom: 16px;
    }

    .nav-cell {
      aspect-ratio: 1; border-radius: var(--radius-sm);
      background: var(--bg-elevated); border: 1px solid var(--border);
      color: var(--text-2); font-size: 12px; font-weight: 500;
      cursor: pointer; font-family: var(--font);
      transition: all 120ms; display: flex; align-items: center; justify-content: center;
    }
    .nav-cell:hover { background: var(--bg-hover); color: var(--text-1); }
    .nav-cell.nav-current { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 700; }
    .nav-cell.nav-answered { background: var(--success-subtle); border-color: rgba(16,185,129,.3); color: var(--success); }
    .nav-cell.nav-flagged { border-color: var(--warning); color: var(--warning); background: var(--warning-subtle); }
    .nav-cell.nav-current.nav-flagged { background: var(--accent); border-color: var(--warning); color: #fff; }

    .nav-legend { display: flex; flex-direction: column; gap: 6px; }

    .legend-item { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--text-3); }

    .legend-dot { width: 10px; height: 10px; border-radius: 3px; }
    .dot-current { background: var(--accent); }
    .dot-answered { background: var(--success); opacity: 0.6; }
    .dot-flagged { background: var(--warning); opacity: 0.6; }

    /* Question panel */
    .question-panel { flex: 1; overflow-y: auto; padding: 24px; }

    .question-card {
      max-width: 720px; background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); overflow: hidden;
    }

    .progress-bar-wrap {
      height: 3px; background: var(--border);
    }
    .progress-bar { height: 100%; background: var(--accent); transition: width 300ms; }

    .question-meta {
      display: flex; align-items: center; gap: 10px;
      padding: 14px 20px 0; flex-wrap: wrap;
    }

    .q-position { display: flex; align-items: baseline; gap: 4px; }
    .q-num { font-size: 14px; font-weight: 700; color: var(--text-1); }
    .q-total { font-size: 12px; color: var(--text-3); }

    .type-badge {
      display: inline-flex; padding: 2px 8px; border-radius: 999px;
      font-size: 11.5px; font-weight: 500;
    }
    .type-mcq { background: var(--accent-subtle); color: var(--accent); }
    .type-text { background: var(--info-subtle); color: var(--info); }
    .type-code_submission { background: rgba(168,85,247,0.13); color: #a855f7; }

    .pts-badge {
      font-size: 11.5px; color: var(--text-3);
      background: var(--bg-elevated); padding: 2px 8px; border-radius: 999px;
    }

    .flag-btn {
      display: inline-flex; align-items: center; gap: 5px; margin-left: auto;
      background: none; border: 1px solid var(--border); border-radius: var(--radius-sm);
      padding: 3px 10px; color: var(--text-3); font-size: 12px;
      cursor: pointer; font-family: var(--font); transition: all 120ms;
    }
    .flag-btn:hover { border-color: var(--warning); color: var(--warning); }
    .flag-btn.flagged { background: var(--warning-subtle); border-color: var(--warning); color: var(--warning); }

    .clarify-btn {
      font-size: 12px; padding: 5px 10px;
      display: inline-flex; align-items: center; gap: 5px;
      background: none; border: 1px solid var(--border); border-radius: var(--radius-sm);
      color: var(--text-2); cursor: pointer; font-family: var(--font); transition: all 120ms;
    }
    .clarify-btn:hover { border-color: var(--accent); color: var(--accent); }
    .clarify-btn.active { background: var(--accent-subtle, rgba(99,102,241,0.1)); border-color: var(--accent); color: var(--accent); }

    .clarify-panel {
      margin: 0 20px 16px; padding: 14px;
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      background: var(--surface-2, rgba(0,0,0,0.02));
    }
    .clarify-hint { margin: 0 0 10px; font-size: 12.5px; color: var(--text-2); }
    .clarify-note {
      width: 100%; box-sizing: border-box; resize: vertical;
      padding: 8px 10px; font-family: var(--font); font-size: 13px;
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      background: var(--surface); color: var(--text-1);
    }
    .clarify-actions { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
    .clarify-ask-btn {
      padding: 6px 16px; font-size: 13px; font-family: var(--font); cursor: pointer;
      background: var(--accent); color: #fff; border: none; border-radius: var(--radius-sm);
    }
    .clarify-ask-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .clarify-remaining { font-size: 12px; color: var(--text-2); }
    .clarify-error { margin: 10px 0 0; font-size: 12.5px; color: var(--danger, #dc2626); }
    .clarify-response {
      margin-top: 12px; padding: 12px; font-size: 13.5px; line-height: 1.6;
      color: var(--text-1); background: var(--surface); border-radius: var(--radius-sm);
      border-left: 3px solid var(--accent); white-space: pre-wrap;
    }
    .clarify-response.degraded { border-left-color: var(--warning); color: var(--text-2); }

    .question-body {
      padding: 16px 20px 18px;
      font-size: 14.5px; color: var(--text-1); line-height: 1.7; margin: 0;
    }

    .mcq-options { display: flex; flex-direction: column; gap: 7px; padding: 0 20px 20px; }

    .mcq-option {
      display: flex; align-items: center; gap: 12px;
      padding: 11px 14px; border: 1px solid var(--border);
      border-radius: var(--radius-sm); cursor: pointer;
      transition: all 120ms;
    }
    .mcq-option:hover { border-color: var(--accent); background: var(--accent-subtle); }
    .mcq-option.mcq-selected { border-color: var(--accent); background: var(--accent-subtle); }

    .option-radio {
      width: 18px; height: 18px; border-radius: 50%;
      border: 2px solid var(--border); flex-shrink: 0;
      display: flex; align-items: center; justify-content: center;
      transition: border-color 120ms;
    }
    .radio-selected { border-color: var(--accent); }
    .radio-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--accent); }

    .option-letter { font-size: 12px; font-weight: 700; color: var(--text-3); width: 18px; }

    .option-text { font-size: 13.5px; color: var(--text-1); }

    .text-answer { padding: 0 20px 20px; display: flex; flex-direction: column; gap: 6px; }

    .answer-textarea {
      width: 100%; padding: 12px 14px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1);
      font-size: 13.5px; resize: vertical; outline: none;
      font-family: var(--font); line-height: 1.65; box-sizing: border-box;
      transition: border-color 150ms;
    }
    .answer-textarea:focus { border-color: var(--accent); }
    .answer-textarea::placeholder { color: var(--text-3); }

    .code-textarea { font-family: var(--font-mono); font-size: 13px; resize: none; }

    .word-count { font-size: 11.5px; color: var(--text-3); text-align: right; }

    .code-answer { padding: 0 20px 20px; display: flex; flex-direction: column; gap: 8px; }

    .code-bar {
      display: flex; align-items: center; gap: 10px;
      padding: 8px 12px; background: var(--bg-elevated);
      border: 1px solid var(--border); border-radius: var(--radius-sm);
    }

    .lang-tag {
      font-size: 11.5px; background: rgba(168,85,247,0.13); color: #a855f7;
      padding: 2px 8px; border-radius: 999px; font-weight: 500;
    }

    .code-hint { font-size: 12px; color: var(--text-3); }

    .group-sub-questions { display: flex; flex-direction: column; gap: 16px; padding: 0 20px 20px; }

    .sub-question-block {
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); padding: 12px 14px;
    }

    .sub-q-header {
      display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
    }

    .sub-q-num { font-size: 12px; font-weight: 700; color: var(--text-3); }
    .sub-q-title { font-size: 13.5px; color: var(--text-1); flex: 1; }

    .type-badge.type-group { background: rgba(20,184,166,0.13); color: #14b8a6; }

    .question-nav {
      display: flex; gap: 8px; justify-content: space-between;
      padding: 14px 20px; border-top: 1px solid var(--border);
    }

    .nav-btn {
      padding: 8px 18px; border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 500; cursor: pointer;
      font-family: var(--font); transition: all 120ms;
      background: var(--bg-elevated); color: var(--text-2); border: 1px solid var(--border);
    }
    .nav-btn:hover:not(:disabled) { background: var(--bg-hover); color: var(--text-1); }
    .nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .nav-btn.primary { background: var(--accent); color: #fff; border-color: var(--accent); }
    .nav-btn.primary:hover:not(:disabled) { background: var(--accent-hover); }

    .password-screen {
      flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px;
    }

    .password-card {
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 40px 36px;
      width: 100%; max-width: 380px; display: flex; flex-direction: column;
      align-items: center; gap: 14px; text-align: center;
    }

    .password-icon {
      width: 52px; height: 52px; border-radius: 50%;
      background: var(--bg-elevated); border: 1px solid var(--border);
      display: flex; align-items: center; justify-content: center; color: var(--text-2);
    }

    .password-title { font-size: 18px; font-weight: 700; color: var(--text-1); margin: 0; }

    .password-sub { font-size: 13.5px; color: var(--text-2); margin: 0; }

    .password-input {
      width: 100%; padding: 10px 14px; box-sizing: border-box;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1); font-size: 14px;
      outline: none; text-align: center; letter-spacing: 0.1em; transition: border-color 150ms;
    }
    .password-input:focus { border-color: var(--accent); }

    .password-error { font-size: 13px; color: var(--danger); margin: 0; }

    .password-btn {
      width: 100%; padding: 10px 14px;
      background: var(--accent); color: #fff; border: none;
      border-radius: var(--radius-sm); font-size: 14px; font-weight: 600;
      cursor: pointer; font-family: var(--font); transition: background 150ms;
    }
    .password-btn:hover:not(:disabled) { background: var(--accent-hover); }
    .password-btn:disabled { opacity: 0.5; cursor: not-allowed; }
  `],
})
export class AssessmentTakeComponent implements OnInit, OnDestroy {
  private readonly svc = inject(AssessmentService);
  private readonly takeSvc = inject(CandidateTakeService);
  private readonly authSvc = inject(AuthService);
  private readonly route = inject(ActivatedRoute);

  readonly phase = signal<'guide' | 'in-progress' | 'submitted'>('guide');
  readonly submitted = computed(() => this.phase() === 'submitted');

  readonly preview = signal<AssessmentPreview | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly submitResult = signal<SubmitResponse | null>(null);

  readonly sessionToken = signal<string | null>(null);
  readonly invitationToken = signal<string | null>(null);
  readonly awaitingPassword = signal(false);
  readonly passwordInput = signal('');
  readonly passwordError = signal('');
  readonly checkingPassword = signal(false);

  readonly showSubmitModal = signal(false);
  readonly showGiveUpModal = signal(false);
  readonly zeroAnswerWarning = signal(false);

  readonly currentIndex = signal(0);
  readonly answers = signal<Record<string, string | undefined>>({});
  readonly flagged = signal<Set<string>>(new Set());
  readonly timeLeft = signal(0);

  // Clarification panel state — scoped to the current question, reset on navigation.
  readonly clarifyOpen = signal(false);
  readonly clarifyLoading = signal(false);
  readonly clarifyNote = signal('');
  readonly clarifyText = signal<string | null>(null);
  readonly clarifyError = signal<string | null>(null);
  readonly clarifyDegraded = signal(false);
  readonly clarifyRemaining = signal<number | null>(null);
  readonly clarifyExhausted = signal(false);

  // Prefilled in the code editor; never autosaved until the candidate edits.
  // "Main" is a contract with the run endpoint, which compiles the file as Main.java.
  readonly javaStarter =
    'public class Main {\n    public static void main(String[] args) {\n        \n    }\n}\n';

  private timerId: ReturnType<typeof setInterval> | null = null;
  private deadlineMs: number | null = null;
  private autosaveTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private assessmentId = '';
  private readonly beforeUnloadHandler = (e: BeforeUnloadEvent) => {
    e.preventDefault();
    e.returnValue = '';
  };

  readonly currentQuestion = computed(() => {
    const p = this.preview();
    if (!p) return null;
    return p.questions[this.currentIndex()] ?? null;
  });

  readonly answeredCount = computed(() => {
    const p = this.preview();
    if (!p) return 0;
    const a = this.answers();
    return p.questions.filter(q => this.isQuestionAnswered(q, a)).length;
  });

  readonly progressPercent = computed(() => {
    const p = this.preview();
    if (!p || p.questions.length === 0) return 0;
    return Math.round(((this.currentIndex() + 1) / p.questions.length) * 100);
  });

  readonly timePercent = computed(() => {
    const p = this.preview();
    if (!p) return 100;
    const total = p.timeLimitMinutes * 60;
    return Math.round((this.timeLeft() / total) * 100);
  });

  readonly timeDisplay = computed(() => {
    const secs = this.timeLeft();
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  });

  ngOnInit() {
    this.assessmentId = this.route.snapshot.paramMap.get('id')!;
    const token = this.route.snapshot.queryParamMap.get('token');

    if (!token) {
      this.error.set('Invalid invitation link. Please check your email for the correct link.');
      return;
    }

    this.invitationToken.set(token);
    this.loading.set(true);

    this.authSvc.validateCandidateToken(token).subscribe({
      next: res => {
        this.sessionToken.set(res.token);
        this.svc.getPreview(this.assessmentId, res.token).subscribe({
          next: p => {
            this.loading.set(false);
            if (p.passwordRequired) {
              this.preview.set(p);
              this.awaitingPassword.set(true);
            } else {
              this.loadAssessmentData(res.token);
            }
          },
          error: () => { this.error.set('Failed to load assessment.'); this.loading.set(false); },
        });
      },
      error: () => {
        this.error.set('Your invitation link has expired or is invalid. Please request a new one.');
        this.loading.set(false);
      },
    });
  }

  submitPassword() {
    const p = this.preview();
    const invToken = this.invitationToken();
    const sessionTok = this.sessionToken();
    if (!p || !invToken || !sessionTok) return;
    this.checkingPassword.set(true);
    this.passwordError.set('');
    this.svc.verifyPassword(p.id, this.passwordInput(), invToken).subscribe({
      next: res => {
        this.checkingPassword.set(false);
        if (res.valid) {
          this.awaitingPassword.set(false);
          this.loadAssessmentData(sessionTok);
        } else {
          this.passwordError.set('Incorrect password. Please try again.');
        }
      },
      error: () => {
        this.checkingPassword.set(false);
        this.passwordError.set('Verification failed. Please try again.');
      },
    });
  }

  private loadAssessmentData(sessionToken: string) {
    this.loading.set(true);
    this.takeSvc.loadAssessment(sessionToken).subscribe({
      next: takeData => {
        this.loading.set(false);
        this.applyTakeResponse(takeData);
      },
      error: err => {
        this.loading.set(false);
        if (err.status === 409) {
          this.phase.set('submitted');
        } else {
          this.error.set('Failed to load assessment.');
        }
      },
    });
  }

  private applyTakeResponse(data: AssessmentTakeResponse) {
    this.preview.set({
      id: data.assessmentId,
      title: data.title,
      description: data.description,
      timeLimitMinutes: 0,
      passwordRequired: false,
      randomiseQuestions: false,
      randomisationQuotas: [],
      questions: data.questions.map(q => ({
        id: q.id,
        type: q.type as any,
        body: q.body,
        maxScore: q.maxScore ?? 1,
        options: q.options
          ? q.options.map(o => ({ id: o.id, text: o.optionText }))
          : null,
        languageHint: null,
        subQuestions: q.subQuestions
          ? q.subQuestions.map(sub => ({
              id: sub.id,
              type: sub.type as any,
              body: sub.body,
              maxScore: sub.maxScore ?? 1,
              options: sub.options
                ? sub.options.map(o => ({ id: o.id, text: o.optionText }))
                : null,
              languageHint: null,
            }))
          : undefined,
      })),
    });

    const preloaded: Record<string, string> = {};
    for (const ans of data.answers) {
      if (ans.selectedOptionIds && ans.selectedOptionIds.length > 0) {
        preloaded[ans.questionId] = ans.selectedOptionIds[0];
      } else if (ans.textContent) {
        preloaded[ans.questionId] = ans.textContent;
      }
    }
    this.answers.set(preloaded);

    this.deadlineMs = new Date(data.deadline).getTime();
    const totalSecs = Math.round(
      (new Date(data.deadline).getTime() - new Date(data.startedAt).getTime()) / 1000
    );
    const secsLeft = Math.max(0, Math.round((this.deadlineMs - Date.now()) / 1000));
    this.preview.update(p => p ? { ...p, timeLimitMinutes: Math.ceil(totalSecs / 60) } : p);
    this.timeLeft.set(secsLeft);

    // Returning candidate: >10 seconds have already elapsed → skip guide
    const isReturning = secsLeft < totalSecs - 10;
    if (isReturning) {
      this.phase.set('in-progress');
      this.startTimer();
      this.addBeforeUnloadListener();
    } else {
      this.phase.set('guide');
    }
  }

  beginAssessment() {
    this.phase.set('in-progress');
    this.startTimer();
    this.addBeforeUnloadListener();
  }

  ngOnDestroy() {
    this.stopTimer();
    this.removeBeforeUnloadListener();
    this.autosaveTimers.forEach(t => clearTimeout(t));
  }

  private addBeforeUnloadListener() {
    window.addEventListener('beforeunload', this.beforeUnloadHandler);
  }

  private removeBeforeUnloadListener() {
    window.removeEventListener('beforeunload', this.beforeUnloadHandler);
  }

  private startTimer() {
    this.timerId = setInterval(() => {
      const secsLeft = this.deadlineMs
        ? Math.max(0, Math.round((this.deadlineMs - Date.now()) / 1000))
        : Math.max(0, this.timeLeft() - 1);
      this.timeLeft.set(secsLeft);
      if (secsLeft <= 0) {
        this.stopTimer();
        this.doSubmit(true);
      }
    }, 1000);
  }

  private stopTimer() {
    if (this.timerId !== null) {
      clearInterval(this.timerId);
      this.timerId = null;
    }
  }

  confirmSubmit() {
    if (!this.preview()) return;
    this.zeroAnswerWarning.set(this.answeredCount() === 0);
    this.showSubmitModal.set(true);
  }

  cancelSubmit() {
    this.showSubmitModal.set(false);
    this.zeroAnswerWarning.set(false);
  }

  doSubmitFromModal() {
    this.showSubmitModal.set(false);
    this.doSubmit(false);
  }

  openGiveUpModal() {
    this.showGiveUpModal.set(true);
  }

  confirmGiveUp() {
    this.showGiveUpModal.set(false);
    this.doSubmit(true);
  }

  cancelGiveUp() {
    this.showGiveUpModal.set(false);
  }

  private doSubmit(autoSubmitted: boolean) {
    const token = this.sessionToken();
    if (!token) return;
    this.stopTimer();
    this.removeBeforeUnloadListener();
    this.autosaveTimers.forEach(t => clearTimeout(t));
    this.autosaveTimers.clear();
    this.submitting.set(true);

    this.takeSvc.submit(token, { autoSubmitted }).subscribe({
      next: result => {
        this.submitting.set(false);
        this.submitResult.set(result);
        this.phase.set('submitted');
      },
      error: () => {
        this.submitting.set(false);
        this.phase.set('submitted');
      },
    });
  }

  setAnswer(questionId: string, value: string) {
    this.answers.update(a => ({ ...a, [questionId]: value }));
    this.scheduleAutosave(questionId);
  }

  private scheduleAutosave(questionId: string) {
    const existing = this.autosaveTimers.get(questionId);
    if (existing) clearTimeout(existing);

    const q = this.findQuestion(questionId);
    const debounceMs = q?.type === 'MCQ' ? 500 : 1000;

    const timer = setTimeout(() => {
      this.autosaveTimers.delete(questionId);
      this.flushAnswer(questionId);
    }, debounceMs);

    this.autosaveTimers.set(questionId, timer);
  }

  private findQuestion(questionId: string): any | undefined {
    const questions = this.preview()?.questions ?? [];
    for (const q of questions) {
      if (q.id === questionId) return q;
      if (q.type === 'GROUP' && q.subQuestions) {
        const sub = (q.subQuestions as any[]).find((s: any) => s.id === questionId);
        if (sub) return sub;
      }
    }
    return undefined;
  }

  private flushAnswer(questionId: string) {
    const token = this.sessionToken();
    if (!token) return;

    const value = this.answers()[questionId];
    const q = this.findQuestion(questionId);
    if (!q) return;

    const input = q.type === 'MCQ'
      ? { questionId, selectedOptionIds: value ? [value] : [] }
      : { questionId, textContent: value ?? '' };

    this.takeSvc.saveAnswers(token, { answers: [input] }).subscribe({
      error: err => {
        if (err.status === 409) {
          this.phase.set('submitted');
        }
      },
    });
  }

  isAnswered(questionId: string): boolean {
    const p = this.preview();
    const q = p?.questions.find(q => q.id === questionId);
    return this.isQuestionAnswered(q as any, this.answers());
  }

  private isQuestionAnswered(q: any, a: Record<string, string | undefined>): boolean {
    if (!q) return false;
    if (q.type === 'GROUP' && q.subQuestions?.length) {
      return q.subQuestions.every((sub: any) => !!(a[sub.id]?.trim()));
    }
    return !!(a[q.id]?.trim());
  }

  toggleFlag(questionId: string) {
    this.flagged.update(set => {
      const next = new Set(set);
      if (next.has(questionId)) next.delete(questionId);
      else next.add(questionId);
      return next;
    });
  }

  prev() {
    if (this.currentIndex() > 0) {
      this.currentIndex.update(i => i - 1);
      this.resetClarify();
    }
  }

  next() {
    const p = this.preview();
    if (p && this.currentIndex() < p.questions.length - 1) {
      this.currentIndex.update(i => i + 1);
      this.resetClarify();
    }
  }

  toggleClarify() {
    this.clarifyOpen.update(open => !open);
  }

  private resetClarify() {
    this.clarifyOpen.set(false);
    this.clarifyLoading.set(false);
    this.clarifyNote.set('');
    this.clarifyText.set(null);
    this.clarifyError.set(null);
    this.clarifyDegraded.set(false);
    this.clarifyRemaining.set(null);
    this.clarifyExhausted.set(false);
  }

  askClarification(questionId: string) {
    const token = this.sessionToken();
    if (!token || this.clarifyLoading() || this.clarifyExhausted()) return;

    this.clarifyLoading.set(true);
    this.clarifyError.set(null);

    this.takeSvc.askClarification(token, questionId, this.clarifyNote().trim() || undefined).subscribe({
      next: res => {
        this.clarifyLoading.set(false);
        this.clarifyText.set(res.clarification);
        this.clarifyDegraded.set(res.degraded);
        this.clarifyRemaining.set(res.remainingForQuestion);
        if (res.remainingForQuestion <= 0) this.clarifyExhausted.set(true);
      },
      error: err => {
        this.clarifyLoading.set(false);
        if (err.status === 429) {
          this.clarifyExhausted.set(true);
          this.clarifyError.set('You have reached the clarification limit for this question.');
        } else {
          this.clarifyError.set('Clarification is unavailable right now. Please answer to the best of your understanding.');
        }
      },
    });
  }

  optionLetter(index: number): string {
    return String.fromCharCode(65 + index);
  }

  wordCount(text: string | undefined): number {
    if (!text?.trim()) return 0;
    return text.trim().split(/\s+/).length;
  }

  typeLabel(type: string): string {
    return { MCQ: 'MCQ', TEXT: 'Text', CODE_SUBMISSION: 'Code' }[type] ?? type;
  }
}
