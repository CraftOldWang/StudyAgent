import type { Quiz, QuizFeedback, ReviewCard } from '../learningTypes'
import { QuizSection } from './QuizSection'
import { ReviewCards } from './ReviewCards'
import { Feedback } from './ui/Feedback'

type Artifact = { type: 'QUIZ'; quizId: string; knowledgePointId: string; questions: Quiz['questions'] }
  | { type: 'GRADE'; quizId: string; score: number; feedback: QuizFeedback[] }
  | { type: 'CARDS'; cards: ReviewCard[] }
  | { type: 'QUESTION' | 'EXPLANATION' }

export function SavedArtifacts({ json, currentQuizId, hideCards = false }: { json: string | null; currentQuizId?: string; hideCards?: boolean }) {
  if (!json) return null
  let artifact: Artifact
  try { artifact = JSON.parse(json) as Artifact }
  catch { return <Feedback error>此回合的产物记录无法读取，请保留会话编号并检查服务端记录。</Feedback> }
  if (artifact.type === 'QUIZ' && artifact.quizId !== currentQuizId) {
    return <QuizSection readOnly busy={false} quiz={{ ...artifact, score: null, feedback: null }} onSubmit={async () => undefined} />
  }
  if (artifact.type === 'GRADE' && artifact.quizId !== currentQuizId) {
    return <section className="saved-grade"><h3>本次测验：{artifact.score} / 100</h3>{artifact.feedback.map(item => <div className={`quiz-feedback ${item.correct ? 'correct' : 'incorrect'}`} key={item.questionIndex}>
      <strong>第 {item.questionIndex + 1} 题 · {item.correct ? '回答正确' : `正确答案：${item.correctAnswer}`}</strong><p>{item.explanation}</p>
    </div>)}</section>
  }
  if (artifact.type === 'CARDS') return hideCards ? null : <details><summary>当时的卡片草稿</summary><ReviewCards cards={artifact.cards} draft /></details>
  return null
}
