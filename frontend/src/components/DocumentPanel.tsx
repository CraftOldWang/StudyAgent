import { type ChangeEvent, useRef, useState } from 'react'
import { isDocumentTerminal, statusLabel } from '../status'
import type { DocumentItem, KnowledgeBase } from '../types'

interface Props {
  knowledgeBase: KnowledgeBase
  documents: DocumentItem[]
  loading: boolean
  uploadBusy: boolean
  onUpload: (file: File) => Promise<void>
}

export function DocumentPanel({ knowledgeBase, documents, loading, uploadBusy, onUpload }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [fileError, setFileError] = useState('')

  async function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
    if (!isPdf) {
      setFileError('当前里程碑仅支持 PDF 文件。')
      return
    }
    setFileError('')
    await onUpload(file)
  }

  return (
    <section className="panel documents-panel">
      <div className="panel-header">
        <div>
          <span className="eyebrow">当前知识库</span>
          <h1>{knowledgeBase.name}</h1>
          <p>上传真实 PDF，处理完成后即可检索。</p>
        </div>
        <button disabled={uploadBusy} onClick={() => inputRef.current?.click()} type="button">
          {uploadBusy ? '正在上传…' : '上传 PDF'}
        </button>
        <input
          ref={inputRef}
          accept="application/pdf,.pdf"
          className="visually-hidden"
          onChange={chooseFile}
          type="file"
        />
      </div>
      {fileError && <p className="inline-error" role="alert">{fileError}</p>}

      {loading ? (
        <div className="empty-state">正在读取文档状态…</div>
      ) : documents.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">PDF</span>
          <strong>还没有资料</strong>
          <p>上传一份 PDF，系统会依次存储、解析、分块、向量化并建立索引。</p>
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
                  <td>{new Date(document.updatedAt).toLocaleString('zh-CN')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
