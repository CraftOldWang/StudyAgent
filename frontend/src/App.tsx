import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { DocumentPanel } from './components/DocumentPanel'
import { KnowledgeBaseSidebar } from './components/KnowledgeBaseSidebar'
import { SearchPanel } from './components/SearchPanel'
import { isDocumentTerminal } from './status'
import type { AgentSearchResult, DocumentItem, KnowledgeBase, SearchResult } from './types'
import { useDocumentPolling } from './useDocumentPolling'

export default function App() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [initialLoading, setInitialLoading] = useState(true)
  const [documentsLoading, setDocumentsLoading] = useState(false)
  const [mutationBusy, setMutationBusy] = useState(false)
  const [uploadBusy, setUploadBusy] = useState(false)
  const [searchBusy, setSearchBusy] = useState(false)
  const [searchResult, setSearchResult] = useState<SearchResult | AgentSearchResult | null>(null)
  const [error, setError] = useState('')
  const selectedIdRef = useRef<number | null>(selectedId)
  const documentRequestIdRef = useRef(0)
  const searchRequestIdRef = useRef(0)
  selectedIdRef.current = selectedId

  const selectedKnowledgeBase = useMemo(
    () => knowledgeBases.find((item) => item.id === selectedId) ?? null,
    [knowledgeBases, selectedId],
  )

  const reportError = useCallback((caught: unknown) => {
    setError(caught instanceof Error ? caught.message : '发生未知错误。')
  }, [])

  useEffect(() => {
    let active = true
    api.listKnowledgeBases()
      .then((items) => {
        if (!active) return
        setKnowledgeBases(items)
        if (selectedIdRef.current === null) selectKnowledgeBase(items[0]?.id ?? null)
      })
      .catch((caught) => active && reportError(caught))
      .finally(() => active && setInitialLoading(false))
    return () => { active = false }
  }, [reportError])

  const refreshDocuments = useCallback(async (silent = false) => {
    if (!selectedId) return
    const knowledgeBaseId = selectedId
    const requestId = ++documentRequestIdRef.current
    if (!silent) setDocumentsLoading(true)
    try {
      const items = await api.listDocuments(knowledgeBaseId)
      if (selectedIdRef.current === knowledgeBaseId && documentRequestIdRef.current === requestId) {
        setDocuments(items)
      }
    } catch (caught) {
      if (selectedIdRef.current === knowledgeBaseId && documentRequestIdRef.current === requestId) {
        reportError(caught)
      }
    } finally {
      if (!silent && selectedIdRef.current === knowledgeBaseId && documentRequestIdRef.current === requestId) {
        setDocumentsLoading(false)
      }
    }
  }, [reportError, selectedId])

  useEffect(() => {
    setDocuments([])
    setSearchResult(null)
    if (selectedId) void refreshDocuments()
  }, [refreshDocuments, selectedId])

  useDocumentPolling(selectedId, documents, refreshDocuments)

  function selectKnowledgeBase(id: number | null) {
    selectedIdRef.current = id
    documentRequestIdRef.current += 1
    searchRequestIdRef.current += 1
    setDocumentsLoading(false)
    setSearchBusy(false)
    setSelectedId(id)
  }

  async function createKnowledgeBase(name: string) {
    setMutationBusy(true)
    setError('')
    try {
      const created = await api.createKnowledgeBase(name)
      setKnowledgeBases((items) => [created, ...items])
      selectKnowledgeBase(created.id)
    } catch (caught) {
      reportError(caught)
    } finally {
      setMutationBusy(false)
    }
  }

  async function renameKnowledgeBase(id: number, name: string) {
    setMutationBusy(true)
    setError('')
    try {
      const renamed = await api.renameKnowledgeBase(id, name)
      setKnowledgeBases((items) => items.map((item) => item.id === id ? renamed : item))
    } catch (caught) {
      reportError(caught)
    } finally {
      setMutationBusy(false)
    }
  }

  async function uploadPdf(file: File) {
    if (!selectedId) return
    setUploadBusy(true)
    setError('')
    try {
      await api.uploadPdf(selectedId, file)
      await refreshDocuments(true)
    } catch (caught) {
      reportError(caught)
    } finally {
      setUploadBusy(false)
    }
  }

  async function search(mode: 'retrieval' | 'agent', query: string) {
    if (!selectedId) return
    const knowledgeBaseId = selectedId
    const requestId = ++searchRequestIdRef.current
    setSearchBusy(true)
    setSearchResult(null)
    setError('')
    try {
      const result = mode === 'agent'
        ? await api.agentSearch(knowledgeBaseId, query)
        : await api.search(knowledgeBaseId, query)
      if (selectedIdRef.current === knowledgeBaseId && searchRequestIdRef.current === requestId) {
        setSearchResult(result)
      }
    } catch (caught) {
      if (selectedIdRef.current === knowledgeBaseId && searchRequestIdRef.current === requestId) {
        reportError(caught)
      }
    } finally {
      if (selectedIdRef.current === knowledgeBaseId && searchRequestIdRef.current === requestId) {
        setSearchBusy(false)
      }
    }
  }

  const hasIndexedDocument = documents.some((document) =>
    isDocumentTerminal(document.pipelineStatus) && document.pipelineStatus.toUpperCase() === 'INDEXED')

  return (
    <div className="app-shell">
      <KnowledgeBaseSidebar
        busy={mutationBusy}
        items={knowledgeBases}
        loading={initialLoading}
        onCreate={createKnowledgeBase}
        onRename={renameKnowledgeBase}
        onSelect={selectKnowledgeBase}
        selectedId={selectedId}
      />
      <main>
        {error && (
          <div className="error-banner" role="alert">
            <span>{error}</span>
            <button aria-label="关闭错误" onClick={() => setError('')} type="button">×</button>
          </div>
        )}

        {selectedKnowledgeBase ? (
          <div className="content-grid">
            <DocumentPanel
              documents={documents}
              knowledgeBase={selectedKnowledgeBase}
              loading={documentsLoading}
              onUpload={uploadPdf}
              uploadBusy={uploadBusy}
            />
            <SearchPanel
              disabled={!hasIndexedDocument}
              loading={searchBusy}
              onSearch={search}
              result={searchResult}
            />
          </div>
        ) : (
          <section className="welcome-state">
            <span className="welcome-mark">S</span>
            <h1>从一份真实资料开始</h1>
            <p>先创建知识库，再上传 PDF。处理完成后，可以直接检索或让 Agent 基于资料回答。</p>
          </section>
        )}
      </main>
    </div>
  )
}
