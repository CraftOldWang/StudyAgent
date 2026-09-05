import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocumentItem } from './types'

const apiMock = vi.hoisted(() => ({
  listKnowledgeBases: vi.fn(),
  listDocuments: vi.fn(),
  createKnowledgeBase: vi.fn(),
  renameKnowledgeBase: vi.fn(),
  uploadPdf: vi.fn(),
  search: vi.fn(),
  agentSearch: vi.fn(),
}))

vi.mock('./api', () => ({ api: apiMock }))

import App from './App'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function document(id: string, knowledgeBaseId: string, title: string): DocumentItem {
  return {
    id,
    knowledgeBaseId,
    fileRecordId: id,
    title,
    contentType: 'application/pdf',
    pipelineStatus: 'INDEXED',
    errorMessage: null,
    createdAt: '2026-09-05T10:00:00',
    updatedAt: '2026-09-05T10:00:00',
  }
}

describe('App knowledge-base request association', () => {
  beforeEach(() => vi.clearAllMocks())

  it('ignores an earlier knowledge base response and its finally after selection changes', async () => {
    const first = deferred<DocumentItem[]>()
    const second = deferred<DocumentItem[]>()
    apiMock.listKnowledgeBases.mockResolvedValue([
      { id: '9007199254740993', name: '知识库一', createdAt: '', updatedAt: '' },
      { id: '9007199254740995', name: '知识库二', createdAt: '', updatedAt: '' },
    ])
    apiMock.listDocuments.mockImplementation((id: string) =>
      id === '9007199254740993' ? first.promise : second.promise)

    render(<App />)
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('9007199254740993'))
    fireEvent.click(screen.getByRole('button', { name: '▤ 知识库二' }))
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('9007199254740995'))

    first.resolve([document('11', '9007199254740993', '旧知识库.pdf')])
    await waitFor(() => expect(screen.getByText('正在读取文档状态…')).toBeInTheDocument())
    expect(screen.queryByText('旧知识库.pdf')).not.toBeInTheDocument()

    second.resolve([document('22', '9007199254740995', '当前知识库.pdf')])
    expect(await screen.findByText('当前知识库.pdf')).toBeInTheDocument()
    expect(screen.queryByText('旧知识库.pdf')).not.toBeInTheDocument()
  })
})
