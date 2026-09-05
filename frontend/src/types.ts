export interface KnowledgeBase {
  id: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface DocumentItem {
  id: string
  knowledgeBaseId: string
  fileRecordId: string
  title: string
  contentType: string
  pipelineStatus: string
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface UploadResult {
  fileId: string
  documentId: string
  status: string
}

export interface Provenance {
  documentId: string
  documentTitle: string
  sourceLocation: string
}

export interface SearchHit {
  chunkId: string
  content: string
  provenance: Provenance
  score: number
}

export interface SearchResult {
  query: string
  message: string
  hits: SearchHit[]
}

export interface AgentSearchResult {
  query: string
  answer: string
  toolInvoked: boolean
  hits: SearchHit[]
}
