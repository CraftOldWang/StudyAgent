import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MessageContent } from './MessageContent'
describe('course message rendering', () => {
  it('renders code and emphasis without executing raw HTML or fetching model-provided images', () => {
    const { container } = render(<MessageContent text={'**重点**\n\n```java\nint n = 1;\n```\n\n<script>alert(1)</script>\n\n![插图](https://example.com/private-text)\n\n[错误链接](javascript:alert(1))'} />)
    expect(screen.getByText('重点').tagName).toBe('STRONG')
    expect(container.querySelector('pre code')).toHaveTextContent('int n = 1;')
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a')?.getAttribute('href')).not.toContain('javascript:')
  })
})
