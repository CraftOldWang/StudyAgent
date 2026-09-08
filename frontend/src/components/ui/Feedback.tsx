import type { ReactNode } from 'react'

export function Feedback({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <div className={error ? 'feedback feedback-error' : 'feedback'} role={error ? 'alert' : 'status'}>{children}</div>
}
