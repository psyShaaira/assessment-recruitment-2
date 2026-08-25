import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AssessmentTakeComponent } from './assessment-take.component';
import { AssessmentService } from '../../core/assessment/assessment.service';
import { AuthService } from '../../core/auth/auth.service';
import { CandidateTakeService } from '../../core/take/candidate-take.service';
import { AssessmentTakeResponse, SubmitResponse } from '../../core/take/candidate-take.model';

const MOCK_TOKEN = 'mock-session-token';
const NOW = Date.now();

const mockTakeResponse: AssessmentTakeResponse = {
  assessmentId: 'aaa',
  title: 'Test Assessment',
  description: null,
  totalQuestionCount: 1,
  startedAt: new Date(NOW - 5000).toISOString(),   // 5s ago → new session, shows guide
  deadline: new Date(NOW + 3600 * 1000).toISOString(),
  questions: [
    { id: 'q1', displayOrder: 1, type: 'MCQ', title: 'Q1', body: 'What is 2+2?', maxScore: 1, options: [{ id: 'o1', optionText: '4' }] },
  ],
  answers: [
    { questionId: 'q1', selectedOptionIds: ['o1'], textContent: null, savedAt: new Date().toISOString() },
  ],
};

const mockReturningTakeResponse: AssessmentTakeResponse = {
  ...mockTakeResponse,
  startedAt: new Date(NOW - 30_000).toISOString(),  // 30s ago → returning, skips guide
  deadline: new Date(NOW + (3600 - 30) * 1000).toISOString(),
};

const mockSubmitResponse: SubmitResponse = {
  submissionId: 'sub-1',
  assessmentTitle: 'Test Assessment',
  status: 'SUBMITTED',
  submittedAt: new Date().toISOString(),
  answeredCount: 1,
  totalQuestionCount: 1,
};

function createComponent(takeResponse = mockTakeResponse) {
  const authSvc = {
    validateCandidateToken: vi.fn().mockReturnValue(of({ token: MOCK_TOKEN })),
  };
  const assessmentSvc = {
    getPreview: vi.fn().mockReturnValue(of({
      id: 'aaa', title: 'Test', description: null, timeLimitMinutes: 60,
      passwordRequired: false, questions: [],
    })),
    verifyPassword: vi.fn().mockReturnValue(of({ valid: true })),
  };
  const takeSvc = {
    loadAssessment: vi.fn().mockReturnValue(of(takeResponse)),
    saveAnswers: vi.fn().mockReturnValue(of({ answers: [] })),
    submit: vi.fn().mockReturnValue(of(mockSubmitResponse)),
    askClarification: vi.fn().mockReturnValue(of({
      clarification: 'This asks what 2+2 evaluates to.',
      remainingForQuestion: 2,
      remainingForAssessment: 14,
      degraded: false,
    })),
  };

  TestBed.configureTestingModule({
    imports: [AssessmentTakeComponent],
    providers: [
      { provide: AuthService, useValue: authSvc },
      { provide: AssessmentService, useValue: assessmentSvc },
      { provide: CandidateTakeService, useValue: takeSvc },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            paramMap: { get: () => 'aaa' },
            queryParamMap: { get: () => 'inv-token' },
          },
        },
      },
    ],
  });

  const fixture = TestBed.createComponent(AssessmentTakeComponent);
  return { fixture, component: fixture.componentInstance, takeSvc, assessmentSvc, authSvc };
}

describe('AssessmentTakeComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads assessment on init and pre-populates answers from saved state', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    expect(takeSvc.loadAssessment).toHaveBeenCalledWith(MOCK_TOKEN);
    expect(component.preview()).not.toBeNull();
    expect(component.preview()!.title).toBe('Test Assessment');
    expect(component.answers()['q1']).toBe('o1');
  });

  it('initialises timeLeft from server deadline, not full timeLimitMinutes', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    const t = component.timeLeft();
    expect(t).toBeGreaterThan(3500);
    expect(t).toBeLessThanOrEqual(3601);
  });

  // ── 8.1: Guide screen shown for new session; Start transitions to in-progress ──

  it('shows guide screen on init when session is new (startedAt < 10s ago)', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    expect(component.phase()).toBe('guide');
  });

  it('beginAssessment() transitions phase to in-progress', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    component.beginAssessment();

    expect(component.phase()).toBe('in-progress');
  });

  // ── 8.2: Returning candidate skips guide ──

  it('skips guide screen for returning candidate (startedAt > 10s ago)', () => {
    const { fixture, component } = createComponent(mockReturningTakeResponse);
    fixture.detectChanges();

    expect(component.phase()).toBe('in-progress');
  });

  // ── 8.3: beforeunload listener added on in-progress and removed on submit ──

  it('adds beforeunload listener when beginAssessment() is called', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    const addSpy = vi.spyOn(window, 'addEventListener');
    component.beginAssessment();

    expect(addSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function));
    addSpy.mockRestore();
  });

  it('removes beforeunload listener after submission', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();
    component.beginAssessment();

    const removeSpy = vi.spyOn(window, 'removeEventListener');
    component['doSubmit'](false);

    expect(removeSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function));
    removeSpy.mockRestore();
  });

  // ── 8.4: Give Up modal ──

  it('openGiveUpModal() sets showGiveUpModal to true', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    component.openGiveUpModal();

    expect(component.showGiveUpModal()).toBe(true);
  });

  it('confirmGiveUp() calls submit service with autoSubmitted=true', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    component.confirmGiveUp();

    expect(takeSvc.submit).toHaveBeenCalledWith(MOCK_TOKEN, { autoSubmitted: true });
    expect(component.submitted()).toBe(true);
  });

  it('cancelGiveUp() closes modal without submitting', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    component.openGiveUpModal();
    component.cancelGiveUp();

    expect(component.showGiveUpModal()).toBe(false);
    expect(takeSvc.submit).not.toHaveBeenCalled();
  });

  // ── 8.5: Zero-answer submit guard ──

  it('confirmSubmit() sets zeroAnswerWarning when answeredCount is 0', () => {
    const noAnswerResponse: AssessmentTakeResponse = { ...mockTakeResponse, answers: [] };
    const { fixture, component } = createComponent(noAnswerResponse);
    fixture.detectChanges();

    component.confirmSubmit();

    expect(component.zeroAnswerWarning()).toBe(true);
    expect(component.showSubmitModal()).toBe(true);
  });

  it('confirmSubmit() does not set zeroAnswerWarning when at least one answer exists', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    component.confirmSubmit();

    expect(component.zeroAnswerWarning()).toBe(false);
    expect(component.showSubmitModal()).toBe(true);
  });

  it('doSubmit with autoSubmitted:false calls service and sets submitted state', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    component['doSubmit'](false);

    expect(takeSvc.submit).toHaveBeenCalledWith(MOCK_TOKEN, { autoSubmitted: false });
    expect(component.submitted()).toBe(true);
    expect(component.submitResult()?.status).toBe('SUBMITTED');
  });

  it('doSubmit with autoSubmitted:true marks as auto-submitted', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    component['doSubmit'](true);

    expect(takeSvc.submit).toHaveBeenCalledWith(MOCK_TOKEN, { autoSubmitted: true });
    expect(component.submitted()).toBe(true);
  });

  // ── clarification bot ──

  it('toggleClarify() opens and closes the clarification panel', () => {
    const { fixture, component } = createComponent();
    fixture.detectChanges();

    expect(component.clarifyOpen()).toBe(false);
    component.toggleClarify();
    expect(component.clarifyOpen()).toBe(true);
    component.toggleClarify();
    expect(component.clarifyOpen()).toBe(false);
  });

  it('askClarification() calls the service and renders the clarification', () => {
    const { fixture, component, takeSvc } = createComponent();
    fixture.detectChanges();

    component.clarifyNote.set('what does this mean?');
    component.askClarification('q1');

    expect(takeSvc.askClarification).toHaveBeenCalledWith(MOCK_TOKEN, 'q1', 'what does this mean?');
    expect(component.clarifyText()).toBe('This asks what 2+2 evaluates to.');
    expect(component.clarifyRemaining()).toBe(2);
    expect(component.clarifyExhausted()).toBe(false);
  });

  it('askClarification() marks exhausted when no clarifications remain', () => {
    const { fixture, component, takeSvc } = createComponent();
    takeSvc.askClarification.mockReturnValue(of({
      clarification: 'last one',
      remainingForQuestion: 0,
      remainingForAssessment: 5,
      degraded: false,
    }));
    fixture.detectChanges();

    component.askClarification('q1');

    expect(component.clarifyExhausted()).toBe(true);
  });

  it('askClarification() sets a limit error on 429', () => {
    const { fixture, component, takeSvc } = createComponent();
    takeSvc.askClarification.mockReturnValue(throwError(() => ({ status: 429 })));
    fixture.detectChanges();

    component.askClarification('q1');

    expect(component.clarifyExhausted()).toBe(true);
    expect(component.clarifyError()).toContain('limit');
  });

  it('navigating to the next question resets clarification state', () => {
    const twoQuestions: AssessmentTakeResponse = {
      ...mockReturningTakeResponse,
      questions: [
        mockReturningTakeResponse.questions[0],
        { id: 'q2', displayOrder: 2, type: 'TEXT', title: 'Q2', body: 'Explain.', maxScore: 5, options: null },
      ],
    };
    const { fixture, component } = createComponent(twoQuestions);
    fixture.detectChanges();

    component.askClarification('q1');
    component.toggleClarify();
    expect(component.clarifyText()).not.toBeNull();

    component.next();

    expect(component.clarifyOpen()).toBe(false);
    expect(component.clarifyText()).toBeNull();
    expect(component.clarifyNote()).toBe('');
  });
});
