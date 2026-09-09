import { type FormEvent, useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { learningApi } from '../learningApi'
import type { LearningSession, PlanningView } from '../learningTypes'
import type { DocumentItem, KnowledgeBase } from '../types'
import { Feedback } from './ui/Feedback'
import { Field, MultilineInput } from './ui/Field'
import { SourceLink, SourceProvider } from './SourceDrawer'

type Role = 'lesson' | 'exercise' | 'unused'
interface Draft { roles: Record<string, Role>; goal: string; count: string; runId: string; plan: PlanningView | null }
// Keep drafts across in-app navigation without storing course text in browser storage.
const drafts = new Map<string, Draft>()
const phaseLabel = (value: string) => ({ EXTRACT: '读取课件', OUTLINE: '整理大纲', EMPHASIS: '标注习题重点', EMPHASIS_REVIEW: '核对重点依据', TASKS: '安排学习任务' }[value.split('/')[0]] || value)
export const priorityLabel = (value?: string | null) => value === 'HIGH' ? '重点' : value === 'MEDIUM' ? '关注' : '基础'

export function PlanningStart({ knowledgeBase, onSession }: {
  knowledgeBase: KnowledgeBase; onSession: (session: LearningSession) => Promise<void>
}) {
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [roles, setRoles] = useState<Record<string, Role>>(() => drafts.get(knowledgeBase.id)?.roles || {})
  const [goal, setGoal] = useState(() => drafts.get(knowledgeBase.id)?.goal || '')
  const [count, setCount] = useState(() => drafts.get(knowledgeBase.id)?.count || '5')
  const [runId, setRunId] = useState(() => drafts.get(knowledgeBase.id)?.runId || '')
  const [plan, setPlan] = useState<PlanningView | null>(() => drafts.get(knowledgeBase.id)?.plan || null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [documentError, setDocumentError] = useState('')
  const [loadVersion, setLoadVersion] = useState(0)
  const [error, setError] = useState('')
  const mounted = useRef(true)
  const polling = useRef<ReturnType<typeof setInterval>>()
  const currentRun = useRef('')
  const lock = useRef(false)
  const previewHeading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { if (plan?.result) previewHeading.current?.focus() }, [plan?.id, plan?.status])
  useEffect(() => { drafts.set(knowledgeBase.id, { roles, goal, count, runId, plan }) }, [knowledgeBase.id, roles, goal, count, runId, plan])
  useEffect(() => {
    mounted.current = true
    let active = true
    setLoading(true); setDocumentError('')
    api.listDocuments(knowledgeBase.id).then(items => {
      if (active) setDocuments(items)
    }).catch(e => { if (active) setDocumentError(String(e.message || e)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [knowledgeBase.id, loadVersion])
  useEffect(() => () => { mounted.current = false; clearInterval(polling.current) }, [])
  const lessons = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'lesson').map(d => d.id)
  const exercises = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'exercise').map(d => d.id)
  const validCount = /^\d+$/.test(count) && +count >= 1 && +count <= 30
  async function execute(id: string) {
    currentRun.current = id
    let fetching = false
    polling.current = setInterval(async () => {
      if (fetching) return
      fetching = true
      try {
        const next = await learningApi.getPlan(id)
        if (mounted.current && currentRun.current === id) setPlan(next)
      } catch (e) {
        if (mounted.current) setError(`进度查询失败：${e instanceof Error ? e.message : String(e)}`)
      } finally { fetching = false }
    }, 2500)
    try {
      const next = await learningApi.executePlan(id)
      const cached = drafts.get(knowledgeBase.id)
      if (cached?.runId === id) drafts.set(knowledgeBase.id, { ...cached, plan: next })
      if (mounted.current) setPlan(next)
    } finally { currentRun.current = ''; clearInterval(polling.current) }
  }
  async function action(work: () => Promise<void>) {
    if (lock.current) return
    lock.current = true
    setBusy(true); setError('')
    try { await work() }
    catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function generate(event: FormEvent) {
    event.preventDefault()
    if (!goal.trim() || !validCount || !lessons.length) return
    await action(async () => {
      const created = await learningApi.createPlan(knowledgeBase.id, goal.trim(), lessons, exercises, +count)
      drafts.set(knowledgeBase.id, { roles, goal, count, runId: created.id, plan: created })
      if (!mounted.current) return
      setPlan(created); setRunId(created.id)
      await execute(created.id)
    })
  }
  return <SourceProvider knowledgeBaseId={plan?.knowledgeBaseId || knowledgeBase.id}><section className="planning-start">
    <div className="section-intro"><span className="eyebrow">01 / 准备学习</span><h2>先梳理大纲，再分配重点</h2>
      <p>用课件建立完整路线，参考往年习题安排投入。重点会保留资料依据，基础知识点仍会保留。</p></div>
    {error && <Feedback error>{error}</Feedback>}
    {!plan && <form noValidate onSubmit={generate} className="plan-form">
      <Field id="learning-goal" label="这次想学会什么？">
        <MultilineInput id="learning-goal" rows={3} value={goal} maxLength={3000} onChange={e => setGoal(e.target.value)} placeholder="例如：期末复习编译原理，重点理解词法分析与语法分析" />
      </Field>
      <Field id="point-count" label="本次知识点数" hint="1–30 个，按课件顺序安排；时间为计划估算。" error={validCount ? undefined : '请输入 1–30 的整数。'}>
        <input id="point-count" className="short-input" inputMode="numeric" value={count} onChange={e => setCount(e.target.value)} aria-invalid={!validCount} aria-describedby={`point-count-hint${validCount ? '' : ' point-count-error'}`} />
      </Field>
      <fieldset className="source-selection"><legend>选择资料及用途</legend><p>每份资料只选择一种用途。只有处理完成的资料可以加入计划。</p>
        {loading ? <Feedback>正在读取资料…</Feedback> : documentError ? <Feedback error>{documentError}<button type="button" className="text-button" onClick={() => setLoadVersion(v => v + 1)}>重新读取资料</button></Feedback> : documents.length === 0 ? <Feedback>资料库为空，请先在资料页上传课件。</Feedback> :
          documents.map(doc => <fieldset key={doc.id} className="source-row" disabled={busy || doc.pipelineStatus !== 'INDEXED'}>
            <legend>{doc.title}</legend><div className="source-roles">
              {(['lesson', 'exercise', 'unused'] as Role[]).map(role => <label key={role}><input type="radio" name={`role-${doc.id}`} checked={(roles[doc.id] || 'unused') === role} onChange={() => setRoles(r => ({ ...r, [doc.id]: role }))} />
                {role === 'lesson' ? '课件' : role === 'exercise' ? '习题参考' : '不使用'}</label>)}
              {doc.pipelineStatus !== 'INDEXED' && <small>尚未处理完成</small>}
            </div></fieldset>)}
      </fieldset>
      <div className="action-row"><button disabled={busy || loading || !!documentError || !goal.trim() || !validCount || !lessons.length} type="submit">{busy ? '正在整理计划…' : '生成重点学习计划'}</button>
        <small>已选 {lessons.length} 份课件 · {exercises.length} 份习题</small></div>
    </form>}
    {plan && <div className="plan-preview" aria-busy={busy}>
      <h3 tabIndex={-1} ref={previewHeading}>{plan.learningGoal}</h3><p className="resource-reference">计划编号：{plan.id} · 保存该编号可恢复</p>
      <ol className="planning-stages">{plan.stages.map(stage => <li key={stage.id}>
        <span className={stage.status === 'FAILED' ? 'field-error' : ''}>{stage.status === 'SUCCEEDED' ? '✓' : stage.status === 'FAILED' ? '!' : '·'} {phaseLabel(stage.stage)}</span>
        <small>{stage.status === 'SUCCEEDED' ? '已保存' : stage.status === 'FAILED' ? '未完成' : '处理中'} · 第 {stage.attemptCount} 次</small>
        {stage.errorMessage && <p className="field-error">{stage.errorMessage}</p>}
      </li>)}</ol>
      {busy && <Feedback>正在整理资料。已完成阶段会保存，可用上方编号查询进度。</Feedback>}
      {plan.errorMessage && <Feedback error>{plan.errorMessage}</Feedback>}
      {plan.result && <><ol className="preview-tasks">{plan.result.tasks.map(task => <li key={task.knowledgePointId}>
        <small>{task.chapterTitle}</small><div className="task-title"><strong>{task.topic}</strong><span className={`priority priority-${task.priority.toLowerCase()}`}>{priorityLabel(task.priority)}</span></div>
        <p>{task.reason} · 约 {task.estimatedMinutes} 分钟</p>
        <div className="source-links">{task.sourceChunkIds.map((id, i) => <SourceLink key={id} chunkId={id} label={`课件依据 ${i + 1}`} />)}</div>
        {plan.result?.emphasis.matches.filter(m => m.knowledgePointId === task.knowledgePointId).map((match, i) => <details key={i}><summary>重点依据</summary>
          <p>习题：“{match.quote}”</p><p>课件：“{match.lessonQuote}”</p><p>{match.reason}</p></details>)}
      </li>)}</ol>
      {plan.result.emphasis.unmatched.length > 0 && <details><summary>{plan.result.emphasis.unmatched.length} 条习题内容未匹配到课件</summary>{plan.result.emphasis.unmatched.map((item, i) => <p key={i}>{item.quote} — {item.reason}</p>)}</details>}</>}
      <div className="action-row">
        {plan.status === 'SUCCEEDED' ? <button disabled={busy} type="button" onClick={() => void action(async () => onSession(await learningApi.planSession(plan.id)))}>按此计划开始学习</button> :
          <button disabled={busy} type="button" onClick={() => void action(() => execute(plan.id))}>继续生成此计划</button>}
        <button className="secondary" disabled={busy} type="button" onClick={() => setPlan(null)}>返回资料选择</button>
      </div>
    </div>}
    <details className="restore-planning"><summary>用计划编号查询或恢复</summary><form noValidate className="inline-form" onSubmit={e => { e.preventDefault(); void action(async () => setPlan(await learningApi.getPlan(runId))) }}>
      <label className="visually-hidden" htmlFor="plan-id">计划编号</label><input id="plan-id" value={runId} inputMode="numeric" onChange={e => setRunId(e.target.value.trim())} />
      <button className="secondary" disabled={busy || !/^[1-9]\d*$/.test(runId)} type="submit">查询计划</button></form></details>
  </section></SourceProvider>
}
