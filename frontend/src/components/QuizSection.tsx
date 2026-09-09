import { type FormEvent, useEffect, useState } from 'react'
import type { Quiz } from '../learningTypes'
import { SourceLink } from './SourceDrawer'

interface Props {
  busy: boolean
  quiz: Quiz
  onSubmit: (answers: string[]) => Promise<void>
  readOnly?: boolean
}

export function QuizSection({ busy, quiz, onSubmit, readOnly = false }: Props) {
  const [answers, setAnswers] = useState<Record<number, string>>({})

  useEffect(() => setAnswers({}), [quiz.quizId])

  const submitted = quiz.score !== null
  const complete = quiz.questions.length >= 1 && quiz.questions.length <= 10
    && quiz.questions.every((question) => Boolean(answers[question.questionIndex]))

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!complete) return
    await onSubmit(quiz.questions.map((question) => answers[question.questionIndex]))
  }

  return (
    <section className="quiz-block">
      <div className="learning-section-heading">
        <div>
          <span className="eyebrow">{quiz.questions.length} 道选择题</span>
          <h2>{submitted ? `得分 ${quiz.score} / 100` : readOnly ? '已保存测验' : '检验刚刚学到的内容'}</h2>
        </div>
      </div>
      <form noValidate onSubmit={submit}>
        {quiz.questions.map((question, position) => {
          const feedback = quiz.feedback?.find((item) => item.questionIndex === question.questionIndex)
          return (
            <fieldset key={question.questionIndex}>
              <legend>{position + 1}. {question.question}</legend>
              <div className="quiz-options">
                {question.options.map((option) => (
                  <label key={option}>
                    <input
                      checked={answers[question.questionIndex] === option}
                      disabled={busy || submitted || readOnly}
                      name={`quiz-${quiz.quizId}-question-${question.questionIndex}`}
                      onChange={() => setAnswers((current) => ({
                        ...current,
                        [question.questionIndex]: option,
                      }))}
                      type="radio"
                      value={option}
                    />
                    <span>{option}</span>
                  </label>
                ))}
              </div>
              <SourceLink chunkId={question.sourceChunkId} />
              {feedback && (
                <div className={feedback.correct ? 'quiz-feedback correct' : 'quiz-feedback incorrect'}>
                  <strong>{feedback.correct ? '回答正确' : `正确答案：${feedback.correctAnswer}`}</strong>
                  <p>{feedback.explanation}</p>
                </div>
              )}
            </fieldset>
          )
        })}
        {!submitted && !readOnly && (
          <button disabled={busy || !complete} type="submit">
            {busy ? '正在评分…' : complete ? '提交答案' : '请完成全部题目'}
          </button>
        )}
      </form>
    </section>
  )
}
