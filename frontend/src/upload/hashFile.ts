export function hashFile(file: File, signal: AbortSignal, progress: (bytes: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('已暂停文件校验。', 'AbortError'))
      return
    }
    const worker = new Worker(new URL('./hash.worker.ts', import.meta.url), { type: 'module' })
    const cleanup = () => {
      signal.removeEventListener('abort', abort)
      worker.terminate()
    }
    const abort = () => {
      cleanup()
      reject(new DOMException('已暂停文件校验。', 'AbortError'))
    }
    worker.onmessage = (event: MessageEvent<{ bytes?: number; hash?: string; error?: string }>) => {
      if (event.data.error) {
        cleanup()
        reject(new Error(event.data.error))
      } else if (event.data.hash) {
        cleanup()
        resolve(event.data.hash)
      } else if (event.data.bytes !== undefined) {
        progress(event.data.bytes)
      }
    }
    worker.onerror = () => {
      cleanup()
      reject(new Error('文件校验线程无法启动，请重新选择文件。'))
    }
    signal.addEventListener('abort', abort, { once: true })
    worker.postMessage({ file })
  })
}
