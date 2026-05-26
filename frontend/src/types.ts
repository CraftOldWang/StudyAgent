export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface KnowledgeBase {
  id: number;
  userId: number;
  name: string;
  description?: string | null;
  status: "ACTIVE" | "ARCHIVED" | "DELETED" | string;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  fileId: number;
  title: string;
  sourceType: string;
  parseStatus: string;
  indexStatus: string;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadResult {
  fileId: number;
  documentId: number;
  status: string;
}

export interface RagReference {
  chunkId: number;
  documentId: number;
  knowledgeBaseId: number;
  parentChunkId?: number | null;
  chunkIndex: number;
  documentTitle: string;
  content: string;
  retrievalSource: string;
  score: number;
}

export interface RagAnswer {
  answer: string;
  references: RagReference[];
}

export interface RagSearchResult {
  question: string;
  references: RagReference[];
}

export interface LearningSessionResponse {
  sessionId: number;
  agentRunId: number;
  status: string;
}

export interface ReviewCard {
  id: number;
  knowledgeBaseId?: number | null;
  documentId?: number | null;
  sessionId?: number | null;
  front: string;
  back: string;
  tagsJson?: string | null;
  status: "ACTIVE" | "SUSPENDED" | "DELETED" | string;
  cardState: string;
  dueAt: string;
  lastReviewedAt?: string | null;
  stability: number;
  difficulty: number;
  elapsedDays: number;
  scheduledDays: number;
  reps: number;
  lapses: number;
}

export interface ReviewRecord {
  id: number;
  cardId: number;
  rating: string;
  reviewedAt: string;
  scheduledDaysBefore: number;
  scheduledDaysAfter: number;
  stabilityBefore: number;
  stabilityAfter: number;
  difficultyBefore: number;
  difficultyAfter: number;
  stateBefore: string;
  stateAfter: string;
  dueAtBefore: string;
  dueAtAfter: string;
}

export interface ReviewSubmitResponse {
  card: ReviewCard;
  record: ReviewRecord;
}

export interface AgentEvent {
  id: number;
  event: string;
  data: unknown;
  receivedAt: string;
}

export interface ChatMessage {
  id: number;
  role: "user" | "assistant" | "system";
  content: string;
}

export type ViewKey = "knowledge" | "agent" | "rag" | "review";
