export interface KnowledgeBase {
  id: number
  name: string
  createdAt: string
  updatedAt: string
}

export interface DocumentItem {
  id: number
  knowledgeBaseId: number
  fileRecordId: number
  title: string
  contentType: string
  pipelineStatus: string
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface UploadResult {
  fileId: number
  documentId: number
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
