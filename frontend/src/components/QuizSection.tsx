import { type FormEvent, useEffect, useState } from 'react'
import type { Quiz } from '../learningTypes'

interface Props {
  busy: boolean
  quiz: Quiz
  onSubmit: (answers: string[]) => Promise<void>
}

export function QuizSection({ busy, quiz, onSubmit }: Props) {
  const [answers, setAnswers] = useState<Record<number, string>>({})

  useEffect(() => setAnswers({}), [quiz.quizId])

  const submitted = quiz.score !== null
  const complete = quiz.questions.length === 5
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
          <span className="eyebrow">五题测验</span>
          <h2>{submitted ? `得分 ${quiz.score} / 100` : '检验刚刚学到的内容'}</h2>
        </div>
      </div>
      <form onSubmit={submit}>
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
                      disabled={busy || submitted}
                      name={`question-${question.questionIndex}`}
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
              {feedback && (
                <div className={feedback.correct ? 'quiz-feedback correct' : 'quiz-feedback incorrect'}>
                  <strong>{feedback.correct ? '回答正确' : `正确答案：${feedback.correctAnswer}`}</strong>
                  <p>{feedback.explanation}</p>
                </div>
              )}
            </fieldset>
          )
        })}
        {!submitted && (
          <button disabled={busy || !complete} type="submit">
            {busy ? '正在评分…' : complete ? '提交五题答案' : '请完成全部五题'}
          </button>
        )}
      </form>
    </section>
  )
}
