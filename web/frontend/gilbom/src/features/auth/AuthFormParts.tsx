import type { ReactNode } from 'react'
import { ApiError, getApiErrorMessage } from '../../shared/api/apiClient'

export function AuthCardHeader({
  eyebrow,
  title,
  description,
}: {
  eyebrow: string
  title: string
  description: string
}) {
  return (
    <header className="auth-card-header">
      <p className="eyebrow">{eyebrow}</p>
      <h2>{title}</h2>
      <p>{description}</p>
    </header>
  )
}

export function FormField({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: string
  htmlFor: string
  hint?: string
  error?: string | null
  children: ReactNode
}) {
  return (
    <div className="form-field">
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? (
        <p className="field-error" id={`${htmlFor}-error`}>
          {error}
        </p>
      ) : hint ? (
        <p className="field-hint" id={`${htmlFor}-hint`}>
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export function ErrorNotice({ error }: { error: unknown }) {
  if (error === null) {
    return null
  }
  const requestId = error instanceof ApiError ? error.requestId : null
  return (
    <div className="form-notice form-notice-error" role="alert">
      <strong>{getApiErrorMessage(error)}</strong>
      {requestId ? <small>요청 ID: {requestId}</small> : null}
    </div>
  )
}

export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div className="form-notice form-notice-success" role="status">
      {children}
    </div>
  )
}

