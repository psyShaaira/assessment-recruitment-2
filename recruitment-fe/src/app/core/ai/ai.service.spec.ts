import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { AiService } from './ai.service';
import { QuestionRequest } from '../question/question.model';

const mockDraft: QuestionRequest = {
  type: 'MCQ',
  title: 'Java Generics',
  body: 'What does <T> mean?',
  tags: ['java'],
  options: [
    { text: 'A type parameter', correct: true },
    { text: 'A comment', correct: false },
  ],
  difficulty: 'MEDIUM',
};

describe('AiService — generateQuestions', () => {
  let service: AiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sends POST /api/questions/generate with the request params', () => {
    service.generateQuestions({ type: 'MCQ', topic: 'java', difficulty: 'MEDIUM', count: 1 })
      .subscribe(drafts => {
        expect(drafts.length).toBe(1);
        expect(drafts[0].title).toBe('Java Generics');
      });

    const req = httpMock.expectOne('/api/questions/generate');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ type: 'MCQ', topic: 'java', difficulty: 'MEDIUM', count: 1 });
    req.flush([mockDraft]);
  });

  it('propagates HTTP errors to the caller', () => {
    let caught: unknown;
    service.generateQuestions({ type: 'MCQ', topic: 'java', difficulty: 'MEDIUM', count: 1 })
      .subscribe({ error: err => (caught = err) });

    httpMock.expectOne('/api/questions/generate').flush(
      { detail: 'AI provider is not configured: missing API key' },
      { status: 502, statusText: 'Bad Gateway' },
    );

    expect((caught as { status: number }).status).toBe(502);
  });
});
