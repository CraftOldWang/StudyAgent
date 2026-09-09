import { type FormEvent, useEffect, useRef, useState } from 'react'
import { learningApi } from '../learningApi'
import type { ConversationTurn, LearningSession, LearningTurn } from '../learningTypes'
import type { KnowledgeBase } from '../types'
import { LearningPlan } from './LearningPlan'
import { PlanningStart } from './PlanningStart'
import { QuizSection } from './QuizSection'
import { ReviewCards } from './ReviewCards'
import { Feedback } from './ui/Feedback'
import { Field, MultilineInput } from './ui/Field'
import { SavedArtifacts } from './SavedArtifacts'
import { MessageContent } from './ui/MessageContent'
import { SourceProvider } from './SourceDrawer'

interface Props { knowledgeBase: KnowledgeBase; onSessionKnowledgeBase: (id: string) => void }
interface Pending { requestId: string; message: string }
export function LearningPanel({ knowledgeBase, onSessionKnowledgeBase }: Props) {
  const [session, setSession] = useState<LearningSession | null>(null)
  const [history, setHistory] = useState<ConversationTurn[]>([])
  const [restoreId, setRestoreId] = useState('')
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState<Pending | null>(null)
  const [partial, setPartial] = useState('')
  const [progress, setProgress] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  const mounted = useRef(true)
  const transport = useRef<AbortController>()
  const sessionId = useRef('')
  const end = useRef<HTMLDivElement>(null)
  const follow = useRef(true)
  const draft = useRef(new Map<string, string>())
  const pendingBySession = useRef(new Map<string, Pending>())
  useEffect(() => {
    mounted.current = true
    const onScroll = () => { follow.current = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 140 }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => { mounted.current = false; transport.current?.abort(); window.removeEventListener('scroll', onScroll) }
  }, [])
  useEffect(() => { if (follow.current && (partial || history.length)) end.current?.scrollIntoView({ block: 'nearest' }) }, [partial, history.length])

  function clearSentDraft(id: string, sent: string) {
    setMessage(current => current.trim() === sent ? '' : current)
    if (draft.current.get(id)?.trim() === sent) draft.current.delete(id)
  }

  async function loadSession(value: LearningSession) {
    const turns = await learningApi.history(value.id)
    if (!mounted.current) return
    sessionId.current = value.id
    setMessage(draft.current.get(value.id) || '')
    const savedPending = pendingBySession.current.get(value.id)
    const alreadySaved = turns.some(t => t.requestId === savedPending?.requestId && t.status === 'SUCCEEDED')
    if (alreadySaved) pendingBySession.current.delete(value.id)
    setSession(value); setHistory(turns); setRestoreId(value.id); setPartial(''); setPending(alreadySaved ? null : savedPending || null)
    onSessionKnowledgeBase(value.knowledgeBaseId)
  }
  async function restore(event: FormEvent) {
    event.preventDefault()
    if (lock.current || !/^[1-9]\d*$/.test(restoreId)) return
    lock.current = true; setBusy(true); setError('')
    try { await loadSession(await learningApi.getSession(restoreId)) }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function refresh() {
    if (!session || lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      const [value, turns] = await Promise.all([learningApi.getSession(session.id), learningApi.history(session.id)])
      if (!mounted.current || sessionId.current !== value.id) return
      setSession(value); setHistory(turns)
      if (pending && turns.some(t => t.requestId === pending.requestId && t.status === 'SUCCEEDED')) { setPending(null); pendingBySession.current.delete(session.id); setPartial(''); clearSentDraft(session.id, pending.message) }
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function send(text: string, retry?: Pending) {
    if (!session || lock.current || !text.trim() || (!retry && !canSend)) return
    const request = retry || { message: text.trim(), requestId: crypto.randomUUID() }
    const id = session.id
    pendingBySession.current.set(id, request)
    lock.current = true; setBusy(true); setError(''); setPartial(''); setProgress('正在连接…'); setPending(request)
    transport.current = new AbortController()
    try {
      await learningApi.streamMessage(id, request.message, request.requestId, event => {
        if (!mounted.current || sessionId.current !== id) return
        if (event.event === 'text') setPartial(p => p + (event.data as { text: string }).text)
        else if (event.event === 'progress') setProgress((event.data as { text: string }).text)
        else if (event.event === 'accepted') setProgress('请求已收到，正在处理…')
        else if (event.event === 'failure') throw new Error((event.data as { message: string }).message)
        else if (event.event === 'result') {
          const result = event.data as LearningTurn
          setSession(result.session)
          if (!result.turn) throw new Error('响应缺少回合状态，请查询已保存结果。')
          const turn = result.turn
          setHistory(current => [...current.filter(t => t.requestId !== turn.requestId), turn])
          setPartial('')
          if (turn.status === 'SUCCEEDED') { setPending(null); pendingBySession.current.delete(id); clearSentDraft(id, request.message); setProgress('已保存') }
          else if (turn.status === 'FAILED') {
            setError(turn.errorMessage || '本次未完成，可重试原回合。')
            if (turn.phase === 'MODEL') { setPending(null); pendingBySession.current.delete(id) }
          }
          else setProgress('后台仍在处理，请稍后查询结果。')
        }
      }, transport.current.signal)
    } catch (e) {
      if (mounted.current) setError(e instanceof Error && e.name === 'AbortError' ? '已断开显示，后台处理不会取消。请查询结果。' : e instanceof Error ? e.message : String(e))
    } finally { transport.current = undefined; lock.current = false; if (mounted.current) setBusy(false) }
  }
  const active = session?.activeKnowledgePoint
  const lastTurn = history.at(-1)
  const unresolved = lastTurn?.status !== 'SUCCEEDED' ? lastTurn : undefined
  const retry = pending || (unresolved ? { requestId: unresolved.requestId, message: unresolved.userMessage } : null)
  const blocking = pending || (unresolved && (unresolved.status === 'RUNNING' || unresolved.phase === 'ARTIFACTS_COMMITTED'))
  const canSend = !!active && !busy && !blocking
  const hint = active?.status === 'NEW' ? '开始这个知识点的讲解' : active?.status === 'EXPLAINING' ? '我理解了，开始五题测验' : active?.status === 'CARD_GENERATING' ? '生成三张复习卡片，完成这个知识点' : ''
  if (!session) return <section className="panel learning-start">
    <div className="panel-header"><div><span className="eyebrow">学习工作台</span><h1>{knowledgeBase.name}</h1><p>资料有依据，学习有顺序，复习有记录。</p></div></div>
    {error && <Feedback error>{error}</Feedback>}
    <details className="session-restore" open><summary>继续已有学习</summary><form noValidate onSubmit={restore} className="inline-form">
      <Field id="learning-session-id" label="学习会话编号"><input id="learning-session-id" inputMode="numeric" value={restoreId} onChange={e => setRestoreId(e.target.value.trim())} placeholder="输入之前保存的会话编号" /></Field>
      <button className="secondary" disabled={busy || !/^[1-9]\d*$/.test(restoreId)} type="submit">{busy ? '正在恢复…' : '恢复会话'}</button></form></details>
    <PlanningStart key={knowledgeBase.id} knowledgeBase={knowledgeBase} onSession={loadSession} />
  </section>
  return <SourceProvider knowledgeBaseId={session.knowledgeBaseId}><div className="learning-workspace">
    <section className="panel learning-summary"><div><span className="eyebrow">{session.status === 'COMPLETED' ? '本次学习已完成' : '02 / 学习与练习'}</span><h1>{session.learningGoal}</h1>
      <details className="resource-reference"><summary>会话信息与恢复编号</summary><p>会话：{session.id}<br />绑定资料库：{session.knowledgeBaseId}</p></details></div>
      <div className="action-row"><button className="secondary" disabled={busy} onClick={() => void refresh()} type="button">查询已保存结果</button>
        <button className="secondary" disabled={busy} onClick={() => { sessionId.current = ''; setSession(null); setHistory([]); setMessage(''); setError('') }} type="button">返回学习准备</button></div></section>
    {session.knowledgeBaseId !== knowledgeBase.id && <Feedback>当前会话仍绑定原资料库。<button className="text-button" type="button" onClick={() => onSessionKnowledgeBase(session.knowledgeBaseId)}>切回会话资料库</button></Feedback>}
    {(error || session.errorMessage) && <Feedback error>{error || session.errorMessage}</Feedback>}
    <div className="learning-columns"><LearningPlan activeKnowledgePointId={active?.id || null} points={session.plan} />
      <section className="learning-focus" aria-label="学习对话">
        <div className="focus-heading"><span className="eyebrow">{active ? `当前知识点 · ${active.sequenceNo}` : '学习记录'}</span><h2>{active?.topic || '讲解、测验与卡片已保存'}</h2><p>完成流程不代表已经掌握，之后可用卡片继续复习。</p></div>
        {history.length === 0 && <Feedback>{session.status === 'COMPLETED' ? '本次学习已完成，可以查看保存的卡片与资料来源。' : active?.explanation ? '此会话来自旧版本，早期对话未存为聊天记录；现有讲解与测验仍可查看。' : '发送一条消息开始学习，也可以先提问。'}</Feedback>}
        {history.length === 0 && active?.explanation && <article className="chat-answer"><MessageContent text={active.explanation} /></article>}
        <div className="conversation-history">{history.map(turn => <article className="conversation-turn" key={turn.id}>
          <div className="chat-user"><span>你</span><p>{turn.userMessage}</p></div>
          {turn.assistantMessage && <div className="chat-answer"><span>StudyPilot</span><MessageContent text={turn.assistantMessage} /></div>}
          <SavedArtifacts json={turn.artifactJson} currentQuizId={session.currentQuiz?.quizId} />
          {turn.status !== 'SUCCEEDED' && <small className={turn.status === 'FAILED' ? 'field-error' : 'muted'}>{turn.status === 'FAILED' ? `未完成：${turn.errorMessage || '可重试原回合'}` : '处理中，可查询进度'}</small>}
        </article>)}</div>
        {pending && !history.some(t => t.requestId === pending.requestId) && <div className="chat-user"><span>你 · 待确认</span><p>{pending.message}</p></div>}
        {partial && <div className="chat-answer streaming"><span>StudyPilot · 生成中</span><MessageContent text={partial} /></div>}
        {busy && <Feedback>{progress || '正在处理…'} {transport.current && <button className="text-button" type="button" onClick={() => transport.current?.abort()}>断开显示</button>}</Feedback>}
        {!busy && retry && <div className="recovery-actions"><Feedback>{blocking ? '请先确认上次消息的结果。' : '上次生成未完成，也可以调整消息后继续。'}重试会沿用同一请求编号。</Feedback><button disabled={busy} type="button" onClick={() => void send(retry.message, retry)}>重试原回合</button></div>}
        {session.currentQuiz && <QuizSection busy={!canSend} quiz={session.currentQuiz} onSubmit={async answers => {
          const quiz = session.currentQuiz!
          await send(answers.map((answer, i) => `${i + 1}. ${'ABCD'[quiz.questions[i].options.indexOf(answer)]}`).join('\n'))
        }} />}
        {history.length === 0 && <ReviewCards cards={session.cards} />}
        {active && <form noValidate className="learning-message-form" onSubmit={e => { e.preventDefault(); void send(message) }}>
          <Field id="learning-message" label="继续学习或提问" hint="可自然提问、请求测验或生成卡片。Ctrl + Enter 发送。">
            <MultilineInput id="learning-message" rows={3} value={message} maxLength={12000} onChange={e => { setMessage(e.target.value); draft.current.set(session.id, e.target.value) }} aria-describedby="learning-message-hint" onKeyDown={e => {
              if (e.ctrlKey && e.key === 'Enter' && !e.nativeEvent.isComposing && canSend) { e.preventDefault(); void send(message) }
            }} placeholder="例如：先用一个例子解释，再带我做题" /></Field>
          <div className="action-row"><button disabled={!canSend || !message.trim()} type="submit">{busy ? '处理中…' : '发送消息'}</button>
            {hint && <button className="secondary" disabled={!canSend} onClick={() => void send(hint)} type="button">{active.status === 'NEW' ? '开始讲解' : active.status === 'EXPLAINING' ? '进入测验' : '生成复习卡'}</button>}</div>
        </form>}<div ref={end} />
      </section></div></div></SourceProvider>
}
