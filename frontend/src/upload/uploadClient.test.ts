import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadMissing, uploadStatus, type UploadStatus } from './uploadClient'

const request = vi.hoisted(() => vi.fn())
vi.mock('../api', () => ({ apiRequest: request }))

function status(missing: number[], uploaded: number[] = []): UploadStatus {
  return { uploadSessionId: '55', knowledgeBaseId: '1', filename: 'test.txt', fileHash: 'hash',
    fileSize: 30, chunkSize: 3, totalChunks: 10, status: 'UPLOADING', uploadedChunkIndexes: uploaded,
    missingChunkIndexes: missing }
}

describe('resumable upload client', () => {
  beforeEach(() => { request.mockReset() })

  it('normalizes the backend Long file size before byte arithmetic', async () => {
    request.mockResolvedValue({ ...status([]), fileSize: '400000000' })
    expect((await uploadStatus('55')).fileSize).toBe(400000000)
  })

  it('sends only server-reported missing parts and counts saved bytes', async () => {
    request.mockResolvedValue(undefined)
    const progress: number[] = []
    await uploadMissing(new File(['123456789012345678901234567890'], 'test.txt'),
      status([1, 4, 7], [0, 2, 3, 5, 6, 8, 9]), new AbortController().signal, (value) => progress.push(value))
    expect(request.mock.calls.map(([url]) => url)).toEqual([
      '/api/files/multipart/55/chunks/1', '/api/files/multipart/55/chunks/4', '/api/files/multipart/55/chunks/7',
    ])
    expect(progress[0]).toBe(21)
    expect(progress.at(-1)).toBe(30)
  })

  it('stops scheduling further parts after a failure and does not silently retry', async () => {
    request.mockRejectedValue(new Error('storage unavailable'))
    await expect(uploadMissing(new File(['123456789012345678901234567890'], 'test.txt'),
      status([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]), new AbortController().signal, () => {})).rejects.toThrow('storage unavailable')
    expect(request).toHaveBeenCalledTimes(4)
  })

  it('a paused operation cannot start another request', async () => {
    const controller = new AbortController()
    controller.abort()
    await expect(uploadMissing(new File(['x'], 'test.txt'), status([0]), controller.signal, () => {}))
      .rejects.toMatchObject({ name: 'AbortError' })
    expect(request).not.toHaveBeenCalled()
  })
})
