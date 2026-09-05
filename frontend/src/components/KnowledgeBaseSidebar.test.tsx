import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { KnowledgeBaseSidebar } from './KnowledgeBaseSidebar'

const knowledgeBase = {
  id: '12',
  name: 'Java 并发',
  createdAt: '2026-09-04T12:00:00',
  updatedAt: '2026-09-04T12:00:00',
}

describe('KnowledgeBaseSidebar', () => {
  it('normalizes a new knowledge base name before creating it', async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined)
    render(
      <KnowledgeBaseSidebar
        busy={false}
        items={[]}
        loading={false}
        onCreate={onCreate}
        onRename={vi.fn()}
        onSelect={vi.fn()}
        selectedId={null}
      />,
    )

    fireEvent.change(screen.getByLabelText('新建知识库'), { target: { value: '  JVM 原理  ' } })
    fireEvent.click(screen.getByRole('button', { name: '创建' }))

    await waitFor(() => expect(onCreate).toHaveBeenCalledWith('JVM 原理'))
    expect(screen.getByLabelText('新建知识库')).toHaveValue('')
  })

  it('renames an existing knowledge base without changing selection', async () => {
    const onRename = vi.fn().mockResolvedValue(undefined)
    const onSelect = vi.fn()
    render(
      <KnowledgeBaseSidebar
        busy={false}
        items={[knowledgeBase]}
        loading={false}
        onCreate={vi.fn()}
        onRename={onRename}
        onSelect={onSelect}
        selectedId={knowledgeBase.id}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: `重命名 ${knowledgeBase.name}` }))
    fireEvent.change(screen.getByLabelText(`重命名 ${knowledgeBase.name}`), {
      target: { value: '  Java 虚拟机  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(onRename).toHaveBeenCalledWith('12', 'Java 虚拟机'))
    expect(onSelect).not.toHaveBeenCalled()
  })
})
