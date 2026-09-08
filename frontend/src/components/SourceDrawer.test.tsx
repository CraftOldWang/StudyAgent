import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => vi.fn())
vi.mock('../api', () => ({ apiRequest: api }))
import { SourceLink, SourceProvider } from './SourceDrawer'

describe('source drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
    HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); this.dispatchEvent(new Event('close')) }
  })
  it('does not let an earlier knowledge-base response overwrite the current source', async () => {
    let resolveOld!: (value: unknown) => void
    api.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    api.mockResolvedValueOnce({ chunkId: 'c/1', documentId: '4', documentTitle: '当前课件', sourceLocation: '第 2 页', content: '<script>untrusted source</script>' })
    const { rerender, container } = render(<SourceProvider knowledgeBaseId="10"><SourceLink chunkId="c/1" /></SourceProvider>)
    fireEvent.click(screen.getByRole('button', { name: '查看资料来源' }))
    rerender(<SourceProvider knowledgeBaseId="20"><SourceLink chunkId="c/1" /></SourceProvider>)
    expect(await screen.findByText('当前课件')).toBeInTheDocument()
    await act(async () => resolveOld({ chunkId: 'c/1', documentTitle: '旧课件', content: '旧内容' }))
    expect(screen.queryByText('旧课件')).not.toBeInTheDocument()
    expect(api).toHaveBeenLastCalledWith('/api/knowledge-bases/20/source?chunkId=c%2F1')
    expect(container.querySelector('script')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '关闭来源' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
