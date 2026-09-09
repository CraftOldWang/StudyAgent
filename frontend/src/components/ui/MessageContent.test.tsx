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

  it('renders inline and display math with accessible MathML while preserving code literals', () => {
    const { container } = render(<MessageContent text={'复杂度 $n^2$\n\n$$\nT(n) = 2T(n/2) + n\n$$\n\n`$literal$`'} />)
    expect(container.querySelectorAll('.katex')).toHaveLength(2)
    expect(container.querySelectorAll('math')).toHaveLength(2)
    expect(container.querySelector('.katex-display')).not.toBeNull()
    expect(screen.getByText('$literal$').tagName).toBe('CODE')
  })

  it('keeps malformed streaming math readable and disallows math image commands', () => {
    const { container, rerender } = render(<MessageContent text={'还在生成 $\\frac{n}'} />)
    expect(container).toHaveTextContent('还在生成')
    rerender(<MessageContent text={'$\\includegraphics{https://example.com/private-text}$\n\n$\\href{javascript:alert(1)}{bad}$\n\n$\\invalidcommand$'} />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a')).toBeNull()
    expect(container).toHaveTextContent('invalidcommand')
  })
})
