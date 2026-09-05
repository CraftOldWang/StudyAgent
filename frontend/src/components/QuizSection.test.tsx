import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { QuizSection } from './QuizSection'

describe('QuizSection', () => {
  it('requires all five questions and submits answers in backend question order', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(
      <QuizSection
        busy={false}
        onSubmit={onSubmit}
        quiz={{
          quizId: '9007199254740999',
          knowledgePointId: 'point-1',
          score: null,
          feedback: null,
          questions: Array.from({ length: 5 }, (_, index) => ({
            questionIndex: index,
            question: `问题 ${index + 1}`,
            options: [`选项 ${index + 1}A`, `选项 ${index + 1}B`],
            sourceChunkId: null,
          })),
        }}
      />,
    )

    const submit = screen.getByRole('button', { name: '请完成全部五题' })
    expect(submit).toBeDisabled()
    for (let index = 1; index <= 5; index += 1) {
      fireEvent.click(screen.getByLabelText(`选项 ${index}B`))
    }
    fireEvent.click(screen.getByRole('button', { name: '提交五题答案' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith([
      '选项 1B', '选项 2B', '选项 3B', '选项 4B', '选项 5B',
    ]))
  })

  it('displays the backend percentage score on a 100-point scale', () => {
    render(
      <QuizSection
        busy={false}
        onSubmit={vi.fn()}
        quiz={{
          quizId: 'quiz-2',
          knowledgePointId: 'point-2',
          score: 80,
          feedback: [],
          questions: [],
        }}
      />,
    )

    expect(screen.getByRole('heading', { name: '得分 80 / 100' })).toBeInTheDocument()
  })
})
