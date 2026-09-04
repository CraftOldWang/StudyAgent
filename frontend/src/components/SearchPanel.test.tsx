import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SearchPanel } from './SearchPanel'

describe('SearchPanel', () => {
  it('switches to agent mode and submits a normalized question', async () => {
    const onSearch = vi.fn().mockResolvedValue(undefined)
    render(<SearchPanel disabled={false} loading={false} onSearch={onSearch} result={null} />)

    fireEvent.click(screen.getByRole('button', { name: 'Agent 检索' }))
    fireEvent.change(screen.getByLabelText('检索问题'), { target: { value: '  解释 happens-before  ' } })
    fireEvent.click(screen.getByRole('button', { name: '调用 Agent' }))

    await waitFor(() => expect(onSearch).toHaveBeenCalledWith('agent', '解释 happens-before'))
  })

  it('renders an agent answer and its string-based provenance identifiers', () => {
    render(
      <SearchPanel
        disabled={false}
        loading={false}
        onSearch={vi.fn()}
        result={{
          answer: '可见性由同步关系保证。',
          toolInvoked: true,
          hits: [{
            chunkId: 'chunk-42',
            content: '对 happens-before 的说明。',
            provenance: {
              documentId: 'document-9',
              documentTitle: 'JMM 笔记.pdf',
              sourceLocation: '第 3 页',
            },
            score: 0.91234,
          }],
        }}
      />,
    )

    expect(screen.getByText('可见性由同步关系保证。')).toBeInTheDocument()
    expect(screen.getByText('JMM 笔记.pdf')).toBeInTheDocument()
    expect(screen.getByText('第 3 页')).toBeInTheDocument()
    expect(screen.getByText('chunk #chunk-42')).toBeInTheDocument()
    expect(screen.getByText('相关度 0.9123')).toBeInTheDocument()
  })
})
