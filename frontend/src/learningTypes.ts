export type LearningSessionStatus = 'ACTIVE' | 'COMPLETED'
export type KnowledgePointStatus = 'NEW' | 'EXPLAINING' | 'QUIZZING' | 'FEEDBACK' | 'CARD_GENERATING' | 'CARD_CONFIRMING' | 'COMPLETED'

export interface KnowledgePoint {
  id: string
  sequenceNo: number
  topic: string
  subtopics: string[]
  estimatedMinutes: number
  status: KnowledgePointStatus
  explanation: string | null
  errorMessage: string | null
  chapterId?: string | null
  chapterTitle?: string | null
  priority?: string | null
  sourceChunkIds?: string[]
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
  turn?: ConversationTurn
}

export interface ConversationTurn {
  knowledgePointId?: string
  id: string
  requestId: string
  userMessage: string
  assistantMessage: string | null
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  phase: string
  errorMessage: string | null
  artifactJson: string | null
  createdAt: string
  traceId: string
}

export interface PlanningTask {
  knowledgePointId: string
  chapterId: string
  chapterTitle: string
  topic: string
  subtopics: string[]
  sourceChunkIds: string[]
  priority: string
  estimatedMinutes: number
  reason: string
}

export interface PlanningView {
  id: string
  knowledgeBaseId: string
  learningGoal: string
  status: string
  errorMessage: string | null
  sessionId: string | null
  stages: { id: string; stage: string; status: string; errorMessage: string | null; attemptCount: number }[]
  result: {
    tasks: PlanningTask[]
    emphasis: {
      matches: { knowledgePointId: string; quote: string; lessonQuote: string; reason: string; priority: string }[]
      unmatched: { quote: string; reason: string }[]
    }
  } | null
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
