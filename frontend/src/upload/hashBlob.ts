import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'

export const HASH_BLOCK_BYTES = 8 * 1024 * 1024

type SliceableBytes = { size: number; slice(start: number, end: number): { arrayBuffer(): Promise<ArrayBuffer> } }

export async function hashBlob(blob: SliceableBytes, progress: (bytes: number) => void): Promise<string> {
  const hash = sha256.create()
  for (let offset = 0; offset < blob.size; offset += HASH_BLOCK_BYTES) {
    const bytes = new Uint8Array(await blob.slice(offset, offset + HASH_BLOCK_BYTES).arrayBuffer())
    hash.update(bytes)
    progress(Math.min(offset + bytes.length, blob.size))
  }
  return bytesToHex(hash.digest())
}
