import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { QuestionFormComponent } from './question-form.component';
import { QuestionRequest } from '../../core/question/question.model';

describe('QuestionFormComponent — Save All AI Drafts', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [QuestionFormComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function makeDraft(overrides: Partial<QuestionRequest> = {}): QuestionRequest {
    return {
      type: 'TEXT',
      title: 'Generated title',
      body: 'Generated body',
      tags: ['java'],
      maxScore: 1,
      difficulty: 'MEDIUM',
      ...overrides,
    };
  }

  /**
   * Seed the component with N drafts and display the first one, mirroring the
   * generate() flow (which sets the signals then applies draft 0 to the form
   * directly — NOT via showAiDraft, whose leading sync would run against the
   * still-empty form).
   */
  function seedDrafts(component: QuestionFormComponent, drafts: QuestionRequest[]) {
    component.aiDrafts.set(drafts);
    component.aiDraftIndex.set(0);
    component.aiSavedDrafts.set(new Set());
    applyDraftToForm(component, drafts[0]);
  }

  /** Reflects the form state generate()/applyAiDraft would produce for a draft. */
  function applyDraftToForm(component: QuestionFormComponent, draft: QuestionRequest) {
    component.form.patchValue({
      type: draft.type,
      title: draft.title,
      body: draft.body,
      tagsRaw: (draft.tags ?? []).join(', '),
      languageHint: draft.languageHint ?? '',
      difficulty: draft.difficulty ?? null,
    });
    if (draft.type === 'MCQ' && draft.options) {
      component.options.clear();
      draft.options.forEach(o => component.options.push(component.makeOption(o.text, o.correct)));
    }
  }

  /** Flush all createQuestion POSTs and return the request bodies. */
  function flushCreateQuestions(count: number): QuestionRequest[] {
    const reqs: TestRequest[] = httpMock.match('/api/questions');
    expect(reqs.length).toBe(count);
    const bodies = reqs.map(r => r.request.body as QuestionRequest);
    reqs.forEach((r, i) => r.flush({ id: `id-${i}`, ...bodies[i] }));
    return bodies;
  }

  it('saveAllDrafts sends the EDITED title of the shown draft, not the generated one', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    seedDrafts(component, [
      makeDraft({ title: 'Generated A' }),
      makeDraft({ title: 'Generated B' }),
    ]);

    // User edits the title of the currently shown (first) draft
    component.form.patchValue({ title: 'Edited A' });

    component.saveAllDrafts();

    const bodies = flushCreateQuestions(2);
    const titles = bodies.map(b => b.title);
    expect(titles).toContain('Edited A');
    expect(titles).toContain('Generated B');
    expect(titles).not.toContain('Generated A');
  });

  it('edits persist across Prev/Next navigation and are saved', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    seedDrafts(component, [
      makeDraft({ title: 'Generated A' }),
      makeDraft({ title: 'Generated B' }),
    ]);

    // Edit draft 0, navigate to draft 1, edit it, navigate back
    component.form.patchValue({ title: 'Edited A' });
    component.showAiDraft(1);
    component.form.patchValue({ title: 'Edited B' });
    component.showAiDraft(0);

    // Draft 0's edit should still be in the form after coming back
    expect(component.form.get('title')?.value).toBe('Edited A');

    component.saveAllDrafts();

    const titles = flushCreateQuestions(2).map(b => b.title);
    expect(titles).toContain('Edited A');
    expect(titles).toContain('Edited B');
  });

  it('unedited drafts save with their generated content', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    seedDrafts(component, [
      makeDraft({ title: 'Generated A' }),
      makeDraft({ title: 'Generated B' }),
    ]);

    component.saveAllDrafts();

    const titles = flushCreateQuestions(2).map(b => b.title);
    expect(titles).toContain('Generated A');
    expect(titles).toContain('Generated B');
  });

  it('saveAllDrafts persists edited MCQ options for the shown draft', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    seedDrafts(component, [
      makeDraft({
        type: 'MCQ',
        title: 'MCQ A',
        options: [
          { text: 'orig-1', correct: true },
          { text: 'orig-2', correct: false },
        ],
      }),
      makeDraft({ title: 'Text B' }),
    ]);

    // Edit the first option text of the shown MCQ draft
    component.options.at(0).patchValue({ text: 'edited-1' });

    component.saveAllDrafts();

    const bodies = flushCreateQuestions(2);
    const mcq = bodies.find(b => b.type === 'MCQ');
    expect(mcq?.options?.[0].text).toBe('edited-1');
  });

  it('only saves unsaved drafts (already-saved ones are skipped)', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    seedDrafts(component, [
      makeDraft({ title: 'Generated A' }),
      makeDraft({ title: 'Generated B' }),
    ]);

    // Mark draft 0 as already saved
    component.aiSavedDrafts.set(new Set([0]));

    component.saveAllDrafts();

    const titles = flushCreateQuestions(1).map(b => b.title);
    expect(titles).toEqual(['Generated B']);
  });
});

describe('QuestionFormComponent — suggestDistractors', () => {
  let httpMock: HttpTestingController;
  const ENDPOINT = '/api/questions/generate-distractors';

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [QuestionFormComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /** Snapshot of the current option rows (text + correct flag). */
  function optionsSnapshot(component: QuestionFormComponent) {
    return component.options.controls.map(c => ({
      text: c.get('text')?.value as string,
      correct: c.get('correct')?.value as boolean,
    }));
  }

  it('appends returned distractors as correct:false without modifying existing options', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    // Valid state: body + a correct option ('Paris') + a wrong option ('London').
    component.form.patchValue({ body: 'What is the capital of France?' });
    component.markCorrect(0);
    component.options.at(0).patchValue({ text: 'Paris' });
    component.options.at(1).patchValue({ text: 'London' });

    const before = optionsSnapshot(component);
    expect(before).toEqual([
      { text: 'Paris', correct: true },
      { text: 'London', correct: false },
    ]);

    component.suggestDistractors();

    const req = httpMock.expectOne(ENDPOINT);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      questionBody: 'What is the capital of France?',
      correctAnswer: 'Paris',
      count: 3,
    });

    req.flush([
      { text: 'Berlin', correct: false },
      { text: 'Madrid', correct: false },
      { text: 'Rome', correct: false },
    ]);

    // 2 original + 3 appended = 5.
    expect(component.options.length).toBe(5);

    const after = optionsSnapshot(component);
    // Original two options unchanged (text + correct flags; Paris still correct).
    expect(after[0]).toEqual({ text: 'Paris', correct: true });
    expect(after[1]).toEqual({ text: 'London', correct: false });
    // Appended distractors carry the returned texts and correct === false.
    expect(after[2]).toEqual({ text: 'Berlin', correct: false });
    expect(after[3]).toEqual({ text: 'Madrid', correct: false });
    expect(after[4]).toEqual({ text: 'Rome', correct: false });

    expect(component.generatingDistractors()).toBe(false);
  });

  it('is blocked (no HTTP call) when body is blank', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    // Valid correct option but empty body.
    component.markCorrect(0);
    component.options.at(0).patchValue({ text: 'Paris' });
    component.options.at(1).patchValue({ text: 'London' });

    const before = optionsSnapshot(component);

    component.suggestDistractors();

    expect(httpMock.match(ENDPOINT).length).toBe(0);
    expect(optionsSnapshot(component)).toEqual(before);
  });

  it('is blocked (no HTTP call) when no correct option has text', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    // Body present, but the (default) correct option 0 has EMPTY text.
    component.form.patchValue({ body: 'What is the capital of France?' });

    const before = optionsSnapshot(component);

    component.suggestDistractors();

    expect(httpMock.match(ENDPOINT).length).toBe(0);
    expect(optionsSnapshot(component)).toEqual(before);
  });

  it('error path leaves existing options intact', () => {
    const fixture = TestBed.createComponent(QuestionFormComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    component.form.patchValue({ body: 'What is the capital of France?' });
    component.markCorrect(0);
    component.options.at(0).patchValue({ text: 'Paris' });
    component.options.at(1).patchValue({ text: 'London' });

    const before = optionsSnapshot(component);

    component.suggestDistractors();

    httpMock
      .expectOne(ENDPOINT)
      .flush({ detail: 'AI unavailable' }, { status: 502, statusText: 'Bad Gateway' });

    expect(component.options.length).toBe(before.length);
    expect(optionsSnapshot(component)).toEqual(before);
    expect(component.generatingDistractors()).toBe(false);
  });
});
