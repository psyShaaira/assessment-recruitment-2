import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AssessmentTakeResponse,
  ClarificationResponse,
  SaveAnswersRequest,
  SaveAnswersResponse,
  SubmitRequest,
  SubmitResponse,
} from './candidate-take.model';

@Injectable({ providedIn: 'root' })
export class CandidateTakeService {
  private readonly http = inject(HttpClient);

  loadAssessment(sessionToken: string): Observable<AssessmentTakeResponse> {
    return this.http.get<AssessmentTakeResponse>('/api/take/assessment', {
      headers: new HttpHeaders({ Authorization: `Bearer ${sessionToken}` }),
    });
  }

  saveAnswers(sessionToken: string, req: SaveAnswersRequest): Observable<SaveAnswersResponse> {
    return this.http.put<SaveAnswersResponse>('/api/take/answers', req, {
      headers: new HttpHeaders({ Authorization: `Bearer ${sessionToken}` }),
    });
  }

  submit(sessionToken: string, req: SubmitRequest): Observable<SubmitResponse> {
    return this.http.post<SubmitResponse>('/api/take/submit', req, {
      headers: new HttpHeaders({ Authorization: `Bearer ${sessionToken}` }),
    });
  }

  askClarification(
    sessionToken: string,
    questionId: string,
    candidateNote?: string,
  ): Observable<ClarificationResponse> {
    return this.http.post<ClarificationResponse>(
      '/api/take/clarify',
      { questionId, candidateNote: candidateNote ?? null },
      { headers: new HttpHeaders({ Authorization: `Bearer ${sessionToken}` }) },
    );
  }
}
