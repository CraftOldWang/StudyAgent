import { Blob } from 'node:buffer'
import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { HASH_BLOCK_BYTES, hashBlob } from './hashBlob'

describe('incremental upload hash', () => {
  it('matches the independent native SHA-256 across an 8 MiB boundary', async () => {
    const bytes = new Uint8Array(HASH_BLOCK_BYTES + 197)
    for (let index = 0; index < bytes.length; index++) bytes[index] = index % 251
    const progress: number[] = []
    const result = await hashBlob(new Blob([bytes]), (value) => progress.push(value))
    expect(result).toBe(createHash('sha256').update(bytes).digest('hex'))
    expect(progress).toEqual([HASH_BLOCK_BYTES, bytes.length])
  })
})
