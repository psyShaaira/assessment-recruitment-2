import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CreateFlagRequest,
  FlagAuditEntry,
  FlagListItem,
  FlagReason,
  FlagResponse,
  RiskAssessmentResponse,
  TransitionFlagRequest,
} from './flag.model';

@Injectable({ providedIn: 'root' })
export class FlagService {
  private readonly http = inject(HttpClient);

  createFlag(submissionId: string, req: CreateFlagRequest): Observable<FlagResponse> {
    return this.http.post<FlagResponse>(`/api/submissions/${submissionId}/flags`, req);
  }

  transitionFlag(
    submissionId: string,
    flagId: string,
    req: TransitionFlagRequest,
  ): Observable<FlagResponse> {
    return this.http.patch<FlagResponse>(`/api/submissions/${submissionId}/flags/${flagId}`, req);
  }

  getAuditTrail(submissionId: string, flagId: string): Observable<FlagAuditEntry[]> {
    return this.http.get<FlagAuditEntry[]>(
      `/api/submissions/${submissionId}/flags/${flagId}/audit`,
    );
  }

  getCandidateFlags(candidateId: string): Observable<FlagListItem[]> {
    return this.http.get<FlagListItem[]>(`/api/candidates/${candidateId}/flags`);
  }

  getAllFlags(filters?: {
    reason?: FlagReason;
    assessmentId?: string;
    fromDate?: string;
    toDate?: string;
  }): Observable<FlagListItem[]> {
    let params = new HttpParams();
    if (filters?.reason) params = params.set('reason', filters.reason);
    if (filters?.assessmentId) params = params.set('assessmentId', filters.assessmentId);
    if (filters?.fromDate) params = params.set('fromDate', filters.fromDate);
    if (filters?.toDate) params = params.set('toDate', filters.toDate);
    return this.http.get<FlagListItem[]>('/api/flags', { params });
  }

  getRiskAssessment(submissionId: string): Observable<RiskAssessmentResponse> {
    return this.http.get<RiskAssessmentResponse>(`/api/submissions/${submissionId}/risk-assessment`);
  }
}
