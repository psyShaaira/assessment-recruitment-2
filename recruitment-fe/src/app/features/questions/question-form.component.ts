import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { QuestionService } from '../../core/question/question.service';
import { Difficulty, Question, QuestionRequest, QuestionType } from '../../core/question/question.model';
import { AiService } from '../../core/ai/ai.service';
import { ToastService } from '../../core/toast/toast.service';

interface NewSubQuestionDraft {
  type: 'MCQ' | 'TEXT' | 'CODE_SUBMISSION';
  title: string;
  body: string;
  maxScore: number;
  options?: { text: string; correct: boolean }[];
  languageHint?: string;
}

type SubQuestionEntry =
  | { source: 'bank'; question: Question }
  | { source: 'new'; draft: NewSubQuestionDraft };

@Component({
  selector: 'app-question-form',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1 class="page-title">{{ editId() ? 'Edit Question' : 'Add Question' }}</h1>
        </div>
        <a routerLink="/questions" class="btn btn-ghost btn-sm">← Back to Bank</a>
      </div>

      <div class="content">
        <div class="form-card">

          <!-- Edit guard for GROUP questions -->
          @if (groupEditBlocked()) {
            <div class="info-banner">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              Group questions cannot be edited via this form. To change the sub-questions, delete this question and recreate it.
            </div>
            <div class="form-actions" style="border-top: none; padding-top: 0; margin-top: 0;">
              <a routerLink="/questions" class="btn btn-secondary">← Back to Bank</a>
            </div>
          } @else {

          <form [formGroup]="form" (ngSubmit)="submit()">

            <div class="field">
              <label class="field-label">Question Type</label>
              <div class="type-selector">
                @for (t of typeOptions; track t.value) {
                  <button type="button" class="type-btn" [class.active]="form.get('type')?.value === t.value"
                    (click)="setType(t.value)">{{ t.label }}</button>
                }
              </div>
            </div>

            @if (form.get('type')?.value !== 'GROUP') {
              <div class="ai-panel">
                <div class="ai-panel-header">
                  <span class="ai-panel-title">✨ Generate with AI</span>
                </div>
                <div class="ai-panel-body">
                  <div class="field">
                    <label class="field-label">Topic <span class="required">*</span></label>
                    <input class="field-input" [value]="aiTopic()"
                      (input)="aiTopic.set($any($event.target).value)"
                      placeholder="e.g. Java streams, SQL joins, binary search"/>
                  </div>
                  <div class="ai-panel-row">
                    <div class="field" style="flex: 1;">
                      <label class="field-label">Difficulty</label>
                      <div class="type-selector">
                        @for (d of aiDifficultyOptions; track d.value) {
                          <button type="button" class="type-btn"
                            [class.active]="aiDifficulty() === d.value"
                            (click)="aiDifficulty.set(d.value)">{{ d.label }}</button>
                        }
                      </div>
                    </div>
                    <div class="field" style="max-width: 90px;">
                      <label class="field-label">Count</label>
                      <input type="number" class="field-input" [value]="aiCount()"
                        (input)="aiCount.set(+$any($event.target).value)" min="1" max="5"/>
                    </div>
                  </div>
                  <button type="button" class="btn btn-secondary"
                    [disabled]="aiGenerating() || !aiTopic().trim()"
                    (click)="generateWithAi()">
                    {{ aiGenerating() ? 'Generating…' : 'Generate' }}
                  </button>

                  @if (aiDrafts().length > 0) {
                    <div class="ai-draft-nav">
                      <span>
                        Draft {{ aiDraftIndex() + 1 }} of {{ aiDrafts().length }}
                        @if (aiSavedDrafts().has(aiDraftIndex())) {
                          <span class="ai-draft-saved-badge">✓ Saved</span>
                        } @else {
                          <span class="ai-draft-unsaved-badge">Unsaved</span>
                        }
                      </span>
                      @if (aiDrafts().length > 1) {
                        <div class="ai-draft-nav-btns">
                          <button type="button" class="btn btn-ghost btn-sm"
                            [disabled]="aiDraftIndex() === 0"
                            (click)="showAiDraft(aiDraftIndex() - 1)">← Prev</button>
                          <button type="button" class="btn btn-ghost btn-sm"
                            [disabled]="aiDraftIndex() === aiDrafts().length - 1"
                            (click)="showAiDraft(aiDraftIndex() + 1)">Next →</button>
                        </div>
                      }
                    </div>
                    @if (aiDrafts().length > 1 && aiUnsavedCount() > 0) {
                      <button type="button" class="btn btn-secondary btn-sm"
                        [disabled]="aiSavingAll() || aiUnsavedCount() === 0"
                        (click)="saveAllDrafts()">
                        {{ aiSavingAll() ? 'Saving all…' : 'Save All ' + aiUnsavedCount() + ' Drafts' }}
                      </button>
                    }
                  }
                </div>
              </div>
            }

            <div class="field">
              <label class="field-label">Question Title <span class="required">*</span></label>
              <input formControlName="title" class="field-input"
                [placeholder]="form.get('type')?.value === 'GROUP'
                  ? 'e.g. Database scenario — query optimisation'
                  : 'e.g. What is the time complexity of binary search?'"/>
              @if (form.get('title')?.invalid && form.get('title')?.touched) {
                <span class="field-err">Title is required</span>
              }
            </div>

            <div class="field">
              <label class="field-label">
                {{ form.get('type')?.value === 'GROUP' ? 'Scenario Preamble' : 'Question Body' }}
                <span class="required">*</span>
              </label>
              <textarea formControlName="body" class="field-textarea" rows="4"
                [placeholder]="form.get('type')?.value === 'GROUP'
                  ? 'Describe the scenario or context that candidates will read before answering the sub-questions…'
                  : 'Detailed question description…'"></textarea>
              @if (form.get('body')?.invalid && form.get('body')?.touched) {
                <span class="field-err">{{ form.get('type')?.value === 'GROUP' ? 'Preamble' : 'Body' }} is required</span>
              }
            </div>

            <div class="field">
              <label class="field-label">Tags <span class="field-hint-inline">(comma-separated)</span></label>
              <input formControlName="tagsRaw" class="field-input" placeholder="e.g. algorithms, java, sql"/>
            </div>

            @if (form.get('type')?.value !== 'GROUP') {
              <div class="field" style="max-width: 160px;">
                <label class="field-label">Points <span class="field-hint-inline">(max score)</span></label>
                <input type="number" formControlName="maxScore" class="field-input" min="1" step="1"/>
                @if (form.get('maxScore')?.invalid && form.get('maxScore')?.touched) {
                  <span class="field-err">Must be at least 1</span>
                }
              </div>
            } @else {
              <div class="field" style="max-width: 160px;">
                <label class="field-label">Total Points</label>
                <div class="total-points">{{ totalPoints() }} pts</div>
              </div>
            }

            <div class="field">
              <label class="field-label">Difficulty</label>
              <div class="type-selector">
                @for (d of difficultyOptions; track d.value) {
                  <button type="button" class="type-btn"
                    [class.active]="form.get('difficulty')?.value === d.value"
                    (click)="setDifficulty(d.value)">{{ d.label }}</button>
                }
              </div>
            </div>

            <!-- CODE_SUBMISSION: language hint -->
            @if (form.get('type')?.value === 'CODE_SUBMISSION') {
              <div class="field">
                <label class="field-label">Language Hint</label>
                <input formControlName="languageHint" class="field-input" placeholder="e.g. java, python, javascript"/>
              </div>
            }

            <!-- MCQ: answer options -->
            @if (form.get('type')?.value === 'MCQ') {
              <div class="field">
                <label class="field-label">Answer Options <span class="field-hint-inline">(select the correct one)</span></label>
                <div formArrayName="options" class="options-list">
                  @for (opt of options.controls; track opt; let i = $index) {
                    <div [formGroupName]="i" class="option-row">
                      <div class="radio-wrap" (click)="markCorrect(i)">
                        <div class="radio-circle" [class.selected]="opt.get('correct')?.value">
                          @if (opt.get('correct')?.value) {
                            <div class="radio-dot"></div>
                          }
                        </div>
                        <span class="option-letter">{{ optionLetter(i) }}</span>
                      </div>
                      <input type="text" formControlName="text" class="field-input opt-input" [placeholder]="'Option ' + optionLetter(i)"/>
                      <button type="button" class="icon-btn" (click)="removeOption(i)" [disabled]="options.length <= 2" title="Remove option">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M18 6L6 18M6 6l12 12"/>
                        </svg>
                      </button>
                    </div>
                  }
                </div>
                <button type="button" class="add-option-btn" (click)="addOption()">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
                  Add option
                </button>
                @if (mcqError()) {
                  <span class="field-err">{{ mcqError() }}</span>
                }
              </div>
            }

            <!-- GROUP: member question picker -->
            @if (form.get('type')?.value === 'GROUP') {
              <div class="field">
                <label class="field-label">
                  Sub-questions
                  <span class="field-hint-inline">(add 2 or more from the question bank)</span>
                </label>

                <!-- Selected sub-questions list -->
                @if (subQuestionEntries().length > 0) {
                  <div class="members-list">
                    @for (entry of subQuestionEntries(); track $index; let i = $index) {
                      <div class="member-row">
                        <span class="member-num">{{ i + 1 }}</span>
                        @if (entry.source === 'bank') {
                          <span class="type-badge type-{{ entry.question.type.toLowerCase() }}">{{ typeLabel(entry.question.type) }}</span>
                          <span class="member-title">{{ entry.question.title }}</span>
                        } @else {
                          <span class="type-badge type-{{ entry.draft.type.toLowerCase() }}">{{ typeLabel(entry.draft.type) }}</span>
                          <span class="member-title">{{ entry.draft.title }}</span>
                          <span class="new-badge">New</span>
                        }
                        <button type="button" class="icon-btn" (click)="removeEntry(i)" title="Remove">
                          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M18 6L6 18M6 6l12 12"/>
                          </svg>
                        </button>
                      </div>
                    }
                  </div>
                }

                @if (memberError()) {
                  <span class="field-err">{{ memberError() }}</span>
                }

                <!-- Bank picker -->
                <div class="bank-picker">
                  <div class="bank-search-wrap">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
                    <input class="bank-search-input"
                      [value]="bankSearch()"
                      (input)="bankSearch.set($any($event.target).value)"
                      placeholder="Search question bank…" />
                  </div>

                  @if (bankLoading()) {
                    <div class="bank-state-msg">Loading question bank…</div>
                  } @else if (bankError()) {
                    <div class="bank-state-msg bank-state-err">{{ bankError() }}</div>
                  } @else if (filteredBank().length === 0) {
                    <div class="bank-state-msg">
                      {{ allQuestions().length === 0 ? 'No questions in the bank yet.' : 'No matching questions available.' }}
                    </div>
                  } @else {
                    <div class="bank-results">
                      @for (q of filteredBank(); track q.id) {
                        <div class="bank-row">
                          <span class="type-badge type-{{ q.type.toLowerCase() }}">{{ typeLabel(q.type) }}</span>
                          <span class="bank-row-title">{{ q.title }}</span>
                          <button type="button" class="add-btn" (click)="addMember(q)">
                            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
                            Add
                          </button>
                        </div>
                      }
                    </div>
                  }
                </div>

                <!-- Inline create-new-sub-question mini-form -->
                <div [formGroup]="newSubForm" class="new-sub-form">
                  <div class="new-sub-form-title">Create a new sub-question</div>

                  <div class="field">
                    <label class="field-label">Type</label>
                    <div class="type-selector">
                      @for (t of subTypeOptions; track t.value) {
                        <button type="button" class="type-btn"
                          [class.active]="newSubForm.get('type')?.value === t.value"
                          (click)="setNewSubType(t.value)">{{ t.label }}</button>
                      }
                    </div>
                  </div>

                  <div class="field">
                    <label class="field-label">Title <span class="required">*</span></label>
                    <input formControlName="title" class="field-input" placeholder="e.g. What is a foreign key?"/>
                    @if (newSubForm.get('title')?.invalid && newSubForm.get('title')?.touched) {
                      <span class="field-err">Title is required</span>
                    }
                  </div>

                  <div class="field">
                    <label class="field-label">Body <span class="required">*</span></label>
                    <textarea formControlName="body" class="field-textarea" rows="3" placeholder="Question body…"></textarea>
                    @if (newSubForm.get('body')?.invalid && newSubForm.get('body')?.touched) {
                      <span class="field-err">Body is required</span>
                    }
                  </div>

                  <div class="field" style="max-width: 160px;">
                    <label class="field-label">Points <span class="field-hint-inline">(max score)</span></label>
                    <input type="number" formControlName="maxScore" class="field-input" min="1" step="1"/>
                    @if (newSubForm.get('maxScore')?.invalid && newSubForm.get('maxScore')?.touched) {
                      <span class="field-err">Must be at least 1</span>
                    }
                  </div>

                  @if (newSubForm.get('type')?.value === 'CODE_SUBMISSION') {
                    <div class="field">
                      <label class="field-label">Language Hint</label>
                      <input formControlName="languageHint" class="field-input" placeholder="e.g. java, python, javascript"/>
                    </div>
                  }

                  @if (newSubForm.get('type')?.value === 'MCQ') {
                    <div class="field">
                      <label class="field-label">Answer Options <span class="field-hint-inline">(select the correct one)</span></label>
                      <div formArrayName="options" class="options-list">
                        @for (opt of newSubOptions.controls; track opt; let i = $index) {
                          <div [formGroupName]="i" class="option-row">
                            <div class="radio-wrap" (click)="markSubCorrect(i)">
                              <div class="radio-circle" [class.selected]="opt.get('correct')?.value">
                                @if (opt.get('correct')?.value) {
                                  <div class="radio-dot"></div>
                                }
                              </div>
                              <span class="option-letter">{{ optionLetter(i) }}</span>
                            </div>
                            <input type="text" formControlName="text" class="field-input opt-input" [placeholder]="'Option ' + optionLetter(i)"/>
                            <button type="button" class="icon-btn" (click)="removeSubOption(i)" [disabled]="newSubOptions.length <= 2" title="Remove option">
                              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round">
                                <path d="M18 6L6 18M6 6l12 12"/>
                              </svg>
                            </button>
                          </div>
                        }
                      </div>
                      <button type="button" class="add-option-btn" (click)="addSubOption()">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
                        Add option
                      </button>
                    </div>
                  }

                  @if (newSubError()) {
                    <span class="field-err">{{ newSubError() }}</span>
                  }

                  <button type="button" class="add-option-btn" (click)="addSubQuestion()">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
                    Add sub-question
                  </button>
                </div>
              </div>
            }

            @if (error()) {
              <div class="error-banner">{{ error() }}</div>
            }

            <div class="form-actions">
              <a routerLink="/questions" class="btn btn-secondary">Cancel</a>
              <button type="submit" class="btn btn-primary" [disabled]="saving()">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
                {{ saving() ? 'Saving…' : (editId() ? 'Save Changes' : 'Create Question') }}
              </button>
            </div>
          </form>

          } <!-- end @else groupEditBlocked -->
        </div>
      </div>
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; min-height: 100vh; }

    .page-header {
      height: var(--topbar-height);
      display: flex; align-items: center; justify-content: space-between;
      padding: 0 24px; border-bottom: 1px solid var(--border);
      background: var(--bg-card); flex-shrink: 0;
    }

    .page-title { font-size: 15px; font-weight: 600; color: var(--text-1); letter-spacing: -0.01em; }

    .btn {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 7px 14px; border-radius: var(--radius-sm);
      font-size: 13px; font-weight: 500; cursor: pointer;
      border: 1px solid transparent; transition: all 120ms;
      text-decoration: none; white-space: nowrap;
    }
    .btn-sm { padding: 5px 11px; font-size: 12px; }
    .btn-primary { background: var(--accent); color: #fff; }
    .btn-primary:hover:not(:disabled) { background: var(--accent-hover); }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-secondary { background: var(--bg-elevated); color: var(--text-1); border-color: var(--border); }
    .btn-secondary:hover { background: var(--bg-hover); }
    .btn-ghost { background: transparent; color: var(--text-2); }
    .btn-ghost:hover { background: var(--bg-hover); color: var(--text-1); }

    .content { padding: 24px; overflow-y: auto; flex: 1; }

    .form-card {
      max-width: 640px;
      background: var(--bg-card); border: 1px solid var(--border);
      border-radius: var(--radius-lg); padding: 24px;
    }

    .field { margin-bottom: 18px; }

    .field-label { display: block; font-size: 13px; font-weight: 500; color: var(--text-2); margin-bottom: 6px; }

    .field-hint-inline { font-weight: 400; color: var(--text-3); font-size: 11.5px; }

    .required { color: var(--danger); }

    .field-input {
      width: 100%; padding: 8px 12px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1);
      font-size: 13.5px; outline: none; transition: border-color 150ms;
    }
    .field-input:focus { border-color: var(--accent); }
    .field-input::placeholder { color: var(--text-3); }

    .field-textarea {
      width: 100%; padding: 8px 12px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm); color: var(--text-1);
      font-size: 13.5px; outline: none; resize: vertical; line-height: 1.6; transition: border-color 150ms;
    }
    .field-textarea:focus { border-color: var(--accent); }
    .field-textarea::placeholder { color: var(--text-3); }

    .field-err { font-size: 11.5px; color: var(--danger); margin-top: 4px; display: block; }

    .type-selector { display: flex; gap: 6px; flex-wrap: wrap; }

    .type-btn {
      flex: 1; padding: 7px; min-width: 90px;
      background: var(--bg-elevated); color: var(--text-2);
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      cursor: pointer; font-family: var(--font); font-size: 13px; font-weight: 400;
      transition: all 120ms;
    }
    .type-btn:hover { background: var(--bg-hover); color: var(--text-1); }
    .type-btn.active { background: var(--accent-subtle); color: var(--accent); border-color: var(--accent); font-weight: 600; }

    .options-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }

    .option-row { display: flex; align-items: center; gap: 8px; }

    .radio-wrap { display: flex; align-items: center; gap: 8px; cursor: pointer; }

    .radio-circle {
      width: 16px; height: 16px; border-radius: 50%;
      border: 2px solid var(--border);
      display: flex; align-items: center; justify-content: center; flex-shrink: 0;
      transition: border-color 120ms;
    }
    .radio-circle.selected { border-color: var(--accent); }
    .radio-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
    .option-letter { font-size: 12px; font-weight: 600; color: var(--text-3); width: 18px; }
    .opt-input { flex: 1; }

    .icon-btn {
      background: none; border: none; cursor: pointer; padding: 4px;
      border-radius: 4px; display: flex; align-items: center; color: var(--text-3);
      transition: color 120ms, background 120ms; flex-shrink: 0;
    }
    .icon-btn:hover { color: var(--danger); background: var(--danger-subtle); }
    .icon-btn:disabled { opacity: 0.3; cursor: default; }

    .add-option-btn {
      display: flex; align-items: center; gap: 6px;
      background: none; border: 1px dashed var(--border);
      border-radius: var(--radius-sm); padding: 6px 14px;
      cursor: pointer; color: var(--accent); font-size: 12.5px;
      font-family: var(--font); transition: all 120ms;
    }
    .add-option-btn:hover { background: var(--accent-subtle); border-color: var(--accent); }

    /* GROUP: selected members */
    .members-list {
      display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px;
    }

    .member-row {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 10px;
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm);
    }

    .member-num {
      font-size: 11px; font-weight: 700; color: var(--text-3);
      width: 18px; flex-shrink: 0; text-align: center;
    }

    .member-title { font-size: 13px; color: var(--text-1); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .new-badge {
      display: inline-flex; padding: 2px 7px; border-radius: 999px;
      font-size: 11px; font-weight: 500; flex-shrink: 0;
      background: var(--success-subtle); color: var(--success);
    }

    .total-points {
      padding: 8px 12px; font-size: 13.5px; font-weight: 600; color: var(--text-1);
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: var(--radius-sm);
    }

    /* GROUP: inline create-new-sub-question form */
    .new-sub-form {
      margin-top: 12px; padding: 14px;
      border: 1px dashed var(--border); border-radius: var(--radius-sm);
      background: var(--bg-elevated);
    }

    .new-sub-form-title {
      font-size: 13px; font-weight: 600; color: var(--text-1); margin-bottom: 12px;
    }

    /* GROUP: bank picker */
    .bank-picker {
      border: 1px solid var(--border); border-radius: var(--radius-sm);
      overflow: hidden;
    }

    .bank-search-wrap {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 12px; border-bottom: 1px solid var(--border);
      background: var(--bg-elevated); color: var(--text-3);
    }

    .bank-search-input {
      flex: 1; background: transparent; border: none; outline: none;
      color: var(--text-1); font-size: 13px; font-family: var(--font);
    }
    .bank-search-input::placeholder { color: var(--text-3); }

    .bank-results { max-height: 220px; overflow-y: auto; }

    .bank-row {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 12px; border-bottom: 1px solid var(--border);
      transition: background 100ms;
    }
    .bank-row:last-child { border-bottom: none; }
    .bank-row:hover { background: var(--bg-hover); }

    .bank-row-title { flex: 1; font-size: 13px; color: var(--text-1); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .add-btn {
      display: inline-flex; align-items: center; gap: 4px; flex-shrink: 0;
      padding: 4px 10px; border-radius: var(--radius-sm);
      background: var(--accent-subtle); color: var(--accent);
      border: 1px solid transparent; font-size: 12px; font-weight: 500;
      cursor: pointer; font-family: var(--font); transition: all 120ms;
    }
    .add-btn:hover { background: var(--accent); color: #fff; }

    .bank-state-msg {
      padding: 16px 12px; font-size: 12.5px; color: var(--text-3); text-align: center;
    }
    .bank-state-err { color: var(--danger); }

    /* type badges (reused from assessment builder) */
    .type-badge {
      display: inline-flex; padding: 2px 7px; border-radius: 999px;
      font-size: 11px; font-weight: 500; flex-shrink: 0;
    }
    .type-mcq { background: var(--accent-subtle); color: var(--accent); }
    .type-text { background: var(--info-subtle); color: var(--info); }
    .type-code_submission { background: rgba(168,85,247,0.13); color: #a855f7; }
    .type-group { background: rgba(20,184,166,0.13); color: #14b8a6; }

    /* info / edit-blocked banner */
    .info-banner {
      display: flex; align-items: flex-start; gap: 8px;
      padding: 12px 14px; margin-bottom: 20px;
      background: var(--info-subtle); border: 1px solid rgba(59,130,246,.2);
      border-radius: var(--radius-sm); color: var(--info); font-size: 13px;
      line-height: 1.5;
    }
    .info-banner svg { flex-shrink: 0; margin-top: 1px; }

    .error-banner {
      padding: 10px 14px; background: var(--danger-subtle);
      border: 1px solid rgba(239,68,68,.25); border-radius: var(--radius-sm);
      color: var(--danger); font-size: 13px; margin-bottom: 16px;
    }

    /* AI generation panel */
    .ai-panel {
      margin-bottom: 20px; padding: 14px;
      border: 1px solid var(--accent); border-radius: var(--radius-sm);
      background: var(--accent-subtle);
    }
    .ai-panel-header { margin-bottom: 10px; }
    .ai-panel-title { font-size: 13px; font-weight: 600; color: var(--accent); }
    .ai-panel-body .field { margin-bottom: 10px; }
    .ai-panel-row { display: flex; gap: 10px; }
    .ai-draft-nav {
      display: flex; align-items: center; justify-content: space-between;
      gap: 10px; margin-top: 10px; padding-top: 10px;
      border-top: 1px solid rgba(255,255,255,.1); font-size: 12px; color: var(--text-2);
    }
    .ai-draft-nav-btns { display: flex; gap: 6px; flex-shrink: 0; }
    .ai-draft-saved-badge { color: var(--success, #22c55e); font-weight: 600; margin-left: 6px; }
    .ai-draft-unsaved-badge { color: var(--warning, #eab308); font-weight: 500; margin-left: 6px; }

    .form-actions {
      display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px;
      padding-top: 16px; border-top: 1px solid var(--border);
    }
  `],
})
export class QuestionFormComponent implements OnInit {
  private readonly svc = inject(QuestionService);
  private readonly aiSvc = inject(AiService);
  private readonly toastSvc = inject(ToastService);
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly editId = signal<string | null>(null);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly mcqError = signal<string | null>(null);

  // ── AI generation state ─────────────────────────────────────────────────
  readonly aiTopic = signal('');
  readonly aiDifficulty = signal<Difficulty>('MEDIUM');
  readonly aiCount = signal(1);
  readonly aiGenerating = signal(false);
  readonly aiDrafts = signal<QuestionRequest[]>([]);
  readonly aiDraftIndex = signal(0);
  readonly aiSavedDrafts = signal<Set<number>>(new Set());
  readonly aiSavingAll = signal(false);

  readonly aiUnsavedCount = computed(() => {
    const total = this.aiDrafts().length;
    return total - this.aiSavedDrafts().size;
  });

  readonly aiDifficultyOptions: { value: Difficulty; label: string }[] = [
    { value: 'EASY', label: 'Easy' },
    { value: 'MEDIUM', label: 'Medium' },
    { value: 'HARD', label: 'Hard' },
  ];

  // ── GROUP-specific state ────────────────────────────────────────────────
  readonly allQuestions = signal<Question[]>([]);
  readonly subQuestionEntries = signal<SubQuestionEntry[]>([]);
  readonly bankSearch = signal('');
  readonly bankLoading = signal(false);
  readonly bankError = signal<string | null>(null);
  readonly memberError = signal<string | null>(null);
  readonly newSubError = signal<string | null>(null);
  readonly groupEditBlocked = signal(false);

  readonly filteredBank = computed(() => {
    const search = this.bankSearch().toLowerCase();
    const bankIds = new Set(
      this.subQuestionEntries()
        .filter((e): e is { source: 'bank'; question: Question } => e.source === 'bank')
        .map(e => e.question.id)
    );
    return this.allQuestions()
      .filter(q => q.type !== 'GROUP')           // no nested groups
      .filter(q => !bankIds.has(q.id))            // not already added
      .filter(q => !search || q.title.toLowerCase().includes(search));
  });

  readonly totalPoints = computed(() =>
    this.subQuestionEntries().reduce(
      (sum, e) => sum + (e.source === 'bank' ? e.question.maxScore : e.draft.maxScore), 0)
  );

  readonly form = this.fb.group({
    type: ['MCQ' as QuestionType, Validators.required],
    title: ['', Validators.required],
    body: ['', Validators.required],
    tagsRaw: [''],
    languageHint: [''],
    maxScore: [1, [Validators.required, Validators.min(1)]],
    difficulty: [null as Difficulty | null],
    options: this.fb.array([this.makeOption('', true), this.makeOption('', false)]),
  });

  // ── Inline create-new-sub-question mini-form ────────────────────────────
  readonly newSubForm = this.fb.group({
    type: ['TEXT' as 'MCQ' | 'TEXT' | 'CODE_SUBMISSION', Validators.required],
    title: ['', Validators.required],
    body: ['', Validators.required],
    maxScore: [1, [Validators.required, Validators.min(1)]],
    languageHint: [''],
    options: this.fb.array([this.makeOption('', true), this.makeOption('', false)]),
  });

  readonly typeOptions = [
    { value: 'MCQ', label: 'Multiple Choice' },
    { value: 'TEXT', label: 'Text Response' },
    { value: 'CODE_SUBMISSION', label: 'Code Submission' },
    { value: 'GROUP', label: 'Group / Scenario' },
  ];

  readonly subTypeOptions = [
    { value: 'MCQ', label: 'Multiple Choice' },
    { value: 'TEXT', label: 'Text Response' },
    { value: 'CODE_SUBMISSION', label: 'Code Submission' },
  ];

  readonly difficultyOptions: { value: Difficulty | null; label: string }[] = [
    { value: null, label: 'None' },
    { value: 'EASY', label: 'Easy' },
    { value: 'MEDIUM', label: 'Medium' },
    { value: 'HARD', label: 'Hard' },
  ];

  get options(): FormArray {
    return this.form.get('options') as FormArray;
  }

  get newSubOptions(): FormArray {
    return this.newSubForm.get('options') as FormArray;
  }

  ngOnInit() {
    // newSubForm defaults to type 'TEXT', which doesn't use options.
    this.newSubOptions.clear();

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.editId.set(id);
      this.svc.getQuestion(id).subscribe({
        next: q => {
          // GROUP questions cannot be edited — show read-only notice
          if (q.type === 'GROUP') {
            this.groupEditBlocked.set(true);
            this.form.patchValue({ type: q.type, title: q.title, body: q.body });
            return;
          }
          this.form.patchValue({
            type: q.type,
            title: q.title,
            body: q.body,
            tagsRaw: q.tags.join(', '),
            languageHint: q.languageHint ?? '',
            maxScore: q.maxScore ?? 1,
            difficulty: q.difficulty ?? null,
          });
          if (q.type === 'MCQ' && q.options) {
            this.options.clear();
            q.options.forEach(o => this.options.push(this.makeOption(o.text, o.correct)));
          } else {
            // TEXT / CODE_SUBMISSION / GROUP: clear the two empty MCQ rows the
            // form initialises with — they carry Validators.required and would
            // make the form permanently invalid for non-MCQ edit mode.
            this.options.clear();
          }
        },
        error: () => this.error.set('Failed to load question.'),
      });
    }
  }

  setType(type: string) {
    this.form.patchValue({ type: type as QuestionType });
    this.mcqError.set(null);
    this.memberError.set(null);

    if (type === 'MCQ') {
      if (this.options.length < 2) {
        this.options.clear();
        this.options.push(this.makeOption('', true));
        this.options.push(this.makeOption('', false));
      }
    } else {
      this.options.clear();
    }

    if (type === 'GROUP') {
      this.subQuestionEntries.set([]);
      this.bankSearch.set('');
      this.bankError.set(null);
      this.bankLoading.set(true);
      this.svc.listQuestions().subscribe({
        next: qs => { this.allQuestions.set(qs); this.bankLoading.set(false); },
        error: () => {
          this.bankError.set('Failed to load question bank. Please try again.');
          this.bankLoading.set(false);
        },
      });
    }
  }

  setDifficulty(value: Difficulty | null) {
    this.form.patchValue({ difficulty: value });
  }

  // ── GROUP sub-question management ───────────────────────────────────────

  addMember(q: Question) {
    this.subQuestionEntries.update(es => [...es, { source: 'bank', question: q }]);
    this.memberError.set(null);
  }

  removeEntry(index: number) {
    this.subQuestionEntries.update(es => es.filter((_, i) => i !== index));
  }

  typeLabel(type: string): string {
    return ({ MCQ: 'MCQ', TEXT: 'Text', CODE_SUBMISSION: 'Code', GROUP: 'Group' } as Record<string, string>)[type] ?? type;
  }

  // ── Inline create-new-sub-question mini-form ────────────────────────────

  setNewSubType(type: string) {
    this.newSubForm.patchValue({ type: type as 'MCQ' | 'TEXT' | 'CODE_SUBMISSION' });
    this.newSubError.set(null);

    if (type === 'MCQ') {
      if (this.newSubOptions.length < 2) {
        this.newSubOptions.clear();
        this.newSubOptions.push(this.makeOption('', true));
        this.newSubOptions.push(this.makeOption('', false));
      }
    } else {
      this.newSubOptions.clear();
    }
  }

  addSubOption() { this.newSubOptions.push(this.makeOption('', false)); }

  removeSubOption(i: number) { this.newSubOptions.removeAt(i); }

  markSubCorrect(index: number) {
    this.newSubOptions.controls.forEach((ctrl, i) => ctrl.patchValue({ correct: i === index }));
  }

  addSubQuestion() {
    this.newSubForm.markAllAsTouched();
    if (this.newSubForm.invalid) return;

    const type = this.newSubForm.get('type')!.value as 'MCQ' | 'TEXT' | 'CODE_SUBMISSION';

    if (type === 'MCQ') {
      const opts = this.newSubOptions.value as { text: string; correct: boolean }[];
      const correctCount = opts.filter(o => o.correct).length;
      if (opts.some(o => !o.text.trim())) { this.newSubError.set('All option texts are required.'); return; }
      if (correctCount !== 1) { this.newSubError.set('Exactly one option must be marked correct.'); return; }
    }

    const draft: NewSubQuestionDraft = {
      type,
      title: this.newSubForm.get('title')!.value!,
      body: this.newSubForm.get('body')!.value!,
      maxScore: this.newSubForm.get('maxScore')!.value ?? 1,
      ...(type === 'MCQ' && { options: this.newSubOptions.value }),
      ...(type === 'CODE_SUBMISSION' && {
        languageHint: this.newSubForm.get('languageHint')!.value || undefined,
      }),
    };

    this.subQuestionEntries.update(es => [...es, { source: 'new', draft }]);
    this.memberError.set(null);
    this.newSubError.set(null);
    this.resetNewSubForm();
  }

  private resetNewSubForm() {
    this.newSubForm.reset({ type: 'TEXT', title: '', body: '', maxScore: 1, languageHint: '' });
    this.newSubOptions.clear();
  }

  // ── MCQ helpers ─────────────────────────────────────────────────────────

  makeOption(text: string, correct: boolean) {
    return this.fb.group({ text: [text, Validators.required], correct: [correct] });
  }

  addOption() { this.options.push(this.makeOption('', false)); }

  removeOption(i: number) { this.options.removeAt(i); }

  markCorrect(index: number) {
    this.options.controls.forEach((ctrl, i) => ctrl.patchValue({ correct: i === index }));
  }

  optionLetter(i: number): string {
    return String.fromCharCode(65 + i);
  }

  // ── AI generation ───────────────────────────────────────────────────────

  generateWithAi() {
    const topic = this.aiTopic().trim();
    if (!topic) return;

    const type = this.form.get('type')!.value as QuestionType;

    this.aiGenerating.set(true);
    this.aiSvc.generateQuestions({
      type,
      topic,
      difficulty: this.aiDifficulty(),
      count: this.aiCount(),
    }).subscribe({
      next: drafts => {
        this.aiGenerating.set(false);
        this.aiDrafts.set(drafts);
        this.aiDraftIndex.set(0);
        this.aiSavedDrafts.set(new Set());
        this.applyAiDraft(drafts[0]);
        this.toastSvc.show(
          `Generated ${drafts.length} question${drafts.length > 1 ? 's' : ''} — review and edit before saving.`,
          'success'
        );
      },
      error: err => {
        this.aiGenerating.set(false);
        this.toastSvc.show(this.friendlyAiError(err), 'error');
      },
    });
  }

  showAiDraft(index: number) {
    // Capture any edits made to the current draft before switching away
    this.syncCurrentDraftFromForm();
    this.aiDraftIndex.set(index);
    this.applyAiDraft(this.aiDrafts()[index]);
  }

  /**
   * Fold the current form values back into the draft currently displayed so
   * edits (title, body, tags, options, etc.) aren't lost when navigating
   * between drafts or saving all of them.
   */
  private syncCurrentDraftFromForm() {
    const drafts = this.aiDrafts();
    const idx = this.aiDraftIndex();
    if (idx < 0 || idx >= drafts.length) return;

    const current = drafts[idx];
    const tags = (this.form.get('tagsRaw')!.value ?? '')
      .split(',').map((t: string) => t.trim()).filter(Boolean);

    const updated: QuestionRequest = {
      ...current,
      title: this.form.get('title')!.value ?? current.title,
      body: this.form.get('body')!.value ?? current.body,
      tags,
      difficulty: this.form.get('difficulty')!.value ?? null,
      ...(current.type === 'MCQ' && {
        options: this.options.value as { text: string; correct: boolean }[],
      }),
      ...(current.type === 'CODE_SUBMISSION' && {
        languageHint: this.form.get('languageHint')!.value ?? current.languageHint,
      }),
    };

    const next = [...drafts];
    next[idx] = updated;
    this.aiDrafts.set(next);
  }

  private applyAiDraft(draft: QuestionRequest) {
    this.form.patchValue({
      title: draft.title,
      body: draft.body,
      tagsRaw: (draft.tags ?? []).join(', '),
      languageHint: draft.languageHint ?? '',
      difficulty: draft.difficulty ?? null,
    });

    if (draft.type === 'MCQ' && draft.options) {
      this.options.clear();
      draft.options.forEach(o => this.options.push(this.makeOption(o.text, o.correct)));
      this.mcqError.set(null);
    }
  }

  private friendlyAiError(err: { status?: number; error?: { detail?: string } }): string {
    switch (err?.status) {
      case 503:
        return 'The AI provider is rate-limited right now — wait a moment and try again.';
      case 502:
        return 'Could not reach the AI provider. Try again shortly.';
      case 504:
        return 'The AI provider took too long to respond. Try again.';
      case 422:
      case 400:
        return err.error?.detail ?? 'The AI could not generate a usable question. Try again.';
      default:
        return 'Failed to generate a question. Try again.';
    }
  }

  // ── Save All AI Drafts ─────────────────────────────────────────────────

  saveAllDrafts() {
    // Fold edits from the currently-shown draft back in before saving
    this.syncCurrentDraftFromForm();
    const drafts = this.aiDrafts();
    const saved = this.aiSavedDrafts();
    const unsaved = drafts
      .map((d, i) => ({ draft: d, index: i }))
      .filter(({ index }) => !saved.has(index));

    if (unsaved.length === 0) return;

    this.aiSavingAll.set(true);
    const calls = unsaved.map(({ draft }) => this.svc.createQuestion({
      type: draft.type,
      title: draft.title,
      body: draft.body,
      tags: draft.tags ?? [],
      maxScore: draft.maxScore ?? 1,
      difficulty: draft.difficulty ?? null,
      ...(draft.type === 'MCQ' && draft.options && { options: draft.options }),
      ...(draft.type === 'CODE_SUBMISSION' && draft.languageHint && { languageHint: draft.languageHint }),
    }));

    forkJoin(calls).subscribe({
      next: () => {
        this.aiSavingAll.set(false);
        const allSaved = new Set<number>(drafts.map((_, i) => i));
        this.aiSavedDrafts.set(allSaved);
        this.toastSvc.show(
          `All ${unsaved.length} draft${unsaved.length > 1 ? 's' : ''} saved successfully.`,
          'success'
        );
      },
      error: err => {
        this.aiSavingAll.set(false);
        this.toastSvc.show(err?.error?.detail ?? 'Failed to save some drafts. Try again.', 'error');
      },
    });
  }

  // ── Submit ──────────────────────────────────────────────────────────────

  submit() {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    const type = this.form.get('type')!.value as QuestionType;
    const tags = (this.form.get('tagsRaw')!.value ?? '')
      .split(',').map((t: string) => t.trim()).filter(Boolean);

    if (type === 'MCQ') {
      const opts = this.options.value as { text: string; correct: boolean }[];
      const correctCount = opts.filter(o => o.correct).length;
      if (opts.some(o => !o.text.trim())) { this.mcqError.set('All option texts are required.'); return; }
      if (correctCount !== 1) { this.mcqError.set('Exactly one option must be marked correct.'); return; }
      this.mcqError.set(null);
    }

    const difficulty = this.form.get('difficulty')!.value ?? null;

    if (type === 'GROUP') {
      if (this.subQuestionEntries().length < 2) {
        this.memberError.set('A group question must have at least 2 sub-questions.');
        return;
      }
      this.memberError.set(null);

      this.saving.set(true);
      this.error.set(null);
      this.submitGroup(tags, difficulty);
      return;
    }

    const payload = {
      type,
      title: this.form.get('title')!.value!,
      body: this.form.get('body')!.value!,
      tags,
      maxScore: this.form.get('maxScore')!.value ?? 1,
      difficulty,
      ...(type === 'MCQ' && { options: this.options.value }),
      ...(type === 'CODE_SUBMISSION' && {
        languageHint: this.form.get('languageHint')!.value ?? undefined,
      }),
    };

    this.saving.set(true);
    this.error.set(null);

    const op = this.editId()
      ? this.svc.updateQuestion(this.editId()!, payload)
      : this.svc.createQuestion(payload);

    op.subscribe({
      next: () => {
        // If we're working through AI drafts, mark current as saved and advance
        const drafts = this.aiDrafts();
        if (drafts.length > 1 && !this.editId()) {
          const currentIdx = this.aiDraftIndex();
          const saved = new Set(this.aiSavedDrafts());
          saved.add(currentIdx);
          this.aiSavedDrafts.set(saved);

          // Find the next unsaved draft
          const nextUnsaved = drafts.findIndex((_, i) => i !== currentIdx && !saved.has(i));
          if (nextUnsaved !== -1) {
            this.saving.set(false);
            this.showAiDraft(nextUnsaved);
            this.toastSvc.show(
              `Saved draft ${currentIdx + 1} of ${drafts.length}. Showing next unsaved draft.`,
              'success'
            );
            return;
          }
        }
        this.router.navigate(['/questions']);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Failed to save question.');
        this.saving.set(false);
      },
    });
  }

  // Two-phase GROUP submit: persist 'new' sub-question drafts individually first,
  // then create the GROUP referencing bank + newly-created ids in display order.
  private submitGroup(tags: string[], difficulty: Difficulty | null) {
    const entries = this.subQuestionEntries();

    const createCalls = entries
      .filter((entry): entry is { source: 'new'; draft: NewSubQuestionDraft } => entry.source === 'new')
      .map(({ draft }) => this.svc.createQuestion({
        type: draft.type,
        title: draft.title,
        body: draft.body,
        tags: [],
        maxScore: draft.maxScore,
        ...(draft.options && { options: draft.options }),
        ...(draft.languageHint && { languageHint: draft.languageHint }),
      }));

    const create$ = createCalls.length > 0 ? forkJoin(createCalls) : of([] as Question[]);

    create$.subscribe({
      next: created => {
        const memberQuestionIds: string[] = [];
        let createdIdx = 0;
        const updatedEntries: SubQuestionEntry[] = entries.map(entry => {
          if (entry.source === 'bank') {
            memberQuestionIds.push(entry.question.id);
            return entry;
          }
          const newQuestion = created[createdIdx++];
          memberQuestionIds.push(newQuestion.id);
          return { source: 'bank', question: newQuestion };
        });
        // Replace persisted drafts with bank references so a retry after a
        // GROUP-creation failure doesn't recreate them.
        this.subQuestionEntries.set(updatedEntries);

        const payload = {
          type: 'GROUP' as QuestionType,
          title: this.form.get('title')!.value!,
          body: this.form.get('body')!.value!,
          tags,
          difficulty,
          memberQuestionIds,
        };

        this.svc.createQuestion(payload).subscribe({
          next: () => this.router.navigate(['/questions']),
          error: err => {
            this.error.set(err?.error?.detail ?? 'Failed to save question.');
            this.saving.set(false);
          },
        });
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Failed to create new sub-questions.');
        this.saving.set(false);
      },
    });
  }
}
