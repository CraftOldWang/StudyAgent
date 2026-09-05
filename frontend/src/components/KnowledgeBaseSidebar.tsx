import { type FormEvent, useState } from 'react'
import type { KnowledgeBase } from '../types'

interface Props {
  items: KnowledgeBase[]
  selectedId: string | null
  loading: boolean
  busy: boolean
  onSelect: (id: string) => void
  onCreate: (name: string) => Promise<void>
  onRename: (id: string, name: string) => Promise<void>
}

export function KnowledgeBaseSidebar({
  items,
  selectedId,
  loading,
  busy,
  onSelect,
  onCreate,
  onRename,
}: Props) {
  const [name, setName] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')

  async function submitCreate(event: FormEvent) {
    event.preventDefault()
    const normalized = name.trim()
    if (!normalized) return
    await onCreate(normalized)
    setName('')
  }

  function beginRename(item: KnowledgeBase) {
    setEditingId(item.id)
    setRenameValue(item.name)
  }

  async function submitRename(event: FormEvent, id: string) {
    event.preventDefault()
    const normalized = renameValue.trim()
    if (!normalized) return
    await onRename(id, normalized)
    setEditingId(null)
  }

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">S</span>
        <div>
          <strong>StudyAgent</strong>
          <small>资料驱动学习</small>
        </div>
      </div>

      <form className="create-form" onSubmit={submitCreate}>
        <label htmlFor="knowledge-base-name">新建知识库</label>
        <div className="inline-form">
          <input
            id="knowledge-base-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="例如：Java 并发"
            maxLength={100}
          />
          <button disabled={busy || !name.trim()} type="submit">创建</button>
        </div>
      </form>

      <div className="sidebar-heading">
        <span>我的知识库</span>
        <span className="count">{items.length}</span>
      </div>

      {loading ? (
        <p className="muted sidebar-state">正在加载…</p>
      ) : items.length === 0 ? (
        <p className="muted sidebar-state">还没有知识库，从上方创建一个。</p>
      ) : (
        <ul className="knowledge-list">
          {items.map((item) => (
            <li className={item.id === selectedId ? 'selected' : ''} key={item.id}>
              {editingId === item.id ? (
                <form className="rename-form" onSubmit={(event) => submitRename(event, item.id)}>
                  <input
                    aria-label={`重命名 ${item.name}`}
                    autoFocus
                    value={renameValue}
                    onChange={(event) => setRenameValue(event.target.value)}
                    maxLength={100}
                  />
                  <button disabled={busy || !renameValue.trim()} type="submit">保存</button>
                  <button className="ghost" onClick={() => setEditingId(null)} type="button">取消</button>
                </form>
              ) : (
                <>
                  <button className="knowledge-select" onClick={() => onSelect(item.id)} type="button">
                    <span className="book-icon">▤</span>
                    <span>{item.name}</span>
                  </button>
                  <button
                    aria-label={`重命名 ${item.name}`}
                    className="icon-button"
                    onClick={() => beginRename(item)}
                    type="button"
                  >
                    ✎
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </aside>
  )
}
