import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FeedbackEmailSendResponse, FeedbackReportResponse } from './feedback.model';

@Injectable({ providedIn: 'root' })
export class FeedbackService {
  private readonly http = inject(HttpClient);

  getReport(submissionId: string): Observable<FeedbackReportResponse> {
    if (!submissionId) {
      throw new Error('submissionId must not be empty');
    }
    return this.http.get<FeedbackReportResponse>(
      `/api/submissions/${submissionId}/feedback-report`,
    );
  }

  generateReport(submissionId: string): Observable<FeedbackReportResponse> {
    if (!submissionId) {
      throw new Error('submissionId must not be empty');
    }
    return this.http.post<FeedbackReportResponse>(
      `/api/submissions/${submissionId}/feedback-report`,
      {},
    );
  }

  sendFeedbackEmail(submissionId: string): Observable<FeedbackEmailSendResponse> {
    if (!submissionId) {
      throw new Error('submissionId must not be empty');
    }
    return this.http.post<FeedbackEmailSendResponse>(
      `/api/submissions/${submissionId}/feedback-report/email`,
      {},
    );
  }
}
