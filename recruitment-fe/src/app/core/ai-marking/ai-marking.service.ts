import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AiMarkingSuggestionResponse } from './ai-marking.model';

@Injectable({ providedIn: 'root' })
export class AiMarkingService {
  private readonly http = inject(HttpClient);

  getSuggestion(submissionId: string, questionId: string): Observable<AiMarkingSuggestionResponse> {
    return this.http.get<AiMarkingSuggestionResponse>(
      `/api/submissions/${submissionId}/questions/${questionId}/ai-suggestion`,
    );
  }

  generateSuggestion(submissionId: string, questionId: string): Observable<AiMarkingSuggestionResponse> {
    return this.http.post<AiMarkingSuggestionResponse>(
      `/api/submissions/${submissionId}/questions/${questionId}/ai-suggestion`,
      {},
    );
  }
}
