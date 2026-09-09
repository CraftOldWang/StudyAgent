import { hashBlob } from './hashBlob'

self.onmessage = async (event: MessageEvent<{ file: File }>) => {
  try {
    const hash = await hashBlob(event.data.file, (bytes) => self.postMessage({ bytes }))
    self.postMessage({ hash })
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : '无法读取文件。' })
  }
}
