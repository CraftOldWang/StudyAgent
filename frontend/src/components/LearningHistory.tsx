import { useEffect, useState } from 'react'
import { learningApi } from '../learningApi'
import type { SessionEntry } from '../learningTypes'
import { Feedback } from './ui/Feedback'

export function LearningHistory({ knowledgeBaseId, visible, onSelect, onOutline }: {
  knowledgeBaseId: string; visible: boolean; onSelect: (id: string) => void; onOutline: () => void
}) {
  const [items, setItems] = useState<SessionEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  useEffect(() => {
    if (!visible) return
    let active = true
    setLoading(true); setError('')
    learningApi.listSessions(knowledgeBaseId).then(result => { if (active) setItems(result) })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [knowledgeBaseId, visible, version])
  return <section className="panel history-page">
    <div className="panel-header"><div><span className="eyebrow">当前资料库</span><h1>历史会话</h1><p>点击继续原来的对话，学习大纲与进度会一起恢复。</p></div><button className="secondary" type="button" onClick={onOutline}>前往学习大纲</button></div>
    {loading ? <Feedback>正在读取历史会话…</Feedback> : error ? <Feedback error>{error}<button className="text-button" type="button" onClick={() => setVersion(v => v + 1)}>重新读取</button></Feedback> : items.length === 0 ? <Feedback>还没有学习会话。在学习大纲页选择已有大纲，点击“开始学习”。</Feedback> : <ul className="session-list">{items.map(item => <li key={item.id}><button type="button" className="session-entry" onClick={() => onSelect(item.id)}>
      <span><strong>{item.learningGoal}</strong><small>最近学习 {item.updatedAt.replace('T', ' ').slice(0, 16)}</small></span><span className="session-progress">{item.status === 'COMPLETED' ? '课程已完成' : '继续学习'}<small>已完成 {item.completedPoints} / {item.totalPoints} 个知识点</small></span><span aria-hidden="true">→</span>
    </button></li>)}</ul>}
  </section>
}
