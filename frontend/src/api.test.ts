import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'

describe('api client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('unwraps data and always injects the server identity header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.listKnowledgeBases()).resolves.toEqual([])
    const headers = new Headers(fetchMock.mock.calls[0][1].headers)
    expect(headers.get('X-User-Id')).toBe('1')
  })

  it('surfaces a backend business error instead of returning empty data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 40001,
      message: '知识库不存在',
      data: null,
    }), { status: 404, headers: { 'Content-Type': 'application/json' } })))

    await expect(api.listDocuments(99)).rejects.toMatchObject({
      name: 'ApiError',
      message: '知识库不存在',
      status: 404,
    } satisfies Partial<ApiError>)
  })

  it('uses the fixed agent-search endpoint and JSON request contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: { query: 'JVM 是什么', answer: '回答', toolInvoked: true, hits: [] },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await api.agentSearch(7, '  JVM 是什么  ')

    expect(fetchMock).toHaveBeenCalledWith('/api/knowledge-bases/7/agent-search', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ query: '  JVM 是什么  ' }),
    }))
    const headers = new Headers(fetchMock.mock.calls[0][1].headers)
    expect(headers.get('Content-Type')).toBe('application/json')
  })
})
