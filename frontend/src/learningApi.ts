import { apiRequest } from './api'
import type {
  CreatedSession,
  GeneratedCards,
  GeneratedQuiz,
  LearningSession,
  LearningTurn,
  QuizResult,
} from './learningTypes'

const SESSION_PATH = '/api/learning/sessions'

export const learningApi = {
  createSession: (knowledgeBaseId: string, learningGoal: string) =>
    apiRequest<CreatedSession>(SESSION_PATH, {
      method: 'POST',
      body: JSON.stringify({ knowledgeBaseId, learningGoal }),
    }),
  getSession: (sessionId: string) =>
    apiRequest<LearningSession>(`${SESSION_PATH}/${sessionId}`),
  explain: (sessionId: string) =>
    apiRequest<LearningTurn>(`${SESSION_PATH}/${sessionId}/explain`, { method: 'POST' }),
  sendMessage: (sessionId: string, message: string) =>
    apiRequest<LearningTurn>(`${SESSION_PATH}/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),
  generateQuiz: (sessionId: string) =>
    apiRequest<GeneratedQuiz>(`${SESSION_PATH}/${sessionId}/quiz`, { method: 'POST' }),
  submitQuiz: (sessionId: string, answers: string[]) =>
    apiRequest<QuizResult>(`${SESSION_PATH}/${sessionId}/quiz/submit`, {
      method: 'POST',
      body: JSON.stringify({ answers }),
    }),
  generateCards: (sessionId: string) =>
    apiRequest<GeneratedCards>(`${SESSION_PATH}/${sessionId}/cards`, { method: 'POST' }),
}
