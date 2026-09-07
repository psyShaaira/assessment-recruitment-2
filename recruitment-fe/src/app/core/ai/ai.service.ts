import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { QuestionRequest } from '../question/question.model';
import { GenerateDistractorsRequest, GenerateQuestionsRequest } from './ai.model';

@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);

  // Response mirrors QuestionRequest exactly — generated drafts are unsaved,
  // meant to pre-fill the question form for review, not a separate shape.
  generateQuestions(params: GenerateQuestionsRequest): Observable<QuestionRequest[]> {
    return this.http.post<QuestionRequest[]>('/api/questions/generate', params);
  }

  // Returns candidate distractor options; every option has correct: false —
  // the recruiter owns the correct answer and it is never generated here.
  generateDistractors(
    params: GenerateDistractorsRequest,
  ): Observable<{ text: string; correct: boolean }[]> {
    return this.http.post<{ text: string; correct: boolean }[]>(
      '/api/questions/generate-distractors',
      params,
    );
  }
}
