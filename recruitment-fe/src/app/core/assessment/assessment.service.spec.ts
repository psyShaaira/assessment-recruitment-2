import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { AssessmentService } from './assessment.service';
import { AssemblySuggestionResponse } from './assessment.model';

const mockResponse: AssemblySuggestionResponse = {
  outcomes: [
    {
      quota: { tag: 'java', difficulty: 'EASY', count: 2 },
      suggested: [{ id: 'q-1', type: 'TEXT', title: 'Java generics', difficulty: 'EASY', tags: ['java'] }],
      shortfall: 1,
    },
  ],
};

describe('AssessmentService — suggestQuestions', () => {
  let service: AssessmentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AssessmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sends POST /api/assessments/suggest-questions with the quotas wrapped in a request body', () => {
    const quotas = [{ tag: 'java', difficulty: 'EASY' as const, count: 2 }];

    service.suggestQuestions(quotas).subscribe(res => {
      expect(res.outcomes[0].suggested).toHaveLength(1);
      expect(res.outcomes[0].shortfall).toBe(1);
    });

    const req = httpMock.expectOne('/api/assessments/suggest-questions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ quotas });
    req.flush(mockResponse);
  });
});
