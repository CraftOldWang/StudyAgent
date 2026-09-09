import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { ToolCalls } from './ToolCalls'

it('shows tool activity in a closed disclosure with expandable arguments', () => {
  render(<ToolCalls live={[{ id: 'call', name: 'knowledge_search', status: 'SUCCEEDED', input: { query: '线程池' }, output: [] }]} />)
  const summary = screen.getByText('搜索资料')
  expect(summary.closest('details')).not.toHaveAttribute('open')
  expect(summary.closest('details')).toHaveTextContent('线程池')
})
