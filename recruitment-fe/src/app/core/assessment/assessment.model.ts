import { Difficulty, QuestionType } from '../question/question.model';

export type AssessmentStatus = 'DRAFT' | 'PUBLISHED';

export interface RandomisationQuota {
  questionType: QuestionType;
  count: number;
}

export interface Assessment {
  id: string;
  title: string;
  description: string | null;
  timeLimitMinutes: number;
  status: AssessmentStatus;
  questionCount: number;
  passwordProtected: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AssessmentQuestion {
  questionId: string;
  title: string;
  type: QuestionType;
  displayOrder: number;
  subQuestionCount: number;
  difficulty: Difficulty | null;
}

export interface AssessmentDetail {
  id: string;
  title: string;
  description: string | null;
  timeLimitMinutes: number;
  status: AssessmentStatus;
  questions: AssessmentQuestion[];
  passwordProtected: boolean;
  randomiseQuestions: boolean;
  randomisationQuotas: RandomisationQuota[];
  createdAt: string;
  updatedAt: string;
}

export interface AssessmentRequest {
  title: string;
  description: string | null;
  timeLimitMinutes: number;
  accessPassword?: string | null;
  randomiseQuestions: boolean;
  randomisationQuotas: RandomisationQuota[];
}

export interface AddQuestionRequest {
  questionId: string;
  displayOrder: number;
}

export interface PreviewOption {
  id: string;
  text: string;
}

export interface PreviewQuestion {
  id: string;
  type: QuestionType;
  body: string;
  maxScore: number;
  options: PreviewOption[] | null;
  languageHint: string | null;
  subQuestions?: PreviewQuestion[];
}

export interface AssessmentPreview {
  id: string;
  title: string;
  description: string | null;
  timeLimitMinutes: number;
  passwordRequired: boolean;
  randomiseQuestions: boolean;
  randomisationQuotas: RandomisationQuota[];
  questions: PreviewQuestion[];
}

export interface AssemblyQuota {
  tag: string | null;
  difficulty: Difficulty | null;
  count: number;
}

export interface SuggestedAssemblyQuestion {
  id: string;
  type: QuestionType;
  title: string;
  difficulty: Difficulty | null;
  tags: string[];
}

export interface AssemblyQuotaOutcome {
  quota: AssemblyQuota;
  suggested: SuggestedAssemblyQuestion[];
  shortfall: number;
}

export interface AssemblySuggestionResponse {
  outcomes: AssemblyQuotaOutcome[];
}
