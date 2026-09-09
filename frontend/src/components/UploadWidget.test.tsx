import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { UploadWidget } from './UploadWidget'

const client = vi.hoisted(() => ({ uploadStatus: vi.fn(), initializeUpload: vi.fn(), completeUpload: vi.fn(),
  cancelUpload: vi.fn(), uploadMissing: vi.fn() }))
const hash = vi.hoisted(() => vi.fn())
vi.mock('../upload/hashFile', () => ({ hashFile: hash }))
vi.mock('../upload/uploadClient', async (original) => ({ ...await original<typeof import('../upload/uploadClient')>(), ...client }))

const savedTask = { knowledgeBaseId: '1', knowledgeBaseName: '原资料库', filename: 'course.pdf',
  size: 100, contentType: 'application/pdf', sha256: 'a'.repeat(64), sessionId: '55' }
const selected = { id: '2', name: '另一个资料库', createdAt: '', updatedAt: '' }

describe('upload recovery UI', () => {
  beforeEach(() => { vi.resetAllMocks(); localStorage.clear() })

  it('reload reads durable byte progress without starting another upload or changing its target', async () => {
    localStorage.setItem('studypilot:upload:user1:v1', JSON.stringify(savedTask))
    client.uploadStatus.mockResolvedValue({ status: 'UPLOADING', fileSize: 100, chunkSize: 8, uploadedChunkIndexes: [0, 1] })
    render(<UploadWidget knowledgeBase={selected} onUploaded={vi.fn()} />)
    await waitFor(() => expect(screen.getByRole('progressbar')).toHaveAttribute('value', '16'))
    expect(screen.getByText(/上传至 原资料库/)).toHaveTextContent('当前已切换到其他资料库')
    expect(client.initializeUpload).not.toHaveBeenCalled()
    expect(hash).not.toHaveBeenCalled()
  })

  it('a saved completed upload restores success and clears the local pending record', async () => {
    localStorage.setItem('studypilot:upload:user1:v1', JSON.stringify(savedTask))
    client.uploadStatus.mockResolvedValue({ status: 'COMPLETED', fileSize: 100, chunkSize: 100, uploadedChunkIndexes: [0] })
    const onUploaded = vi.fn()
    render(<UploadWidget knowledgeBase={selected} onUploaded={onUploaded} />)
    expect(await screen.findByText('上传已完成')).toBeInTheDocument()
    expect(onUploaded).toHaveBeenCalledWith('1')
    expect(localStorage.getItem('studypilot:upload:user1:v1')).toBeNull()
  })

  it('pausing local hashing leaves the original file available and does not claim saved bytes', async () => {
    hash.mockImplementation((_file: File, signal: AbortSignal, progress: (value: number) => void) => new Promise((_resolve, reject) => {
      progress(2)
      signal.addEventListener('abort', () => reject(new DOMException('paused', 'AbortError')))
    }))
    render(<UploadWidget knowledgeBase={selected} onUploaded={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('选择课程资料'), { target: { files: [new File(['pdf'], 'course.pdf', { type: 'application/pdf' })] } })
    fireEvent.click(await screen.findByRole('button', { name: '暂停上传' }))
    expect(await screen.findByText('上传已暂停')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '0')
    expect(screen.getByRole('button', { name: '继续上传' })).toBeEnabled()
    expect(client.initializeUpload).not.toHaveBeenCalled()
  })
})
