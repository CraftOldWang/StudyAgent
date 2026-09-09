import { useEffect, useState } from 'react'
import { apiRequest } from '../api'
import { Feedback } from './ui/Feedback'

export interface ToolCall { id: string; name: string; status: string; input?: unknown; output?: unknown; error?: string }
const labels: Record<string, string> = {
  knowledge_search: '搜索资料', knowledge_read: '读取资料', learning_explanation_done: '保存讲解进度',
  learning_quiz_publish: '生成选择题', learning_quiz_submit: '批改选择题', learning_cards_begin: '进入卡片阶段',
  learning_cards_publish: '保存卡片草稿',
}

export function ToolCalls({ sessionId, turnId, live = [] }: { sessionId?: string; turnId?: string; live?: ToolCall[] }) {
  const [saved, setSaved] = useState<ToolCall[]>([])
  const [error, setError] = useState('')
  useEffect(() => {
    if (!sessionId || !turnId) return
    let active = true
    apiRequest<ToolCall[]>(`/api/learning/sessions/${sessionId}/turns/${turnId}/tools`)
      .then(value => { if (active) setSaved(value) })
      .catch(e => { if (active) setError(String(e.message || e)) })
    return () => { active = false }
  }, [sessionId, turnId])
  return <div className="tool-calls">
    {error && <Feedback error>工具记录读取失败：{error}</Feedback>}
    {(turnId ? saved : live).map(call => <details className="tool-call" key={call.id}>
      <summary>{labels[call.name] || call.name}<span className="muted">{call.status === 'STARTED' ? '执行中' : call.status === 'SUCCEEDED' ? '已完成' : '失败'}</span></summary>
      <p className="muted">{call.name}</p>
      <strong>参数</strong><pre>{JSON.stringify(call.input, null, 2)}</pre>
      {call.output != null && <><strong>结果</strong><pre>{JSON.stringify(call.output, null, 2)}</pre></>}
      {call.error && <Feedback error>{call.error}</Feedback>}
    </details>)}
  </div>
}
