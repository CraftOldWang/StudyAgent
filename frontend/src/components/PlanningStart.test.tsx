import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlanningView } from '../learningTypes'
const mock = vi.hoisted(() => ({ createPlan: vi.fn(), executePlan: vi.fn(), getPlan: vi.fn(), planSession: vi.fn(), listPlans: vi.fn(), getSession: vi.fn() }))
vi.mock('../learningApi', () => ({ learningApi: mock }))
vi.mock('../api', () => ({ api: { listDocuments: vi.fn().mockResolvedValue([
  { id: '9007199254740999', title: '课件.pdf', pipelineStatus: 'INDEXED' },
  { id: '9007199254741001', title: '习题.pdf', pipelineStatus: 'INDEXED' },
  { id: '9007199254741003', title: '处理中.pdf', pipelineStatus: 'PARSING' },
]) } }))
import { PlanningStart } from './PlanningStart'
const failed: PlanningView = { id: '9007199254741011', knowledgeBaseId: '7', learningGoal: '考试复习', status: 'FAILED', errorMessage: '重点校验未通过', sessionId: null,
  stages: [{ id: '1', stage: 'OUTLINE', status: 'SUCCEEDED', errorMessage: null, attemptCount: 1 }, { id: '2', stage: 'EMPHASIS', status: 'FAILED', errorMessage: '证据不匹配', attemptCount: 1 }], result: null }
describe('source-grounded planning', () => {
  beforeEach(() => { vi.clearAllMocks(); mock.listPlans.mockResolvedValue([]) })
  it('keeps course/exercise roles separate and resumes the same persisted planning task', async () => {
    mock.createPlan.mockResolvedValue({ ...failed, status: 'PENDING', stages: [], errorMessage: null })
    mock.executePlan.mockResolvedValueOnce(failed).mockResolvedValueOnce({ ...failed, status: 'SUCCEEDED', errorMessage: null, result: { tasks: [], emphasis: { matches: [], unmatched: [] } } })
    render(<PlanningStart visible requestedSessionId={null} knowledgeBase={{ id: '7', name: '课程', createdAt: '', updatedAt: '' }} onSession={vi.fn()} />)
    await waitFor(() => expect(mock.listPlans).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '新建学习大纲' }))
    await screen.findByRole('group', { name: '课件.pdf' })
    fireEvent.change(screen.getByLabelText('课程学习目标'), { target: { value: '考试复习' } })
    fireEvent.click(within(screen.getByRole('group', { name: '课件.pdf' })).getByLabelText('课件'))
    fireEvent.click(within(screen.getByRole('group', { name: '习题.pdf' })).getByLabelText('习题参考'))
    expect(within(screen.getByRole('group', { name: '处理中.pdf' })).getByLabelText('课件')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '生成学习大纲' }))
    expect(await screen.findByText('重点校验未通过')).toBeInTheDocument()
    expect(mock.createPlan).toHaveBeenCalledWith('7', '考试复习', ['9007199254740999'], ['9007199254741001'])
    fireEvent.click(screen.getByRole('button', { name: '继续生成大纲' }))
    await screen.findByRole('button', { name: '开始学习' })
    await waitFor(() => expect(mock.executePlan).toHaveBeenCalledTimes(2))
    expect(mock.executePlan).toHaveBeenNthCalledWith(2, failed.id)
    expect(mock.createPlan).toHaveBeenCalledTimes(1)
  })
  it('opens a saved outline without generating and refreshes its persisted progress on return', async () => {
    const task = { knowledgePointId: '101', chapterId: 'c1', chapterTitle: '第一章', topic: '编译模型', subtopics: ['分析', '综合'], sourceChunkIds: [], priority: 'LOW', estimatedMinutes: 20, reason: '课程基础' }
    const saved = { ...failed, status: 'SUCCEEDED', errorMessage: null, sessionId: '201', stages: [], result: { tasks: [task], emphasis: { matches: [], unmatched: [] } } }
    const session = { id: '201', knowledgeBaseId: '7', learningGoal: saved.learningGoal, status: 'ACTIVE', plan: [{ id: '101', status: 'EXPLAINING' }], activeKnowledgePoint: null, cards: [], currentQuiz: null, errorMessage: null }
    mock.listPlans.mockResolvedValue([{ ...saved, updatedAt: '2026-09-10T10:00:00' }])
    mock.getPlan.mockResolvedValue(saved)
    mock.getSession.mockResolvedValue(session)
    mock.planSession.mockResolvedValue(session)
    const onSession = vi.fn()
    const props = { knowledgeBase: { id: '7', name: '课程', createdAt: '', updatedAt: '' }, requestedSessionId: null, onSession }
    const { rerender } = render(<PlanningStart {...props} visible />)
    expect(await screen.findByText('已完成 0 / 1 个知识点')).toBeInTheDocument()
    expect(screen.queryByLabelText('本次知识点数')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '继续学习' }))
    await waitFor(() => expect(onSession).toHaveBeenCalledWith(session))
    expect(mock.planSession).toHaveBeenCalledWith(saved.id)
    rerender(<PlanningStart {...props} visible={false} />)
    mock.getSession.mockResolvedValue({ ...session, status: 'COMPLETED', plan: [{ id: '101', status: 'COMPLETED' }] })
    rerender(<PlanningStart {...props} visible />)
    expect(await screen.findByText('已完成 1 / 1 个知识点')).toBeInTheDocument()
    expect(screen.getByText('✓ 已完成')).toBeInTheDocument()
    expect(mock.createPlan).not.toHaveBeenCalled()
    expect(mock.executePlan).not.toHaveBeenCalled()
  })

})
