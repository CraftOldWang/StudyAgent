import { isDocumentTerminal, statusLabel } from '../status'
import type { DocumentItem, KnowledgeBase } from '../types'
import { UploadWidget } from './UploadWidget'

function displayTime(value: string) {
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`
  return new Date(utc).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
}

interface Props {
  knowledgeBase: KnowledgeBase
  documents: DocumentItem[]
  loading: boolean
  onUploaded: (knowledgeBaseId: string) => void
}

export function DocumentPanel({ knowledgeBase, documents, loading, onUploaded }: Props) {
  return (
    <section className="panel documents-panel">
      <div className="panel-header">
        <div>
          <span className="eyebrow">当前知识库</span>
          <h1>{knowledgeBase.name}</h1>
          <p>整理课件与往年习题，处理完成后即可检索和制定学习计划。</p>
        </div>
      </div>
      <UploadWidget knowledgeBase={knowledgeBase} onUploaded={onUploaded} />

      {loading ? (
        <div className="empty-state">正在读取文档状态…</div>
      ) : documents.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">资料</span>
          <strong>还没有资料</strong>
          <p>从一份课件开始。资料处理完成后，会显示为“可检索”。</p>
        </div>
      ) : (
        <div className="document-table-wrap">
          <table>
            <thead>
              <tr><th>文档</th><th>状态</th><th>更新时间</th></tr>
            </thead>
            <tbody>
              {documents.map((document) => (
                <tr key={document.id}>
                  <td>
                    <strong>{document.title}</strong>
                    {document.errorMessage && <span className="document-error">{document.errorMessage}</span>}
                  </td>
                  <td>
                    <span className={`status status-${document.pipelineStatus.toLowerCase()}`}>
                      {!isDocumentTerminal(document.pipelineStatus) && <span className="pulse" />}
                      {statusLabel(document.pipelineStatus)}
                    </span>
                  </td>
                  <td>{displayTime(document.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
