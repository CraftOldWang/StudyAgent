import { type FormEvent, useMemo, useState } from 'react'
import { learningApi } from '../learningApi'
import { sessionStatusLabel } from '../learningStatus'
import type { LearningSession, ReviewCard } from '../learningTypes'
import type { KnowledgeBase } from '../types'
import { LearningPlan } from './LearningPlan'
import { QuizSection } from './QuizSection'
import { ReviewCards } from './ReviewCards'
interface Props {
  knowledgeBase: KnowledgeBase
  onSessionKnowledgeBase: (knowledgeBaseId: string) => void
}
type Action = 'create' | 'restore' | 'explain' | 'message' | 'quiz' | 'submit' | 'cards'
export function LearningPanel({ knowledgeBase, onSessionKnowledgeBase }: Props) {
  const [session, setSession] = useState<LearningSession | null>(null)
  const [learningGoal, setLearningGoal] = useState('')
  const [restoreId, setRestoreId] = useState('')
  const [message, setMessage] = useState('')
  const [latestAnswer, setLatestAnswer] = useState('')
  const [shownCards, setShownCards] = useState<ReviewCard[]>([])
  const [busy, setBusy] = useState<Action | null>(null)
  const [error, setError] = useState('')

  const activePoint = session?.activeKnowledgePoint ?? null
  const currentQuiz = session?.currentQuiz ?? null
  const displayCards = shownCards.length > 0 ? shownCards : session?.cards ?? []
  const canMessage = activePoint?.status === 'EXPLAINING' || activePoint?.status === 'QUIZZING'
  const validRestoreId = useMemo(() => /^[1-9]\d*$/.test(restoreId), [restoreId])

  function fail(caught: unknown) {
    setError(caught instanceof Error ? caught.message : '学习流程发生未知错误。')
  }

  async function createSession(event: FormEvent) {
    event.preventDefault()
    const goal = learningGoal.trim()
    if (!goal) return
    setBusy('create')
    setError('')
    try {
      const result = await learningApi.createSession(knowledgeBase.id, goal)
      setSession(result.session)
      setRestoreId(String(result.session.id))
      setLatestAnswer('')
      setShownCards(result.session.cards)
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function restoreSession(event: FormEvent) {
    event.preventDefault()
    if (!validRestoreId) return
    setBusy('restore')
    setError('')
    try {
      const restored = await learningApi.getSession(restoreId)
      setSession(restored)
      setLearningGoal(restored.learningGoal)
      setLatestAnswer('')
      setShownCards(restored.cards)
      onSessionKnowledgeBase(restored.knowledgeBaseId)
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function explain() {
    if (!session) return
    setBusy('explain')
    setError('')
    try {
      const result = await learningApi.explain(session.id)
      setSession(result.session)
      setLatestAnswer(result.answer)
      setShownCards(result.session.cards)
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault()
    if (!session || !message.trim()) return
    setBusy('message')
    setError('')
    try {
      const result = await learningApi.sendMessage(session.id, message.trim())
      setSession(result.session)
      setLatestAnswer(result.answer)
      setMessage('')
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function generateQuiz() {
    if (!session) return
    setBusy('quiz')
    setError('')
    try {
      const result = await learningApi.generateQuiz(session.id)
      setSession(result.session)
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function submitQuiz(answers: string[]) {
    if (!session) return
    setBusy('submit')
    setError('')
    try {
      const result = await learningApi.submitQuiz(session.id, answers)
      setSession(result.session)
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  async function generateCards() {
    if (!session) return
    setBusy('cards')
    setError('')
    try {
      const result = await learningApi.generateCards(session.id)
      setSession(result.session)
      setShownCards(result.cards)
      setLatestAnswer('')
    } catch (caught) {
      fail(caught)
    } finally {
      setBusy(null)
    }
  }

  function startAnotherSession() {
    setSession(null)
    setLearningGoal('')
    setRestoreId('')
    setMessage('')
    setLatestAnswer('')
    setShownCards([])
    setError('')
  }

  if (!session) {
    return (
      <section className="panel learning-start">
        <div className="panel-header">
          <div>
            <span className="eyebrow">学习闭环</span>
            <h1>把资料变成一次完整学习</h1>
            <p>当前知识库：{knowledgeBase.name}</p>
          </div>
        </div>
        {error && <p className="inline-error" role="alert">{error}</p>}
        <div className="learning-start-grid">
          <form onSubmit={createSession}>
            <h2>开始新学习</h2>
            <label htmlFor="learning-goal">这次想学会什么？</label>
            <textarea
              id="learning-goal"
              onChange={(event) => setLearningGoal(event.target.value)}
              placeholder="例如：理解 Java happens-before 规则并能判断线程安全问题"
              rows={4}
              value={learningGoal}
            />
            <button disabled={busy !== null || !learningGoal.trim()} type="submit">
              {busy === 'create' ? '正在生成计划…' : '生成学习计划'}
            </button>
          </form>
          <form onSubmit={restoreSession}>
            <h2>恢复学习会话</h2>
            <label htmlFor="learning-session-id">学习会话 ID</label>
            <input
              id="learning-session-id"
              inputMode="numeric"
              onChange={(event) => setRestoreId(event.target.value)}
              placeholder="输入后端返回的会话 ID"
              value={restoreId}
            />
            <button
              disabled={busy !== null || !validRestoreId}
              type="submit"
            >
              {busy === 'restore' ? '正在恢复…' : '恢复会话'}
            </button>
          </form>
        </div>
      </section>
    )
  }

  return (
    <div className="learning-workspace">
      <section className="panel learning-summary">
        <div>
          <span className="eyebrow">学习会话 #{session.id}</span>
          <h1>{session.learningGoal}</h1>
          <p>绑定知识库 #{session.knowledgeBaseId}</p>
        </div>
        <div className="learning-summary-actions">
          <span className={`session-status session-${session.status.toLowerCase()}`}>
            {sessionStatusLabel(session.status)}
          </span>
          <button className="secondary" disabled={busy !== null} onClick={startAnotherSession} type="button">
            新建或恢复其他会话
          </button>
        </div>
      </section>

      {(error || session.errorMessage) && (
        <p className="inline-error" role="alert">{error || session.errorMessage}</p>
      )}

      <div className="learning-columns">
        <LearningPlan
          activeKnowledgePointId={activePoint?.id ?? null}
          points={session.plan}
        />

        <section className="learning-focus">
          {session.status === 'COMPLETED' ? (
            <div className="completion-card">
              <span>✓</span>
              <h2>本次学习已完成</h2>
              <p>讲解、测验与复习卡片都已保存，可使用会话 ID 恢复。</p>
            </div>
          ) : activePoint ? (
            <>
              <div className="focus-heading">
                <span className="eyebrow">当前知识点 · {activePoint.sequenceNo}</span>
                <h2>{activePoint.topic}</h2>
                <div className="topic-chips">
                  {activePoint.subtopics.map((subtopic) => <span key={subtopic}>{subtopic}</span>)}
                </div>
              </div>

              {activePoint.explanation && (
                <article className="explanation"><h3>知识点讲解</h3><p>{activePoint.explanation}</p></article>
              )}
              {latestAnswer && (
                <article className="agent-message"><h3>Agent 回答</h3><p>{latestAnswer}</p></article>
              )}
              {activePoint.status === 'NEW' && (
                <button disabled={busy !== null} onClick={explain} type="button">
                  {busy === 'explain' ? '正在生成讲解…' : '开始这个知识点'}
                </button>
              )}
              {canMessage && (
                <form className="learning-message-form" onSubmit={sendMessage}>
                  <label htmlFor="learning-message">针对当前知识点继续提问</label>
                  <div>
                    <input
                      id="learning-message"
                      onChange={(event) => setMessage(event.target.value)}
                      placeholder="输入你的问题"
                      value={message}
                    />
                    <button disabled={busy !== null || !message.trim()} type="submit">
                      {busy === 'message' ? '回答中…' : '提问'}
                    </button>
                  </div>
                </form>
              )}
              {activePoint.status === 'EXPLAINING' && !currentQuiz && (
                <button disabled={busy !== null} onClick={generateQuiz} type="button">
                  {busy === 'quiz' ? '正在生成五题…' : '进入五题测验'}
                </button>
              )}
            </>
          ) : null}

          {currentQuiz && (
            <QuizSection busy={busy !== null} onSubmit={submitQuiz} quiz={currentQuiz} />
          )}
          {currentQuiz && currentQuiz.score !== null && activePoint?.status === 'CARD_GENERATING' && (
            <button disabled={busy !== null} onClick={generateCards} type="button">
              {busy === 'cards' ? '正在生成三张卡片…' : '生成三张复习卡片并完成'}
            </button>
          )}
          <ReviewCards cards={displayCards} />
        </section>
      </div>
    </div>
  )
}
