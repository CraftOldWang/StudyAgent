import { type FormEvent, useState } from 'react'
import type { AgentSearchResult, SearchHit, SearchResult } from '../types'

type SearchMode = 'retrieval' | 'agent'

interface Props {
  disabled: boolean
  loading: boolean
  result: SearchResult | AgentSearchResult | null
  onSearch: (mode: SearchMode, query: string) => Promise<void>
}

function ResultHits({ hits }: { hits: SearchHit[] }) {
  if (hits.length === 0) return null
  return (
    <ol className="hit-list">
      {hits.map((hit) => (
        <li key={hit.chunkId}>
          <div className="hit-meta">
            <strong>{hit.provenance.documentTitle}</strong>
            <span>{hit.provenance.sourceLocation}</span>
            <span>相关度 {hit.score.toFixed(4)}</span>
          </div>
          <p>{hit.content}</p>
          <small>chunk #{hit.chunkId}</small>
        </li>
      ))}
    </ol>
  )
}

export function SearchPanel({ disabled, loading, result, onSearch }: Props) {
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<SearchMode>('retrieval')

  async function submit(event: FormEvent) {
    event.preventDefault()
    const normalized = query.trim()
    if (!normalized) return
    await onSearch(mode, normalized)
  }

  const agentResult = result && 'answer' in result ? result : null
  const retrievalResult = result && 'message' in result ? result : null
  const hits = result?.hits ?? []

  return (
    <section className="panel search-panel">
      <div className="panel-title-row">
        <div>
          <span className="eyebrow">检索演示</span>
          <h2>向资料提问</h2>
        </div>
        <div aria-label="检索方式" className="mode-switch" role="group">
          <button
            aria-pressed={mode === 'retrieval'}
            className={mode === 'retrieval' ? 'active' : ''}
            onClick={() => setMode('retrieval')}
            type="button"
          >
            普通检索
          </button>
          <button
            aria-pressed={mode === 'agent'}
            className={mode === 'agent' ? 'active' : ''}
            onClick={() => setMode('agent')}
            type="button"
          >
            Agent 检索
          </button>
        </div>
      </div>

      <form className="search-form" onSubmit={submit}>
        <textarea
          aria-label="检索问题"
          disabled={disabled || loading}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={disabled ? '知识库内有可检索文档后才能提问' : '输入一个只能从资料中回答的问题…'}
          rows={3}
          value={query}
        />
        <button disabled={disabled || loading || !query.trim()} type="submit">
          {loading ? '正在检索…' : mode === 'agent' ? '调用 Agent' : '开始检索'}
        </button>
      </form>

      {result && (
        <div className="search-result" aria-live="polite">
          {agentResult && (
            <div className="answer-card">
              <div className="answer-heading">
                <strong>Agent 回答</strong>
                <span className={agentResult.toolInvoked ? 'tool-used' : 'tool-not-used'}>
                  {agentResult.toolInvoked ? '已调用 knowledge_search' : '未调用检索工具'}
                </span>
              </div>
              <p>{agentResult.answer}</p>
            </div>
          )}
          {retrievalResult && <p className="result-message">{retrievalResult.message}</p>}
          {hits.length === 0 ? (
            <div className="no-results">没有找到可展示的资料出处。</div>
          ) : (
            <>
              <h3>资料出处 <span>{hits.length}</span></h3>
              <ResultHits hits={hits} />
            </>
          )}
        </div>
      )}
    </section>
  )
}
