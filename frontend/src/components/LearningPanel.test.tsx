import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearningSession } from '../learningTypes'

const learningApiMock = vi.hoisted(() => ({
  createSession: vi.fn(),
  getSession: vi.fn(),
  explain: vi.fn(),
  sendMessage: vi.fn(),
  generateQuiz: vi.fn(),
  submitQuiz: vi.fn(),
  generateCards: vi.fn(),
}))

vi.mock('../learningApi', () => ({ learningApi: learningApiMock }))

import { LearningPanel } from './LearningPanel'

const point = {
  id: '9007199254741001',
  sequenceNo: 1,
  topic: 'JMM 可见性',
  subtopics: ['happens-before'],
  estimatedMinutes: 20,
  status: 'NEW' as const,
  explanation: null,
  errorMessage: null,
}

const newSession: LearningSession = {
  id: '9007199254740999',
  learningGoal: '理解 Java 内存模型',
  knowledgeBaseId: '9007199254740993',
  status: 'ACTIVE',
  errorMessage: null,
  activeKnowledgePoint: point,
  plan: [point],
  currentQuiz: null,
  cards: [],
}

describe('LearningPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a scoped plan and advances the active point to its real explanation', async () => {
    learningApiMock.createSession.mockResolvedValue({ traceId: 'trace-create', session: newSession })
    learningApiMock.explain.mockResolvedValue({
      traceId: 'trace-explain',
      answer: '同步关系建立跨线程可见性。',
      session: {
        ...newSession,
        activeKnowledgePoint: { ...point, status: 'EXPLAINING', explanation: '这是持久化后的讲解。' },
        plan: [{ ...point, status: 'EXPLAINING', explanation: '这是持久化后的讲解。' }],
      },
    })
    render(
      <LearningPanel
        knowledgeBase={{ id: '9007199254740993', name: 'JMM 资料', createdAt: '', updatedAt: '' }}
        onSessionKnowledgeBase={vi.fn()}
      />,
    )

    fireEvent.change(screen.getByLabelText('这次想学会什么？'), {
      target: { value: '  理解 Java 内存模型  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: '生成学习计划' }))
    await waitFor(() => expect(learningApiMock.createSession).toHaveBeenCalledWith(
      '9007199254740993', '理解 Java 内存模型'))
    expect((await screen.findAllByText('JMM 可见性')).length).toBe(2)
    expect(screen.getByText('学习会话 #9007199254740999')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '开始这个知识点' }))
    await waitFor(() => expect(learningApiMock.explain).toHaveBeenCalledWith('9007199254740999'))
    expect(await screen.findByText('这是持久化后的讲解。')).toBeInTheDocument()
    expect(screen.getByText('同步关系建立跨线程可见性。')).toBeInTheDocument()
  })
})
