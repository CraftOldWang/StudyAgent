import { ApiError } from './api'

export interface StreamEvent { event: string; data: unknown }

// A network read can end inside a UTF-8 character, a line or a JSON object.
export async function readEventStream(response: Response, onEvent: (event: StreamEvent) => void) {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new ApiError(body?.message || `请求失败（HTTP ${response.status}）`, response.status)
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    throw new ApiError('未收到流式响应，请查询已保存结果。')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event = 'message'
  let lines: string[] = []
  let completed = false
  function consume(line: string) {
    if (line === '') {
      if (lines.length) {
        const data: unknown = JSON.parse(lines.join('\n'))
        if (event === 'result' || event === 'failure') completed = true
        onEvent({ event, data })
      }
      event = 'message'; lines = []
    } else if (line.startsWith('event:')) event = line.slice(6).trimStart()
    else if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, ''))
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let end: number
      while ((end = buffer.indexOf('\n')) >= 0) {
        consume(buffer.slice(0, end).replace(/\r$/, ''))
        buffer = buffer.slice(end + 1)
      }
      if (done) break
    }
    if (!completed) throw new ApiError('连接已断开，后台可能仍在处理。请查询结果或重试同一条消息。')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
