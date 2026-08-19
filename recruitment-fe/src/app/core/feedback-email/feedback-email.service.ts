import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FeedbackEmailSendResponse, FeedbackEmailSendLogEntry } from './feedback-email.model';

@Injectable({ providedIn: 'root' })
export class FeedbackEmailService {
  private readonly http = inject(HttpClient);

  sendEmail(submissionId: string): Observable<FeedbackEmailSendResponse> {
    if (!submissionId) {
      throw new Error('submissionId must not be empty');
    }
    return this.http.post<FeedbackEmailSendResponse>(
      `/api/submissions/${submissionId}/feedback-report/email`,
      {},
    );
  }

  getSendHistory(submissionId: string): Observable<FeedbackEmailSendLogEntry[]> {
    if (!submissionId) {
      throw new Error('submissionId must not be empty');
    }
    return this.http.get<FeedbackEmailSendLogEntry[]>(
      `/api/submissions/${submissionId}/feedback-report/email`,
    );
  }
}
