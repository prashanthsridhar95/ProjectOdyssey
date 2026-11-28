import { Component, createContext, useContext, useEffect, useId, useState, type ButtonHTMLAttributes, type ErrorInfo, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Icon, type IconName } from './icons'

export type Tone = 'good' | 'warn' | 'bad' | 'info' | 'neutral'

export function Button({ variant, small, big, block, busy, icon, solid, className = '', children, disabled, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'danger' | 'ghost'; small?: boolean; big?: boolean; block?: boolean; busy?: boolean; icon?: IconName; solid?: boolean
}) {
  const cls = ['btn', variant, small && 'small', big && 'big', block && 'block', solid && 'solid', className].filter(Boolean).join(' ')
  return (
    <button {...rest} disabled={disabled || busy} aria-busy={busy || undefined} className={cls}>
      {busy ? <span className="spin" aria-hidden="true" /> : icon ? <Icon name={icon} size={small ? 14 : 16} /> : null}
      {children}
    </button>
  )
}

export function IconButton({ icon, label, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; label: string }) {
  return <button type="button" className="iconbtn" aria-label={label} title={label} {...rest}><Icon name={icon} size={18} /></button>
}

export function Badge({ tone = 'neutral', dot, children }: { tone?: Tone; dot?: boolean; children: ReactNode }) {
  return <span className={`badge ${tone === 'neutral' ? '' : tone}`}>{dot && <span className="dot" />}{children}</span>
}

export function Card({ children, flush, className = '', title, actions }: { children: ReactNode; flush?: boolean; className?: string; title?: ReactNode; actions?: ReactNode }) {
  if (title !== undefined) {
    return (
      <section className={`card flush ${className}`}>
        <div className="card-head"><h2>{title}</h2>{actions && <div className="row tight">{actions}</div>}</div>
        {flush ? children : <div style={{ padding: 18 }}>{children}</div>}
      </section>
    )
  }
  return <section className={`card ${flush ? 'flush' : ''} ${className}`}>{children}</section>
}

/** Product name for the browser tab ("Bookings · Rinse Admin"). Set once by the shell. */
export const TitleSuffix = createContext('')

export function usePageTitle(title: string) {
  const suffix = useContext(TitleSuffix)
  useEffect(() => { document.title = suffix ? `${title} · ${suffix}` : title }, [title, suffix])
}

export function PageHeader({ title, subtitle, actions, back, meta }: { title: string; subtitle?: ReactNode; actions?: ReactNode; back?: { to: string; label: string }; meta?: ReactNode }) {
  usePageTitle(title)
  return (
    <header className="page-head">
      <div className="titles">
        {back && <Link className="back" to={back.to}><Icon name="arrowLeft" size={14} />{back.label}</Link>}
        <div className="row wrap" style={{ gap: 12 }}><h1>{title}</h1>{meta && <div className="meta">{meta}</div>}</div>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="row wrap end">{actions}</div>}
    </header>
  )
}

export function Stat({ label, value, hint, icon }: { label: string; value: ReactNode; hint?: ReactNode; icon?: IconName }) {
  return <Card className="stat"><div className="label row tight">{icon && <Icon name={icon} size={14} />}{label}</div><div className="value">{value}</div>{hint && <div className="muted small">{hint}</div>}</Card>
}

export function Field({ label, hint, error, required, children }: { label: ReactNode; hint?: ReactNode; error?: string | null; required?: boolean; children: (id: string) => ReactNode }) {
  const id = useId()
  return (
    <label className={`field ${error ? 'invalid' : ''}`} htmlFor={id}>
      <span>{label}{required && <span className="req" aria-hidden="true">*</span>}</span>
      {children(id)}
      {hint && <span className="hint">{hint}</span>}
      {error && <span className="err" role="alert">{error}</span>}
    </label>
  )
}

export function Input(props: React.InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; hint?: ReactNode; error?: string | null }) {
  const { label, hint, error, ...rest } = props
  return <Field label={label} hint={hint} error={error} required={rest.required}>{(id) => <input id={id} aria-invalid={!!error || undefined} {...rest} />}</Field>
}

export function Select({ label, hint, error, children, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement> & { label: ReactNode; hint?: ReactNode; error?: string | null }) {
  return <Field label={label} hint={hint} error={error} required={rest.required}>{(id) => <select id={id} {...rest}>{children}</select>}</Field>
}

export function TextArea({ label, hint, error, ...rest }: React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label: ReactNode; hint?: ReactNode; error?: string | null }) {
  return <Field label={label} hint={hint} error={error} required={rest.required}>{(id) => <textarea id={id} rows={3} {...rest} />}</Field>
}

export function SearchInput({ value, onChange, placeholder = 'Search', className = 'search', autoFocus }: { value: string; onChange: (v: string) => void; placeholder?: string; className?: string; autoFocus?: boolean }) {
  return (
    <div className={`inputwrap ${className}`}>
      <Icon name="search" size={15} />
      <input type="search" value={value} placeholder={placeholder} aria-label={placeholder} autoFocus={autoFocus} onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Escape' && value) { e.stopPropagation(); onChange('') } }} />
    </div>
  )
}

/** An on/off switch with a title and an explanation, for settings. */
export function SwitchRow({ title, description, checked, onChange, disabled }: { title: string; description?: ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  const id = useId()
  return (
    <label className="switchrow" htmlFor={id}>
      <span className="text"><strong>{title}</strong>{description && <span className="muted small">{description}</span>}</span>
      <input id={id} type="checkbox" role="switch" className="toggle" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
    </label>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) { return <div className="spinner" role="status" aria-label={label} /> }

export function ErrorNote({ message, onRetry }: { message: string | null | undefined; onRetry?: () => void }) {
  if (!message) return null
  return <div className="alert" role="alert"><Icon name="alert" /><div className="grow">{message}</div>{onRetry && <Button small onClick={onRetry} icon="refresh">Retry</Button>}</div>
}

export function Notice({ tone = 'info', children, action }: { tone?: 'info' | 'warn' | 'ok' | 'bad'; children: ReactNode; action?: ReactNode }) {
  const icon: IconName = tone === 'ok' ? 'checkCircle' : tone === 'info' ? 'info' : 'alert'
  return <div className={`alert ${tone === 'bad' ? '' : tone}`} role={tone === 'bad' ? 'alert' : 'status'}><Icon name={icon} /><div className="grow">{children}</div>{action}</div>
}

/** An empty list, explained: what would be here, and what to do about it. */
export function EmptyState({ icon = 'inbox', title, children, action }: { icon?: IconName; title: string; children?: ReactNode; action?: ReactNode }) {
  return <div className="empty"><div className="glyph"><Icon name={icon} size={20} /></div><strong>{title}</strong>{children && <p>{children}</p>}{action}</div>
}
/** Older one-line empty message. */
export function Empty({ children }: { children: ReactNode }) { return <EmptyState title={typeof children === 'string' ? children : 'Nothing here yet'} /> }

export function Skeleton({ width = '100%', height = 12 }: { width?: number | string; height?: number }) { return <div className="skel" style={{ width, height }} /> }

export function SkeletonRows({ rows = 6, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="scroll-x" aria-busy="true" aria-label="Loading">
      <table className="table"><tbody>{Array.from({ length: rows }, (_, r) => (
        <tr key={r}>{Array.from({ length: cols }, (_, c) => <td key={c}><Skeleton width={`${50 + ((r * 7 + c * 13) % 45)}%`} /></td>)}</tr>
      ))}</tbody></table>
    </div>
  )
}

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="scroll-x">
      <table className="table"><thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead><tbody>{children}</tbody></table>
    </div>
  )
}

export function Chips<T extends string>({ options, value, onChange, label }: { options: { value: T; label: string; count?: number }[]; value: T; onChange: (v: T) => void; label?: string }) {
  return (
    <div className="chips" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={value === o.value} className={`chip ${value === o.value ? 'on' : ''}`} onClick={() => onChange(o.value)}>
          {o.label}{o.count !== undefined && <span className="n">{o.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: readonly (readonly [T, string])[]; value: T; onChange: (v: T) => void; label?: string }) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {options.map(([k, l]) => <button key={k} type="button" role="tab" aria-selected={value === k} className={value === k ? 'on' : ''} onClick={() => onChange(k)}>{l}</button>)}
    </div>
  )
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { value: T; label: string; count?: number }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="underline-tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.value} type="button" role="tab" aria-selected={value === t.value} className={value === t.value ? 'on' : ''} onClick={() => onChange(t.value)}>
          {t.label}{t.count !== undefined && <span className="n">{t.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function DescriptionList({ items }: { items: [ReactNode, ReactNode][] }) {
  return <dl className="dl">{items.map(([k, v], i) => <div key={i} style={{ display: 'contents' }}><dt>{k}</dt><dd>{v ?? <span className="faint">-</span>}</dd></div>)}</dl>
}

export function LiveIndicator({ on, text }: { on: boolean; text: string }) {
  return <span className={`live ${on ? '' : 'off'}`}><span className="pulse" />{text}</span>
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || '?'
}

export function Avatar({ name }: { name: string }) { return <span className="avatar" aria-hidden="true">{initials(name)}</span> }

const TONES: Record<string, Tone> = {}
;['LIVE', 'COMPLETED', 'APPROVED', 'CONFIRMED', 'PROCESSED', 'PAID_OUT', 'VERIFIED', 'RESOLVED', 'UPHELD', 'ACTIVE', 'CAPTURED', 'SUCCEEDED'].forEach((s) => (TONES[s] = 'good'))
;['SUBMITTED', 'UNDER_REVIEW', 'PENDING_PAYMENT', 'CHANGES_REQUESTED', 'PENDING', 'OPEN', 'HELD', 'NEEDS_FIX', 'QUEUED'].forEach((s) => (TONES[s] = 'warn'))
;['PAID', 'IN_PROGRESS', 'CHECKED_IN', 'ACCEPTED', 'REFUNDED', 'PARTIALLY_REFUNDED'].forEach((s) => (TONES[s] = 'info'))
;['SUSPENDED', 'REJECTED', 'CANCELLED', 'NO_SHOW', 'EXPIRED', 'FAILED', 'BLOCKED', 'DISMISSED'].forEach((s) => (TONES[s] = 'bad'))

export function statusTone(status: string): Tone { return TONES[status] ?? 'neutral' }

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const text = label ?? status.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
  return <Badge tone={statusTone(status)} dot>{text}</Badge>
}

/** Catches a crash in one page so the rest of the console keeps working, and offers a way back. */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: unknown }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('Page crashed', error, info.componentStack) }
  componentDidUpdate(prev: { resetKey?: unknown }) { if (prev.resetKey !== this.props.resetKey && this.state.error) this.setState({ error: null }) }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <Card>
        <EmptyState icon="alert" title="This page ran into a problem" action={<div className="row"><Button onClick={() => this.setState({ error: null })} icon="refresh">Try again</Button><Button variant="ghost" onClick={() => window.location.assign('/')}>Go to home</Button></div>}>
          Nothing was changed. If it keeps happening, tell the platform team what you were doing and quote: <span className="mono">{this.state.error.message.slice(0, 120)}</span>
        </EmptyState>
      </Card>
    )
  }
}

export function NotFound() {
  usePageTitle('Not found')
  return <Card><EmptyState icon="search" title="We could not find that page" action={<Link to="/"><Button>Go to home</Button></Link>}>The link may be old, or you may not have access to it.</EmptyState></Card>
}

/** Full-screen brand splash shown while the session restores. */
export function Splash({ name }: { name?: string }) {
  return (
    <div style={{ minHeight: '100%', display: 'grid', placeItems: 'center' }} role="status" aria-label="Loading">
      <div className="stack tight" style={{ alignItems: 'center' }}>
        <div className="brand" style={{ border: 0, padding: 0, height: 'auto' }}><span className="mark"><Icon name="droplet" size={17} /></span>{name && <span className="word">{name}</span>}</div>
        <div className="spinner" style={{ margin: '12px auto 0' }} />
      </div>
    </div>
  )
}

/** Watches the browser's connection so pages can say when they are offline instead of failing silently. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(typeof navigator === 'undefined' ? true : navigator.onLine)
  useEffect(() => {
    const on = () => setOnline(true); const off = () => setOnline(false)
    window.addEventListener('online', on); window.addEventListener('offline', off)
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off) }
  }, [])
  return online
}

export function useDebounced<T>(value: T, ms = 250): T {
  const [v, setV] = useState(value)
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t) }, [value, ms])
  return v
}

/** Warns before leaving the page while a form has unsaved changes. */
export function useUnsavedWarning(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return
    const h = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])
}

/** A bar that sticks to the bottom of the page while there are unsaved changes. */
export function SaveBar({ dirty, busy, onSave, onDiscard, text = 'You have unsaved changes' }: { dirty: boolean; busy?: boolean; onSave: () => void; onDiscard: () => void; text?: string }) {
  useUnsavedWarning(dirty)
  if (!dirty) return null
  return (
    <div className="savebar" role="region" aria-label="Unsaved changes">
      <Icon name="info" /><span className="grow">{text}</span>
      <Button small onClick={onDiscard} disabled={busy}>Discard</Button>
      <Button small variant="primary" onClick={onSave} busy={busy}>Save changes</Button>
    </div>
  )
}

export function downloadText(filename: string, text: string, type = 'text/csv') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function toCsv(rows: (string | number | null | undefined)[][]): string {
  return rows.map((r) => r.map((v) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }).join(',')).join('\n')
}
