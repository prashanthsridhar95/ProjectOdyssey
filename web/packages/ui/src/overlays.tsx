import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { Button, IconButton, TextArea } from './components'
import { Icon, type IconName } from './icons'

/** Keeps keyboard focus inside a dialog and gives it back to what opened it on close. */
function useFocusTrap(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  const close = useRef(onClose); close.current = onClose
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    const el = ref.current
    const first = el?.querySelector<HTMLElement>('[autofocus], input:not([type=hidden]), select, textarea, button.primary, button')
    first?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); close.current() }
      if (e.key !== 'Tab' || !el) return
      const items = Array.from(el.querySelectorAll<HTMLElement>('a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'))
      if (!items.length) return
      const a = items[0]!, z = items[items.length - 1]!
      if (e.shiftKey && document.activeElement === a) { e.preventDefault(); z.focus() } else if (!e.shiftKey && document.activeElement === z) { e.preventDefault(); a.focus() }
    }
    document.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = prevOverflow; before?.focus?.() }
  }, [])
  return ref
}

export function Modal({ title, description, onClose, children, footer, wide, icon, tone }: {
  title: string; description?: ReactNode; onClose: () => void; children?: ReactNode; footer?: ReactNode; wide?: boolean; icon?: IconName; tone?: 'bad' | 'warn' | 'info'
}) {
  const ref = useFocusTrap(onClose)
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="mhead">
          <div className="row top">
            {icon && <span className={`tone-icon ${tone ?? 'info'}`}><Icon name={icon} size={18} /></span>}
            <div><h2>{title}</h2>{description && <p className="muted small">{description}</p>}</div>
          </div>
          <IconButton icon="x" label="Close" onClick={onClose} />
        </div>
        {children && <div className="mbody">{children}</div>}
        {footer && <div className="mfoot">{footer}</div>}
      </div>
    </div>
  )
}

/** A side panel for the details of one record, so the list stays in view behind it. */
export function Drawer({ title, subtitle, onClose, children, footer, meta }: { title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; meta?: ReactNode }) {
  const ref = useFocusTrap(onClose)
  return (
    <>
      <div className="drawer-overlay" onMouseDown={onClose} />
      <aside ref={ref} className="drawer" role="dialog" aria-modal="true" aria-label={typeof title === 'string' ? title : 'Details'}>
        <div className="dhead">
          <div className="grow stack xtight"><h2>{title}</h2>{subtitle && <div className="muted small">{subtitle}</div>}{meta && <div className="row tight wrap" style={{ marginTop: 6 }}>{meta}</div>}</div>
          <IconButton icon="x" label="Close" onClick={onClose} />
        </div>
        <div className="dbody">{children}</div>
        {footer && <div className="dfoot">{footer}</div>}
      </aside>
    </>
  )
}

// ---- confirm ----

export interface ConfirmOptions {
  title: string
  body?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'danger' | 'primary'
  /** Ask for a written reason; the dialog will not confirm without one when required. */
  reason?: { label: string; placeholder?: string; required?: boolean; hint?: string }
}
type ConfirmFn = (o: ConfirmOptions) => Promise<{ reason: string } | null>
const ConfirmCtx = createContext<ConfirmFn>(async () => null)
/** `const ok = await confirm({...})` — null when cancelled, else `{ reason }`. */
export const useConfirm = () => useContext(ConfirmCtx)

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState<(ConfirmOptions & { resolve: (r: { reason: string } | null) => void }) | null>(null)
  const confirm = useCallback<ConfirmFn>((o) => new Promise((resolve) => setOpen({ ...o, resolve })), [])
  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      {open && <ConfirmDialog o={open} done={(r) => { open.resolve(r); setOpen(null) }} />}
    </ConfirmCtx.Provider>
  )
}

function ConfirmDialog({ o, done }: { o: ConfirmOptions; done: (r: { reason: string } | null) => void }) {
  const [reason, setReason] = useState('')
  const danger = o.tone === 'danger'
  const blocked = !!o.reason?.required && !reason.trim()
  return (
    <Modal title={o.title} onClose={() => done(null)} icon={danger ? 'alert' : 'info'} tone={danger ? 'bad' : 'info'}
      footer={<><Button onClick={() => done(null)}>{o.cancelLabel ?? 'Cancel'}</Button><Button variant={danger ? 'danger' : 'primary'} solid={danger} disabled={blocked} onClick={() => done({ reason: reason.trim() })}>{o.confirmLabel ?? 'Confirm'}</Button></>}>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!blocked) done({ reason: reason.trim() }) }}>
        {o.body && <div className="muted">{o.body}</div>}
        {o.reason && <TextArea label={o.reason.label} required={o.reason.required} hint={o.reason.hint} placeholder={o.reason.placeholder} value={reason} autoFocus onChange={(e) => setReason(e.target.value)} />}
      </form>
    </Modal>
  )
}

/** Asks for the Owner PIN. Used before price, staff, bank and earnings actions. */
export function PinPrompt({ title = 'Enter Owner PIN', onSubmit, onClose, error }: { title?: string; onSubmit: (pin: string) => void; onClose: () => void; error?: string | null }) {
  const [pin, setPin] = useState('')
  const [shown, setShown] = useState(false)
  return (
    <Modal title={title} description="This action is protected. Enter your 6-digit Owner PIN." onClose={onClose} icon="lock" tone="info"
      footer={<><Button type="button" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="pin-form" disabled={pin.length !== 6}>Confirm</Button></>}>
      <form id="pin-form" className="stack" onSubmit={(e) => { e.preventDefault(); if (pin.length === 6) { onSubmit(pin); setPin('') } }}>
        <div className="inputwrap">
          <Icon name="lock" size={15} />
          <input aria-label="Owner PIN" type={shown ? 'text' : 'password'} inputMode="numeric" autoFocus maxLength={6} value={pin} autoComplete="off" style={{ letterSpacing: '.4em', fontSize: 18 }}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
          <span className="clear"><IconButton icon={shown ? 'eyeOff' : 'eye'} label={shown ? 'Hide PIN' : 'Show PIN'} onClick={() => setShown(!shown)} /></span>
        </div>
        {error && <div className="alert" role="alert"><Icon name="alert" />{error}</div>}
        <p className="faint small">We keep the PIN for one minute so a few protected changes in a row need it only once. It is never stored.</p>
      </form>
    </Modal>
  )
}

// ---- toasts ----

interface ToastItem { id: number; text: string; bad?: boolean }
const ToastCtx = createContext<(text: string, bad?: boolean) => void>(() => undefined)
/** `toast('Saved')`, or `toast(message, true)` for an error. */
export const useToast = () => useContext(ToastCtx)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const remove = useCallback((id: number) => setItems((l) => l.filter((t) => t.id !== id)), [])
  const push = useCallback((text: string, bad?: boolean) => {
    const id = Date.now() + Math.random()
    setItems((l) => [...l.slice(-3), { id, text, bad }])
    setTimeout(() => remove(id), bad ? 7000 : 4000)
  }, [remove])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite" role="status">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.bad ? 'bad' : 'good'}`}>
            <Icon name={t.bad ? 'alert' : 'checkCircle'} /><span>{t.text}</span>
            <button className="x" aria-label="Dismiss" onClick={() => remove(t.id)}><Icon name="x" size={14} /></button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}
