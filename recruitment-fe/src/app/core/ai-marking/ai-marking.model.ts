export interface AiMarkingSuggestionResponse {
  answerId: string;
  score: number;
  maxScore: number;
  rationale: string;
  generatedAt: string;
}

export type AiSuggestionErrorKind = 'error' | 'ineligible';
