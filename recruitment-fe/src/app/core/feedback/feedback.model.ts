export interface FeedbackTopic {
  topic: string;
  strengths: string;
  weaknesses: string;
}

export interface FeedbackReportContent {
  overallSummary: string;
  topics: FeedbackTopic[];
  nextSteps: string[];
}

export interface FeedbackReportResponse {
  submissionId: string;
  content: FeedbackReportContent;
  aiGenerated: boolean;
  promptVersion: string;
  generatedAt: string;
}
