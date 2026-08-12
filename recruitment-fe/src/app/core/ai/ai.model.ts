import { Difficulty, QuestionType } from '../question/question.model';

export interface GenerateQuestionsRequest {
  type: QuestionType;
  topic: string;
  difficulty: Difficulty;
  count: number;
}
