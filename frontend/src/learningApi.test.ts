import { afterEach, describe, expect, it, vi } from 'vitest'
import { learningApi } from './learningApi'

describe('learning API contract', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('preserves a database ID beyond Number.MAX_SAFE_INTEGER in the create payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: { traceId: 'trace-1', session: {} },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await learningApi.createSession('9007199254740993', '理解 JMM')

    expect(fetchMock).toHaveBeenCalledWith('/api/learning/sessions', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ knowledgeBaseId: '9007199254740993', learningGoal: '理解 JMM' }),
    }))
  })

  it('submits exactly the ordered five-answer aggregate', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: {},
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const answers = ['A', 'B', 'C', 'D', 'A']

    await learningApi.submitQuiz('9007199254740997', answers)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/learning/sessions/9007199254740997/quiz/submit',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ answers }) }),
    )
  })
})
