import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CandidateTakeService } from './candidate-take.service';
import { AssessmentTakeResponse, SaveAnswersRequest, SubmitRequest } from './candidate-take.model';

const SESSION_TOKEN = 'test-session-token';

const mockTakeResponse: AssessmentTakeResponse = {
  assessmentId: 'aaa',
  title: 'Test Assessment',
  description: null,
  totalQuestionCount: 1,
  startedAt: '2026-05-29T10:00:00Z',
  deadline: '2026-05-29T11:00:00Z',
  questions: [{ id: 'q1', displayOrder: 1, type: 'MCQ', title: 'Q1', body: 'What is 2+2?', maxScore: 1, options: [{ id: 'o1', optionText: '4' }] }],
  answers: [],
};

describe('CandidateTakeService', () => {
  let service: CandidateTakeService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CandidateTakeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loadAssessment sends GET /api/take/assessment with bearer token', () => {
    service.loadAssessment(SESSION_TOKEN).subscribe(res => {
      expect(res.assessmentId).toBe('aaa');
    });

    const req = httpMock.expectOne('/api/take/assessment');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${SESSION_TOKEN}`);
    req.flush(mockTakeResponse);
  });

  it('saveAnswers sends PUT /api/take/answers with bearer token', () => {
    const body: SaveAnswersRequest = {
      answers: [{ questionId: 'q1', selectedOptionIds: ['o1'] }],
    };

    service.saveAnswers(SESSION_TOKEN, body).subscribe();

    const req = httpMock.expectOne('/api/take/answers');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${SESSION_TOKEN}`);
    expect(req.request.body.answers[0].questionId).toBe('q1');
    req.flush({ answers: [] });
  });

  it('submit sends POST /api/take/submit with bearer token', () => {
    const body: SubmitRequest = { autoSubmitted: false };

    service.submit(SESSION_TOKEN, body).subscribe(res => {
      expect(res.status).toBe('SUBMITTED');
    });

    const req = httpMock.expectOne('/api/take/submit');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${SESSION_TOKEN}`);
    req.flush({
      submissionId: 'sub-1',
      assessmentTitle: 'Test',
      status: 'SUBMITTED',
      submittedAt: '2026-05-29T10:30:00Z',
      answeredCount: 1,
      totalQuestionCount: 1,
    });
  });

  it('askClarification sends POST /api/take/clarify with bearer token and note', () => {
    service.askClarification(SESSION_TOKEN, 'q1', 'what does this mean?').subscribe(res => {
      expect(res.clarification).toBe('It asks you to explain the concept.');
      expect(res.remainingForQuestion).toBe(2);
      expect(res.degraded).toBe(false);
    });

    const req = httpMock.expectOne('/api/take/clarify');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${SESSION_TOKEN}`);
    expect(req.request.body.questionId).toBe('q1');
    expect(req.request.body.candidateNote).toBe('what does this mean?');
    req.flush({
      clarification: 'It asks you to explain the concept.',
      remainingForQuestion: 2,
      remainingForAssessment: 14,
      degraded: false,
    });
  });

  it('askClarification sends null note when omitted', () => {
    service.askClarification(SESSION_TOKEN, 'q1').subscribe();

    const req = httpMock.expectOne('/api/take/clarify');
    expect(req.request.body.candidateNote).toBeNull();
    req.flush({
      clarification: 'text',
      remainingForQuestion: 2,
      remainingForAssessment: 14,
      degraded: false,
    });
  });
});
