import { createContext, useCallback, useContext, useEffect, useId, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { NavLink } from 'react-router-dom'

type Tone = 'good' | 'warn' | 'bad' | 'info' | 'neutral'

export function Button({ variant, small, className = '', ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'danger'; small?: boolean }) {
  return <button {...rest} className={`btn ${variant ?? ''} ${small ? 'small' : ''} ${className}`} />
}

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge ${tone === 'neutral' ? '' : tone}`}>{children}</span>
}

export function Card({ children, flush, className = '' }: { children: ReactNode; flush?: boolean; className?: string }) {
  return <section className={`card ${flush ? 'flush' : ''} ${className}`}>{children}</section>
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="page-head">
      <div className="stack tight"><h1>{title}</h1>{subtitle && <p className="muted">{subtitle}</p>}</div>
      {actions && <div className="row wrap">{actions}</div>}
    </header>
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return <Card className="stat"><div className="label">{label}</div><div className="value">{value}</div>{hint && <div className="muted small">{hint}</div>}</Card>
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: (id: string) => ReactNode }) {
  const id = useId()
  return (
    <label className="field" htmlFor={id}>
      {label}{hint && <span className="hint">{hint}</span>}
      {children(id)}
    </label>
  )
}

export function Input(props: React.InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string }) {
  const { label, hint, ...rest } = props
  return <Field label={label} hint={hint}>{(id) => <input id={id} {...rest} />}</Field>
}

export function Select({ label, hint, children, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement> & { label: string; hint?: string }) {
  return <Field label={label} hint={hint}>{(id) => <select id={id} {...rest}>{children}</select>}</Field>
}

export function Spinner() { return <div className="spinner" role="status" aria-label="Loading" /> }
export function ErrorNote({ message }: { message: string | null }) { return message ? <div className="alert" role="alert">{message}</div> : null }
export function Empty({ children }: { children: ReactNode }) { return <div className="empty">{children}</div> }

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="scroll-x">
      <table className="table"><thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead><tbody>{children}</tbody></table>
    </div>
  )
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="row spread" style={{ marginBottom: 14 }}><h2>{title}</h2><Button small onClick={onClose} aria-label="Close">✕</Button></div>
        {children}
      </div>
    </div>
  )
}

/** Asks for the Owner PIN. Used before price, staff, bank and earnings actions. */
export function PinPrompt({ title = 'Enter Owner PIN', onSubmit, onClose, error }: { title?: string; onSubmit: (pin: string) => void; onClose: () => void; error?: string | null }) {
  const [pin, setPin] = useState('')
  return (
    <Modal title={title} onClose={onClose}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); if (pin.length === 6) onSubmit(pin) }}>
        <p className="muted small">This action is protected. Enter the 6-digit Owner PIN.</p>
        <Input label="Owner PIN" type="password" inputMode="numeric" autoFocus maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} autoComplete="off" />
        <ErrorNote message={error ?? null} />
        <div className="row spread"><Button type="button" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" disabled={pin.length !== 6}>Confirm</Button></div>
      </form>
    </Modal>
  )
}

// ---- toasts ----
interface ToastItem { id: number; text: string; bad?: boolean }
const ToastCtx = createContext<(text: string, bad?: boolean) => void>(() => undefined)
export const useToast = () => useContext(ToastCtx)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const push = useCallback((text: string, bad?: boolean) => {
    const id = Date.now() + Math.random()
    setItems((l) => [...l, { id, text, bad }])
    setTimeout(() => setItems((l) => l.filter((t) => t.id !== id)), 3500)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">{items.map((t) => <div key={t.id} className={`toast ${t.bad ? 'bad' : ''}`}>{t.text}</div>)}</div>
    </ToastCtx.Provider>
  )
}

export interface NavItem { to: string; label: string; end?: boolean }

export function AppShell({ brand, subtitle, nav, user, onLogout, children }: { brand: string; subtitle: string; nav: NavItem[]; user: string; onLogout: () => void; children: ReactNode }) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">{brand}<small>{subtitle}</small></div>
        <nav className="nav">{nav.map((n) => <NavLink key={n.to} to={n.to} end={n.end}>{n.label}</NavLink>)}</nav>
        <div className="foot"><div className="small muted">{user}</div><Button small onClick={onLogout} style={{ marginTop: 8 }}>Sign out</Button></div>
      </aside>
      <main className="main">{children}</main>
    </div>
  )
}

export function statusTone(status: string): Tone {
  if (['LIVE', 'COMPLETED', 'APPROVED', 'CONFIRMED', 'PROCESSED'].includes(status)) return 'good'
  if (['SUBMITTED', 'UNDER_REVIEW', 'PAID', 'IN_PROGRESS', 'PENDING_PAYMENT', 'CHANGES_REQUESTED'].includes(status)) return 'warn'
  if (['SUSPENDED', 'REJECTED', 'CANCELLED', 'NO_SHOW', 'EXPIRED'].includes(status)) return 'bad'
  return 'neutral'
}
