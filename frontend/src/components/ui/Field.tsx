import type { ReactNode, TextareaHTMLAttributes } from 'react'

export function MultilineInput({ className = '', ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={`resize-none ${className}`} />
}

export function Field({ id, label, hint, error, children }: {
  id: string; label: string; hint?: string; error?: string; children: ReactNode
}) {
  return <div className="field"><label htmlFor={id}>{label}</label>{children}
    {hint && <small id={`${id}-hint`}>{hint}</small>}
    {error && <p className="field-error" id={`${id}-error`} role="alert">{error}</p>}
  </div>
}
