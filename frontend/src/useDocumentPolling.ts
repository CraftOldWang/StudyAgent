import { useEffect, useRef } from 'react'
import { isDocumentTerminal } from './status'
import type { DocumentItem } from './types'

const POLL_INTERVAL_MS = 1500

export function useDocumentPolling(
  knowledgeBaseId: number | null,
  documents: DocumentItem[],
  refresh: (silent?: boolean) => Promise<void>,
): void {
  const refreshRef = useRef(refresh)
  refreshRef.current = refresh

  const hasProcessingDocument = documents.some((document) => !isDocumentTerminal(document.pipelineStatus))

  useEffect(() => {
    if (!knowledgeBaseId || !hasProcessingDocument) return

    const timer = window.setInterval(() => {
      void refreshRef.current(true)
    }, POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [knowledgeBaseId, hasProcessingDocument])
}
