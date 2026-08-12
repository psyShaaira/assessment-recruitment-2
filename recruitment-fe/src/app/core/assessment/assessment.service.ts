import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AddQuestionRequest,
  Assessment,
  AssemblyQuota,
  AssemblySuggestionResponse,
  AssessmentDetail,
  AssessmentPreview,
  AssessmentRequest,
} from './assessment.model';

@Injectable({ providedIn: 'root' })
export class AssessmentService {
  private readonly http = inject(HttpClient);

  listAssessments(): Observable<Assessment[]> {
    return this.http.get<Assessment[]>('/api/assessments');
  }

  getAssessment(id: string): Observable<AssessmentDetail> {
    return this.http.get<AssessmentDetail>(`/api/assessments/${id}`);
  }

  createAssessment(req: AssessmentRequest): Observable<AssessmentDetail> {
    return this.http.post<AssessmentDetail>('/api/assessments', req);
  }

  updateAssessment(id: string, req: AssessmentRequest): Observable<AssessmentDetail> {
    return this.http.put<AssessmentDetail>(`/api/assessments/${id}`, req);
  }

  deleteAssessment(id: string): Observable<void> {
    return this.http.delete<void>(`/api/assessments/${id}`);
  }

  publishAssessment(id: string): Observable<AssessmentDetail> {
    return this.http.put<AssessmentDetail>(`/api/assessments/${id}/publish`, {});
  }

  addQuestion(assessmentId: string, req: AddQuestionRequest): Observable<AssessmentDetail> {
    return this.http.post<AssessmentDetail>(`/api/assessments/${assessmentId}/questions`, req);
  }

  removeQuestion(assessmentId: string, questionId: string): Observable<void> {
    return this.http.delete<void>(`/api/assessments/${assessmentId}/questions/${questionId}`);
  }

  reorderQuestions(assessmentId: string, order: { questionId: string; displayOrder: number }[]): Observable<AssessmentDetail> {
    return this.http.put<AssessmentDetail>(`/api/assessments/${assessmentId}/questions/order`, { questions: order });
  }

  getPreview(assessmentId: string, token?: string): Observable<AssessmentPreview> {
    const headers = token
      ? new HttpHeaders({ Authorization: `Bearer ${token}` })
      : new HttpHeaders();
    return this.http.get<AssessmentPreview>(`/api/assessments/${assessmentId}/preview`, { headers });
  }

  suggestQuestions(quotas: AssemblyQuota[]): Observable<AssemblySuggestionResponse> {
    return this.http.post<AssemblySuggestionResponse>('/api/assessments/suggest-questions', { quotas });
  }

  verifyPassword(assessmentId: string, password: string, invitationToken: string): Observable<{ valid: boolean }> {
    return this.http.post<{ valid: boolean }>(
      `/api/candidate/assessments/${assessmentId}/verify-password`,
      { password, invitationToken }
    );
  }
}
