import { describe, expect, it } from 'vitest'
import { readEventStream } from './stream'

function response(text: string, step = 1) {
  const bytes = new TextEncoder().encode(text)
  return new Response(new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += step) controller.enqueue(bytes.slice(i, i + step))
    controller.close()
  } }), { headers: { 'Content-Type': 'text/event-stream' } })
}
describe('stream transport', () => {
  it('preserves split Chinese UTF-8, CRLF, JSON and multiline data frames', async () => {
    const events: unknown[] = []
    await readEventStream(response(': heartbeat\r\nevent: text\r\ndata: {"text":"知识点"}\r\n\r\nevent: result\ndata: {"turn":\ndata: {"status":"SUCCEEDED"}}\n\n'), event => events.push(event))
    expect(events).toEqual([{ event: 'text', data: { text: '知识点' } }, { event: 'result', data: { turn: { status: 'SUCCEEDED' } } }])
  })
  it('treats EOF before a terminal event as uncertain, not successful', async () => {
    await expect(readEventStream(response('event: text\ndata: {"text":"半条回答"}\n\n'), () => undefined)).rejects.toThrow('后台可能仍在处理')
  })
  it('reports backend rejection and invalid JSON instead of silently dropping it', async () => {
    await expect(readEventStream(new Response('{"message":"会话不存在"}', { status: 400 }), () => undefined)).rejects.toThrow('会话不存在')
    await expect(readEventStream(response('event: result\ndata: invalid\n\n'), () => undefined)).rejects.toThrow()
  })
})
