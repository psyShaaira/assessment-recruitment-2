export interface FeedbackEmailSendResponse {
  submissionId: string;
  status: 'SENT' | 'FAILED';
  sentAt: string;
}

export interface FeedbackEmailSendLogEntry {
  sentAt: string;
  status: 'SENT' | 'FAILED';
  sentBy: string | null;
  failureReason: string | null;
}
