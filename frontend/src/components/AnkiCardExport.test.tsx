import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AnkiCardExport } from './AnkiCardExport'
import { apiRequest } from '../api'

vi.mock('../api', () => ({ apiRequest: vi.fn() }))
const request = vi.mocked(apiRequest)
beforeEach(() => { request.mockReset() })

describe('Anki export', () => {
  it('loads durable success after refresh without exporting automatically', async () => {
    request.mockResolvedValue({ cardId: '3', status: 'SUCCEEDED', noteId: '77', attempts: 1 })
    render(<AnkiCardExport cardId="3" />)
    expect(await screen.findByText('已导出至 Anki')).toBeInTheDocument()
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith('/api/review/cards/3/anki')
  })

  it('retains failure, prevents duplicate submit and retries the same card', async () => {
    request.mockResolvedValueOnce({ cardId: '3', status: 'FAILED', errorMessage: '请打开本机 Anki', attempts: 1 })
    let resolve!: (value: unknown) => void
    request.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    render(<AnkiCardExport cardId="3" />)
    const retry = await screen.findByRole('button', { name: '重试导出到 Anki' })
    expect(screen.getByRole('alert')).toHaveTextContent('请打开本机 Anki')
    fireEvent.click(retry); fireEvent.click(retry)
    expect(screen.getByRole('button', { name: '正在导出…' })).toBeDisabled()
    expect(request).toHaveBeenCalledTimes(2)
    await act(async () => resolve({ cardId: '3', status: 'SUCCEEDED', noteId: '77', attempts: 2 }))
    expect(await screen.findByText('已导出至 Anki')).toBeInTheDocument()
    expect(request).toHaveBeenLastCalledWith('/api/review/cards/3/anki', { method: 'POST' })
  })

  it('does not report success when export connection fails', async () => {
    request.mockResolvedValueOnce({ cardId: '3', status: 'PENDING', attempts: 0 })
    request.mockRejectedValueOnce(new Error('无法连接 AnkiConnect'))
    render(<AnkiCardExport cardId="3" />)
    fireEvent.click(await screen.findByRole('button', { name: '导出到 Anki' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('无法连接 AnkiConnect'))
    expect(screen.queryByText('已导出至 Anki')).not.toBeInTheDocument()
  })
})
