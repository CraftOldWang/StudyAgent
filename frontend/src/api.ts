import type {
  AgentSearchResult,
  DocumentItem,
  KnowledgeBase,
  SearchResult,
  UploadResult,
} from './types'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('X-User-Id', '1')
  if (init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError('无法连接后端，请确认服务已在 8082 端口启动。')
  }

  let envelope: ApiResponse<T> | undefined
  try {
    envelope = (await response.json()) as ApiResponse<T>
  } catch {
    throw new ApiError(`后端返回了无法解析的响应（HTTP ${response.status}）。`, response.status)
  }

  if (!response.ok || envelope.code !== 0) {
    throw new ApiError(envelope.message || `请求失败（HTTP ${response.status}）`, response.status)
  }
  return envelope.data
}

export const api = {
  listKnowledgeBases: () => apiRequest<KnowledgeBase[]>('/api/knowledge-bases'),
  createKnowledgeBase: (name: string) =>
    apiRequest<KnowledgeBase>('/api/knowledge-bases', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),
  renameKnowledgeBase: (id: string, name: string) =>
    apiRequest<KnowledgeBase>(`/api/knowledge-bases/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    }),
  listDocuments: (knowledgeBaseId: string) =>
    apiRequest<DocumentItem[]>(`/api/knowledge-bases/${knowledgeBaseId}/documents`),
  uploadPdf: (knowledgeBaseId: string, file: File) => {
    const body = new FormData()
    body.append('file', file)
    return apiRequest<UploadResult>(`/api/files/upload?knowledgeBaseId=${knowledgeBaseId}`, {
      method: 'POST',
      body,
    })
  },
  search: (knowledgeBaseId: string, query: string) =>
    apiRequest<SearchResult>(`/api/knowledge-bases/${knowledgeBaseId}/search`, {
      method: 'POST',
      body: JSON.stringify({ query }),
    }),
  agentSearch: (knowledgeBaseId: string, query: string) =>
    apiRequest<AgentSearchResult>(`/api/knowledge-bases/${knowledgeBaseId}/agent-search`, {
      method: 'POST',
      body: JSON.stringify({ query }),
    }),
}
