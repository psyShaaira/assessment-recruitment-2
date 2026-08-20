import { RiskLevel } from '../flag/flag.model';

export type SubmissionStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'SUBMITTED' | 'AUTO_SUBMITTED';
export type MarkingStatus = 'FULLY_MARKED' | 'PENDING_REVIEW';
export type QuestionType = 'MCQ' | 'TEXT' | 'CODE_SUBMISSION' | 'GROUP';
export type FlagStatus = 'FLAGGED' | 'UNDER_REVIEW' | 'ACTION_REQUIRED' | 'RESOLVED' | 'DISMISSED';

export interface SubmissionSummary {
  submissionId: string | null;
  invitationId: string;
  candidateId: string;
  candidateName: string;
  assessmentId: string | null;
  assessmentTitle: string;
  status: SubmissionStatus;
  submittedAt: string | null;
  answeredCount: number;
  totalAnswers: number;
  markedCount: number;
  totalScore: number;
  maxScore: number;
  flagStatus: FlagStatus | null;
  aiRiskLevel?: RiskLevel | null;
  candidateBlacklisted: boolean;
}

export interface ResultQuestion {
  questionId: string;
  answerId: string | null;
  questionTitle: string;
  questionType: QuestionType;
  candidateAnswer: string | null;
  score: number | null;
  maxScore: number;
  feedback: string | null;
  autoMarked: boolean;
  markedBy: string | null;
  markedAt: string | null;
  subQuestions?: ResultQuestion[];
}

export interface ResultSummary {
  submissionId: string;
  candidateName: string;
  assessmentTitle: string;
  submittedAt: string | null;
  totalScore: number;
  maxScore: number;
  answeredCount: number;
  markingStatus: MarkingStatus;
  aiRiskLevel?: RiskLevel | null;
  questions: ResultQuestion[];
}

export interface ScoreAnswerRequest {
  score: number;
  feedback?: string | null;
}

export interface AnswerScoreResponse {
  answerId: string;
  score: number;
  feedback: string | null;
  autoMarked: boolean;
  markedBy: string | null;
  markedAt: string | null;
}
