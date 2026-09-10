import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { DocumentPanel } from './components/DocumentPanel'
import { KnowledgeBaseSidebar } from './components/KnowledgeBaseSidebar'
import { LearningPanel } from './components/LearningPanel'
import { LearningHistory } from './components/LearningHistory'
import { PlanningStart } from './components/PlanningStart'
import { SearchPanel } from './components/SearchPanel'
import { isDocumentTerminal } from './status'
import type { AgentSearchResult, DocumentItem, KnowledgeBase, SearchResult } from './types'
import { useDocumentPolling } from './useDocumentPolling'

export default function App() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [initialLoading, setInitialLoading] = useState(true)
  const [documentsLoading, setDocumentsLoading] = useState(false)
  const [mutationBusy, setMutationBusy] = useState(false)
  const [searchBusy, setSearchBusy] = useState(false)
  const [searchResult, setSearchResult] = useState<SearchResult | AgentSearchResult | null>(null)
  const [error, setError] = useState('')
  const [view, setView] = useState<'knowledge' | 'outline' | 'learning'>('knowledge')
  const [learningVisited, setLearningVisited] = useState(false)
  const [outlineVisited, setOutlineVisited] = useState(false)
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(true)
  const [outlineSessionId, setOutlineSessionId] = useState<string | null>(null)
  const selectedIdRef = useRef<string | null>(selectedId)
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
    if (selectedIdRef.current !== knowledgeBaseId) return
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

  function selectKnowledgeBase(id: string | null) {
    selectedIdRef.current = id
    documentRequestIdRef.current += 1
    searchRequestIdRef.current += 1
    setDocumentsLoading(false)
    setSearchBusy(false)
    setSelectedId(id)
    setOutlineSessionId(null)
  }

  async function createKnowledgeBase(name: string) {
    if (initialLoading) return false
    setMutationBusy(true)
    setError('')
    try {
      const created = await api.createKnowledgeBase(name)
      setKnowledgeBases((items) => [created, ...items])
      selectKnowledgeBase(created.id)
      return true
    } catch (caught) {
      reportError(caught)
      return false
    } finally {
      setMutationBusy(false)
    }
  }

  async function renameKnowledgeBase(id: string, name: string) {
    setMutationBusy(true)
    setError('')
    try {
      const renamed = await api.renameKnowledgeBase(id, name)
      setKnowledgeBases((items) => items.map((item) => item.id === id ? renamed : item))
      return true
    } catch (caught) {
      reportError(caught)
      return false
    } finally {
      setMutationBusy(false)
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

  useEffect(() => { document.title = `${view === 'knowledge' ? '资料库' : view === 'outline' ? '学习大纲' : '学习对话'} — StudyPilot` }, [view])

  return (
    <div className="app-shell">
      <KnowledgeBaseSidebar
        busy={mutationBusy || initialLoading}
        items={knowledgeBases}
        loading={initialLoading}
        onCreate={createKnowledgeBase}
        onRename={renameKnowledgeBase}
        onSelect={selectKnowledgeBase}
        selectedId={selectedId}
      />
      <main className="app-main">
        {error && (
          <div className="error-banner" role="alert">
            <span>{error}</span>
            <button aria-label="关闭错误" onClick={() => setError('')} type="button">×</button>
          </div>
        )}

        {selectedKnowledgeBase ? (
          <>
            <nav aria-label="工作区" className="view-tabs">
              <button
                aria-current={view === 'knowledge' ? 'page' : undefined}
                className={view === 'knowledge' ? 'active' : ''}
                onClick={() => setView('knowledge')}
                type="button"
              >
                知识库
              </button>
              <button aria-current={view === 'outline' ? 'page' : undefined} className={view === 'outline' ? 'active' : ''}
                onClick={() => { setOutlineVisited(true); setOutlineSessionId(null); setView('outline') }} type="button">学习大纲</button>
              <button
                aria-current={view === 'learning' ? 'page' : undefined}
                className={view === 'learning' ? 'active' : ''}
                onClick={() => { setLearningVisited(true); setView('learning') }}
                type="button"
              >
                学习对话
              </button>
            </nav>
            <div className="content-grid" hidden={view !== 'knowledge'}>
              <DocumentPanel
                documents={documents}
                knowledgeBase={selectedKnowledgeBase}
                loading={documentsLoading}
                onUploaded={(knowledgeBaseId) => {
                  if (selectedIdRef.current === knowledgeBaseId) void refreshDocuments(true)
                }}
              />
              <SearchPanel
                disabled={!hasIndexedDocument}
                loading={searchBusy}
                onSearch={search}
                result={searchResult}
              />
            </div>
            <div className="learning-view" hidden={view !== 'outline'}>
              {outlineVisited && <PlanningStart key={selectedKnowledgeBase.id} knowledgeBase={selectedKnowledgeBase}
                visible={view === 'outline'} requestedSessionId={outlineSessionId} onSession={async session => {
                  setActiveSessionId(session.id); setShowHistory(false); setLearningVisited(true); setView('learning')
                }} />}
            </div>
            <div className="learning-view" hidden={view !== 'learning'}>
              {learningVisited && <>
                <div className="history-view" hidden={!showHistory}>
                  <LearningHistory knowledgeBaseId={selectedKnowledgeBase.id} visible={view === 'learning' && showHistory}
                    onSelect={id => { setActiveSessionId(id); setShowHistory(false) }}
                    onOutline={() => { setOutlineVisited(true); setOutlineSessionId(null); setView('outline') }} />
                </div>
                <div className="active-chat-view" hidden={showHistory}>
                  {activeSessionId && <LearningPanel key={activeSessionId} initialSessionId={activeSessionId}
                    knowledgeBase={selectedKnowledgeBase} onBack={() => setShowHistory(true)}
                    onOutline={() => { setOutlineSessionId(activeSessionId); setOutlineVisited(true); setView('outline') }}
                    onSessionKnowledgeBase={(knowledgeBaseId) => {
                      if (knowledgeBases.some((item) => item.id === knowledgeBaseId)) selectKnowledgeBase(knowledgeBaseId)
                    }} />}
                </div>
              </>}
            </div>
          </>
        ) : (
          <section className="welcome-state">
            <span className="welcome-mark">S</span>
            <h1>从一份真实资料开始</h1>
            <p>先创建知识库，再上传课件与习题。处理完成后，可以检索资料并制定学习计划。</p>
          </section>
        )}
      </main>
    </div>
  )
}
