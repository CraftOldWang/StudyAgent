import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlanningView } from '../learningTypes'
const mock = vi.hoisted(() => ({ createPlan: vi.fn(), executePlan: vi.fn(), getPlan: vi.fn(), planSession: vi.fn() }))
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
  beforeEach(() => vi.clearAllMocks())
  it('keeps course/exercise roles separate and resumes the same persisted planning task', async () => {
    mock.createPlan.mockResolvedValue({ ...failed, status: 'PENDING', stages: [], errorMessage: null })
    mock.executePlan.mockResolvedValueOnce(failed).mockResolvedValueOnce({ ...failed, status: 'SUCCEEDED', errorMessage: null, result: { tasks: [], emphasis: { matches: [], unmatched: [] } } })
    render(<PlanningStart knowledgeBase={{ id: '7', name: '课程', createdAt: '', updatedAt: '' }} onSession={vi.fn()} />)
    await screen.findByRole('group', { name: '课件.pdf' })
    fireEvent.change(screen.getByLabelText('这次想学会什么？'), { target: { value: '考试复习' } })
    fireEvent.click(within(screen.getByRole('group', { name: '课件.pdf' })).getByLabelText('课件'))
    fireEvent.click(within(screen.getByRole('group', { name: '习题.pdf' })).getByLabelText('习题参考'))
    expect(within(screen.getByRole('group', { name: '处理中.pdf' })).getByLabelText('课件')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '生成重点学习计划' }))
    expect(await screen.findByText('重点校验未通过')).toBeInTheDocument()
    expect(mock.createPlan).toHaveBeenCalledWith('7', '考试复习', ['9007199254740999'], ['9007199254741001'], 5)
    fireEvent.click(screen.getByRole('button', { name: '继续生成此计划' }))
    await screen.findByRole('button', { name: '按此计划开始学习' })
    await waitFor(() => expect(mock.executePlan).toHaveBeenCalledTimes(2))
    expect(mock.executePlan).toHaveBeenNthCalledWith(2, failed.id)
    expect(mock.createPlan).toHaveBeenCalledTimes(1)
  })
})
