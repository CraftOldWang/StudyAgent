import { describe, expect, it } from 'vitest'
import { isDocumentTerminal } from './status'

describe('document pipeline status', () => {
  it('polls processing states and stops only at the centralized terminal states', () => {
    expect(isDocumentTerminal('EMBEDDED')).toBe(false)
    expect(isDocumentTerminal('INDEXED')).toBe(true)
    expect(isDocumentTerminal('failed')).toBe(true)
  })
})
