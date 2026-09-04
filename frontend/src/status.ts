// Pipeline terminal states stay centralized because the backend ingestion contract is still being finalized.
export const DOCUMENT_TERMINAL_STATUSES = new Set(['INDEXED', 'FAILED'])

export function isDocumentTerminal(status: string): boolean {
  return DOCUMENT_TERMINAL_STATUSES.has(status.toUpperCase())
}

export function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    RECEIVED: '已接收',
    STORED: '已存储',
    PARSED: '已解析',
    CHUNKED: '已分块',
    EMBEDDED: '已向量化',
    INDEXED: '可检索',
    FAILED: '处理失败',
  }
  return labels[status.toUpperCase()] ?? status
}
