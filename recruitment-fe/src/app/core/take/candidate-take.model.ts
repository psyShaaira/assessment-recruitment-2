export type SubmissionStatus = 'IN_PROGRESS' | 'SUBMITTED' | 'AUTO_SUBMITTED';
export type QuestionType = 'MCQ' | 'TEXT' | 'CODE_SUBMISSION' | 'GROUP';

export interface TakeOption {
  id: string;
  optionText: string;
}

export interface TakeQuestion {
  id: string;
  displayOrder: number;
  type: QuestionType;
  title: string;
  body: string;
  maxScore: number;
  options: TakeOption[] | null;
  subQuestions?: TakeQuestion[];
}

export interface TakeAnswer {
  questionId: string;
  selectedOptionIds: string[] | null;
  textContent: string | null;
  savedAt: string;
}

export interface AssessmentTakeResponse {
  assessmentId: string;
  title: string;
  description: string | null;
  totalQuestionCount: number;
  startedAt: string;
  deadline: string;
  questions: TakeQuestion[];
  answers: TakeAnswer[];
}

export interface AnswerInput {
  questionId: string;
  selectedOptionIds?: string[] | null;
  textContent?: string | null;
}

export interface SaveAnswersRequest {
  answers: AnswerInput[];
}

export interface SaveAnswersResponse {
  answers: TakeAnswer[];
}

export interface SubmitRequest {
  autoSubmitted: boolean;
}

export interface SubmitResponse {
  submissionId: string;
  assessmentTitle: string;
  status: SubmissionStatus;
  submittedAt: string;
  answeredCount: number;
  totalQuestionCount: number;
}

export interface ClarificationRequest {
  questionId: string;
  candidateNote?: string | null;
}

export interface ClarificationResponse {
  clarification: string;
  remainingForQuestion: number;
  remainingForAssessment: number;
  degraded: boolean;
}
