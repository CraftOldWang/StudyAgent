export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export type EntityId = string;

export interface KnowledgeBase {
  id: EntityId;
  userId: EntityId;
  name: string;
  description?: string | null;
  status: "ACTIVE" | "ARCHIVED" | "DELETED" | string;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  id: EntityId;
  knowledgeBaseId: EntityId;
  fileId: EntityId;
  title: string;
  sourceType: string;
  parseStatus: string;
  indexStatus: string;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadResult {
  fileId: EntityId;
  documentId: EntityId;
  status: string;
}

export interface RagReference {
  chunkId: EntityId;
  documentId: EntityId;
  knowledgeBaseId: EntityId;
  parentChunkId?: EntityId | null;
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
  sessionId: EntityId;
  agentRunId: EntityId;
  status: string;
}

export interface ReviewCard {
  id: EntityId;
  knowledgeBaseId?: EntityId | null;
  documentId?: EntityId | null;
  sessionId?: EntityId | null;
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
  id: EntityId;
  cardId: EntityId;
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
