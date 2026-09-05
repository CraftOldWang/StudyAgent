export type LearningSessionStatus = 'ACTIVE' | 'COMPLETED'
export type KnowledgePointStatus = 'NEW' | 'EXPLAINING' | 'QUIZZING' | 'CARD_GENERATING' | 'COMPLETED'

export interface KnowledgePoint {
  id: string
  sequenceNo: number
  topic: string
  subtopics: string[]
  estimatedMinutes: number
  status: KnowledgePointStatus
  explanation: string | null
  errorMessage: string | null
}

export interface QuizQuestion {
  questionIndex: number
  question: string
  options: string[]
  sourceChunkId: string | null
}

export interface QuizFeedback {
  questionIndex: number
  correct: boolean
  correctAnswer: string
  explanation: string
}

export interface Quiz {
  quizId: string
  knowledgePointId: string
  questions: QuizQuestion[]
  score: number | null
  feedback: QuizFeedback[] | null
}

export interface ReviewCard {
  id: string
  front: string
  back: string
  sourceChunkId: string | null
}

export interface LearningSession {
  id: string
  learningGoal: string
  knowledgeBaseId: string
  status: LearningSessionStatus
  errorMessage: string | null
  activeKnowledgePoint: KnowledgePoint | null
  plan: KnowledgePoint[]
  currentQuiz: Quiz | null
  cards: ReviewCard[]
}

export interface CreatedSession {
  traceId: string
  session: LearningSession
}

export interface LearningTurn {
  traceId: string
  answer: string
  session: LearningSession
}

export interface GeneratedQuiz {
  traceId: string
  quiz: Quiz
  session: LearningSession
}

export interface QuizResult {
  traceId: string
  quizId: string
  score: number
  feedback: QuizFeedback[]
  session: LearningSession
}

export interface GeneratedCards {
  traceId: string
  knowledgePointId: string
  cards: ReviewCard[]
  session: LearningSession
}
