import { type FormEvent, useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { learningApi } from '../learningApi'
import type { LearningSession, PlanEntry, PlanningView } from '../learningTypes'
import type { DocumentItem, KnowledgeBase } from '../types'
import { Feedback } from './ui/Feedback'
import { Field, MultilineInput } from './ui/Field'
import { SourceLink, SourceProvider } from './SourceDrawer'
import { LearningPlan } from './LearningPlan'

type Role = 'lesson' | 'exercise' | 'unused'
const drafts = new Map<string, { roles: Record<string, Role>; goal: string }>()
const phaseLabel = (value: string) => ({ EXTRACT: '读取课件', OUTLINE: '整理大纲', EMPHASIS: '标注习题重点', EMPHASIS_REVIEW: '核对重点依据', TASKS: '安排学习任务' }[value.split('/')[0]] || value)
export const priorityLabel = (value?: string | null) => value === 'HIGH' ? '重点' : value === 'MEDIUM' ? '关注' : '基础'
const planStatus = (value: string) => ({ SUCCEEDED: '已生成', RUNNING: '生成中', FAILED: '生成未完成', READY: '待生成' }[value] || value)

export function PlanningStart({ knowledgeBase, visible, requestedSessionId, onSession }: {
  knowledgeBase: KnowledgeBase; visible: boolean; requestedSessionId: string | null
  onSession: (session: LearningSession) => Promise<void>
}) {
  const [entries, setEntries] = useState<PlanEntry[]>([])
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [roles, setRoles] = useState<Record<string, Role>>(() => drafts.get(knowledgeBase.id)?.roles || {})
  const [goal, setGoal] = useState(() => drafts.get(knowledgeBase.id)?.goal || '')
  const [plan, setPlan] = useState<PlanningView | null>(null)
  const [session, setSession] = useState<LearningSession | null>(null)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [documentError, setDocumentError] = useState('')
  const mounted = useRef(true)
  const lock = useRef(false)
  const selection = useRef<string | null>(null)
  const request = useRef(0)
  useEffect(() => { drafts.set(knowledgeBase.id, { roles, goal }) }, [knowledgeBase.id, roles, goal])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; request.current++ } }, [])
  useEffect(() => { if (visible) void reload() }, [visible, requestedSessionId])
  useEffect(() => {
    if (plan?.status !== 'RUNNING') return
    let active = true, fetching = false
    const timer = setInterval(async () => {
      if (fetching) return
      fetching = true
      try {
        const next = await learningApi.getPlan(plan.id)
        if (active) { setPlan(next); if (next.status !== 'RUNNING') await refreshEntries() }
      } catch (e) { if (active) setError(`进度读取失败：${e instanceof Error ? e.message : String(e)}`) }
      finally { fetching = false }
    }, 2500)
    return () => { active = false; clearInterval(timer) }
  }, [plan?.id, plan?.status])

  async function refreshEntries() {
    const items = await learningApi.listPlans(knowledgeBase.id)
    if (mounted.current) setEntries(items)
    return items
  }
  async function loadPlan(id: string) {
    const version = ++request.current
    selection.current = id; setCreating(false); setPlan(null); setSession(null); setLoading(true); setError('')
    try {
      const next = await learningApi.getPlan(id)
      const progress = next.sessionId ? await learningApi.getSession(next.sessionId) : null
      if (mounted.current && version === request.current) { setPlan(next); setSession(progress) }
    } catch (e) { if (mounted.current && version === request.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current && version === request.current) setLoading(false) }
  }
  async function reload() {
    if (lock.current) return
    setLoading(true); setError('')
    try {
      const items = await refreshEntries()
      if (!mounted.current) return
      const requested = requestedSessionId && items.find(item => item.sessionId === requestedSessionId)
      if (requested) await loadPlan(requested.id)
      else if (requestedSessionId) {
        const saved = await learningApi.getSession(requestedSessionId)
        if (mounted.current) { setSession(saved); setPlan(null); setCreating(false); selection.current = null }
      } else if (selection.current !== 'new') {
        const id = items.find(item => item.id === selection.current)?.id || items[0]?.id
        if (id) await loadPlan(id)
      }
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current) setLoading(false) }
  }
  async function newOutline() {
    request.current++; selection.current = 'new'; setCreating(true); setPlan(null); setSession(null); setError(''); setDocumentError(''); setLoading(true)
    try { const items = await api.listDocuments(knowledgeBase.id); if (mounted.current) setDocuments(items) }
    catch (e) { if (mounted.current) setDocumentError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current) setLoading(false) }
  }
  async function action(work: () => Promise<void>) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await work() }
    catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function execute(id: string) {
    setPlan(current => current && { ...current, status: 'RUNNING', errorMessage: null })
    const next = await learningApi.executePlan(id)
    if (mounted.current) setPlan(next)
    await refreshEntries()
  }
  const lessons = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'lesson').map(d => d.id)
  const exercises = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'exercise').map(d => d.id)
  async function generate(event: FormEvent) {
    event.preventDefault()
    if (!goal.trim() || !lessons.length) return
    await action(async () => {
      const created = await learningApi.createPlan(knowledgeBase.id, goal.trim(), lessons, exercises)
      if (!mounted.current) return
      selection.current = created.id; setPlan(created); setCreating(false)
      await refreshEntries()
      await execute(created.id)
    })
  }
  const tasks = plan?.result?.tasks || []
  const completed = session?.plan.filter(p => p.status === 'COMPLETED').length || 0
  const generating = busy || plan?.status === 'RUNNING'
  return <div className="outline-workspace">
    <aside className="panel catalog-panel" aria-label="已保存的大纲">
      <div className="catalog-heading"><h2>学习大纲</h2><p>一份课程大纲，持续记录学习进度。</p>
        <button type="button" disabled={generating} onClick={() => void newOutline()}>新建学习大纲</button></div>
      <div className="catalog-list">{entries.map(item => <button className="catalog-entry" aria-current={plan?.id === item.id ? 'true' : undefined} key={item.id} disabled={generating} onClick={() => void loadPlan(item.id)} type="button">
        <strong>{item.learningGoal}</strong><small>{planStatus(item.status)} · {item.updatedAt.replace('T', ' ').slice(0, 16)}</small>
      </button>)}</div>
      {!loading && entries.length === 0 && <p className="catalog-empty">还没有保存的大纲。</p>}
    </aside>
    <section className="panel outline-detail">
    <SourceProvider knowledgeBaseId={knowledgeBase.id}><div className="planning-start">
      <div className="section-intro"><span className="eyebrow">{knowledgeBase.name}</span><h1>{creating ? '建立课程学习大纲' : '课程路线与学习进度'}</h1>
        <p>大纲生成后会保存。每次学习都沿用它，完成的知识点会在这里标记。</p></div>
      {error && <Feedback error>{error}<button className="text-button" type="button" disabled={busy} onClick={() => void reload()}>重新读取</button></Feedback>}
      {loading && <Feedback>正在读取资料与已保存的大纲…</Feedback>}
      {!creating && !plan && !session && !loading && !error && <Feedback>点击“新建学习大纲”，用课件建立完整路线；有往年习题时，可以一起标注重点。</Feedback>}
      {creating && <form noValidate onSubmit={generate} className="plan-form">
        <Field id="learning-goal" label="课程学习目标"><MultilineInput id="learning-goal" rows={3} value={goal} maxLength={3000} onChange={e => setGoal(e.target.value)} placeholder="例如：系统复习编译原理，准备期末考试" /></Field>
        <p className="muted">知识点数量由所选资料内容决定，按章节组织；每次学到哪里，就从哪里继续。</p>
        <fieldset className="source-selection"><legend>选择资料及用途</legend><p>使用已处理完成的资料，无需重复上传。课件用于生成大纲，习题用于标注重点。</p>
          {documentError ? <Feedback error>{documentError}<button type="button" className="text-button" onClick={() => void newOutline()}>重新读取资料</button></Feedback> : !loading && documents.length === 0 ? <Feedback>资料库为空，请先在资料页上传课件。</Feedback> : documents.map(doc => <fieldset key={doc.id} className="source-row" disabled={busy || doc.pipelineStatus !== 'INDEXED'}>
            <legend>{doc.title}</legend><div className="source-roles">{(['lesson', 'exercise', 'unused'] as Role[]).map(role => <label key={role}><input type="radio" name={`role-${doc.id}`} checked={(roles[doc.id] || 'unused') === role} onChange={() => setRoles(r => ({ ...r, [doc.id]: role }))} />{role === 'lesson' ? '课件' : role === 'exercise' ? '习题参考' : '不使用'}</label>)}{doc.pipelineStatus !== 'INDEXED' && <small>尚未处理完成</small>}</div>
          </fieldset>)}
        </fieldset>
        <div className="action-row"><button disabled={busy || loading || !!documentError || !goal.trim() || !lessons.length} type="submit">{busy ? '正在生成大纲…' : '生成学习大纲'}</button><small>已选 {lessons.length} 份课件 · {exercises.length} 份习题</small></div>
      </form>}
      {plan && <div className="plan-preview" aria-busy={generating}>
        <h2>{plan.learningGoal}</h2>
        {plan.result && <div className="outline-progress"><strong>已完成 {completed} / {tasks.length} 个知识点</strong><progress aria-label="大纲完成进度" value={completed} max={tasks.length || 1} /><p>完成代表已走完讲解、练习与卡片确认流程。</p></div>}
        <div className="action-row">{plan.status === 'SUCCEEDED' ? <button disabled={busy} type="button" onClick={() => void action(async () => {
          const value = await learningApi.planSession(plan.id)
          if (mounted.current) { setSession(value); setPlan(current => current && { ...current, sessionId: value.id }) }
          await onSession(value)
        })}>{session ? session.status === 'COMPLETED' ? '查看学习记录' : '继续学习' : '开始学习'}</button> : <button disabled={generating} type="button" onClick={() => void action(() => execute(plan.id))}>{generating ? '正在生成大纲…' : '继续生成大纲'}</button>}</div>
        {plan.errorMessage && <Feedback error>{plan.errorMessage}</Feedback>}
        <details open={!plan.result}><summary>大纲生成进度 · {planStatus(plan.status)}</summary><ol className="planning-stages">{plan.stages.map(stage => <li key={stage.id}><span className={stage.status === 'FAILED' ? 'field-error' : ''}>{stage.status === 'SUCCEEDED' ? '✓' : stage.status === 'FAILED' ? '!' : '·'} {phaseLabel(stage.stage)}</span><small>{stage.status === 'SUCCEEDED' ? '已保存' : stage.status === 'FAILED' ? '未完成' : '处理中'}</small>{stage.errorMessage && <p className="field-error">{stage.errorMessage}</p>}</li>)}</ol></details>
        {Array.from(new Set(tasks.map(t => t.chapterId))).map(chapter => <section className="outline-chapter" key={chapter}><h3>{tasks.find(t => t.chapterId === chapter)?.chapterTitle}</h3><ol className="preview-tasks">
          {tasks.filter(t => t.chapterId === chapter).map(task => {
            const status = session?.plan.find(p => p.id === task.knowledgePointId)?.status || 'NEW'
            return <li key={task.knowledgePointId}><div className="task-title"><strong>{task.topic}</strong><span className={`priority priority-${task.priority.toLowerCase()}`}>{priorityLabel(task.priority)}</span><span className={`point-progress ${status === 'COMPLETED' ? 'done' : ''}`}>{status === 'COMPLETED' ? '✓ 已完成' : status === 'NEW' ? '未开始' : '学习中'}</span></div>
              {task.subtopics.length > 0 && <ul className="outline-subtopics">{task.subtopics.map((topic, i) => <li key={i}>{topic}</li>)}</ul>}<p>{task.reason} · 约 {task.estimatedMinutes} 分钟</p>
              <div className="source-links">{task.sourceChunkIds.map((id, i) => <SourceLink key={id} chunkId={id} label={`课件依据 ${i + 1}`} />)}</div>
              {plan.result?.emphasis.matches.filter(m => m.knowledgePointId === task.knowledgePointId).map((match, i) => <details key={i}><summary>重点依据</summary><p>习题：“{match.quote}”</p><p>课件：“{match.lessonQuote}”</p><p>{match.reason}</p></details>)}
            </li>
          })}</ol></section>)}
        {!!plan.result?.emphasis.unmatched.length && <details><summary>{plan.result.emphasis.unmatched.length} 条习题内容未匹配到课件</summary>{plan.result.emphasis.unmatched.map((item, i) => <p key={i}>{item.quote} — {item.reason}</p>)}</details>}
      </div>}
      {!plan && session && <><h2>{session.learningGoal}</h2><p>此会话的大纲保存在学习记录中。</p><button type="button" onClick={() => void onSession(session)}>继续学习</button><LearningPlan points={session.plan} activeKnowledgePointId={session.activeKnowledgePoint?.id || null} /></>}
    </div></SourceProvider>
    </section>
  </div>
}
