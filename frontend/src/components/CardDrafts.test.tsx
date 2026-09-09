import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { CardDrafts } from './CardDrafts'
vi.mock('./SourceDrawer', () => ({ SourceLink: () => null }))

it('keeps edits in the draft until explicit save or confirmation', () => {
  const onSave = vi.fn().mockResolvedValue(undefined), onConfirm = vi.fn().mockResolvedValue(undefined)
  render(<CardDrafts cards={[{ id: '1', front: '旧问题', back: '答案', sourceChunkId: 'source' }]}
    busy={false} confirming={false} onSave={onSave} onConfirm={onConfirm} onRewrite={vi.fn()} />)
  fireEvent.change(screen.getByLabelText('第 1 张 · 正面'), { target: { value: '改过的问题' } })
  expect(onSave).not.toHaveBeenCalled(); expect(onConfirm).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '确认全部卡片并写入 Anki' }))
  expect(onConfirm).toHaveBeenCalledWith([expect.objectContaining({ front: '改过的问题' })])
})
