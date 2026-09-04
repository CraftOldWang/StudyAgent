import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DocumentPanel } from './DocumentPanel'

const knowledgeBase = { id: 1, name: 'Java', createdAt: '', updatedAt: '' }

describe('DocumentPanel', () => {
  it('rejects non-PDF input before upload', () => {
    const onUpload = vi.fn()
    const { container } = render(
      <DocumentPanel
        documents={[]}
        knowledgeBase={knowledgeBase}
        loading={false}
        onUpload={onUpload}
        uploadBusy={false}
      />,
    )

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['text'], 'notes.txt', { type: 'text/plain' })] } })

    expect(screen.getByRole('alert')).toHaveTextContent('当前里程碑仅支持 PDF 文件。')
    expect(onUpload).not.toHaveBeenCalled()
  })
})
