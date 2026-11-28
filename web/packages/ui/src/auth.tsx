import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button, Segmented, usePageTitle } from './components'
import { Icon, type IconName } from './icons'

/** "98450 21734" or "+91 98450 21734" -> "+919845021734". Indian mobile numbers only, for now. */
export function toE164(input: string): string {
  let d = input.replace(/\D/g, '')
  if (d.length === 12 && d.startsWith('91')) d = d.slice(2)
  if (d.length === 11 && d.startsWith('0')) d = d.slice(1)
  return `+91${d}`
}
export function isValidIndianMobile(input: string): boolean {
  return /^\d{10}$/.test(toE164(input).slice(3))
}
function pretty(d: string) { return d.length > 5 ? `${d.slice(0, 5)} ${d.slice(5, 10)}` : d }

export function PhoneInput({ value, onChange, disabled, autoFocus, id }: { value: string; onChange: (v: string) => void; disabled?: boolean; autoFocus?: boolean; id?: string }) {
  return (
    <div className="prefix">
      <span>+91</span>
      <input id={id} type="tel" inputMode="numeric" autoComplete="tel-national" placeholder="98450 21734" value={pretty(value)} disabled={disabled} autoFocus={autoFocus}
        onChange={(e) => { let d = e.target.value.replace(/\D/g, ''); if (d.length > 10 && d.startsWith('91')) d = d.slice(2); onChange(d.slice(0, 10)) }} />
    </div>
  )
}

/** One box per digit. Typing moves on, backspace moves back, and pasting a whole code fills every box. */
export function CodeInput({ length, value, onChange, secret, autoFocus, onComplete, label }: { length: number; value: string; onChange: (v: string) => void; secret?: boolean; autoFocus?: boolean; onComplete?: (v: string) => void; label: string }) {
  const refs = useRef<(HTMLInputElement | null)[]>([])
  useEffect(() => { if (autoFocus) refs.current[0]?.focus() }, [autoFocus])
  function set(next: string) {
    const v = next.replace(/\D/g, '').slice(0, length)
    onChange(v)
    refs.current[Math.min(v.length, length - 1)]?.focus()
    if (v.length === length) onComplete?.(v)
  }
  return (
    <div className="otp" style={{ ['--n' as string]: length }} role="group" aria-label={label}>
      {Array.from({ length }, (_, i) => (
        <input key={i} ref={(el) => { refs.current[i] = el }} aria-label={`${label} digit ${i + 1}`} inputMode="numeric" autoComplete={i === 0 ? 'one-time-code' : 'off'}
          type={secret ? 'password' : 'text'} maxLength={length} value={value[i] ?? ''}
          onFocus={(e) => e.currentTarget.select()}
          onPaste={(e) => { e.preventDefault(); set(e.clipboardData.getData('text')) }}
          onKeyDown={(e) => {
            if (e.key === 'Backspace' && !value[i] && i > 0) { e.preventDefault(); onChange(value.slice(0, i - 1)); refs.current[i - 1]?.focus() }
            else if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus()
            else if (e.key === 'ArrowRight' && i < length - 1) refs.current[i + 1]?.focus()
          }}
          onChange={(e) => {
            const typed = e.target.value.replace(/\D/g, '')
            if (!typed) { onChange(value.slice(0, i) + value.slice(i + 1)); return }
            set((value.slice(0, i) + typed).slice(0, length))
          }} />
      ))}
    </div>
  )
}

function Drawing() {
  // The car in the blueprint style the apps use for artwork.
  return (
    <svg className="drawing" viewBox="0 0 520 220" fill="none" stroke="currentColor" aria-hidden="true">
      <line x1="40" y1="182" x2="480" y2="182" strokeOpacity=".35" />
      <line x1="72" y1="40" x2="448" y2="40" strokeOpacity=".5" /><line x1="72" y1="33" x2="72" y2="47" strokeOpacity=".5" /><line x1="448" y1="33" x2="448" y2="47" strokeOpacity=".5" />
      <text x="260" y="30" fill="currentColor" stroke="none" fontSize="11" textAnchor="middle" opacity=".6" fontFamily="ui-monospace, monospace">4 380 mm</text>
      <path d="M72 158 V128 L110 114 L190 106 L236 68 L340 62 L398 104 L444 112 L448 132 V158 Z" strokeWidth="1.8" />
      <path d="M200 106 L242 74 L330 70 L376 106 Z" strokeOpacity=".6" /><line x1="290" y1="72" x2="290" y2="158" strokeOpacity=".6" />
      <circle cx="160" cy="158" r="24" strokeWidth="1.8" fill="#24384c" /><circle cx="160" cy="158" r="10" strokeOpacity=".6" />
      <circle cx="380" cy="158" r="24" strokeWidth="1.8" fill="#24384c" /><circle cx="380" cy="158" r="10" strokeOpacity=".6" />
      <path d="M258 46 l-4 11 M282 44 l-4 11 M306 46 l-4 11" strokeWidth="1.6" stroke="#eef6ff" strokeOpacity=".7" />
    </svg>
  )
}

export function AuthLayout({ brand, headline, points, children, footnote }: { brand: string; headline: string; points: [IconName, string][]; children: ReactNode; footnote?: string }) {
  return (
    <div className="auth">
      <section className="art" aria-hidden="true">
        <div className="brand-row"><span className="mark"><Icon name="droplet" size={18} /></span>{brand}</div>
        <div>
          <Drawing />
          <h1 style={{ marginTop: 24 }}>{headline}</h1>
          <div className="points">{points.map(([ic, t]) => <div key={t}><Icon name={ic} size={18} /><span>{t}</span></div>)}</div>
        </div>
        <div className="legal">© {new Date().getFullYear()} {brand}. {footnote ?? 'Access is monitored and every change is recorded.'}</div>
      </section>
      <section className="pane"><div className="form">{children}</div></section>
    </div>
  )
}

/**
 * Phone + one-time-code sign-in, optionally with a second "staff PIN" mode. The calls are passed in so the identity
 * provider behind them can change (dev code today, the IAM product later) without touching the screen.
 */
export function SignIn({ brand, audience, headline, points, requestOtp, verifyOtp, staffPin, devHint, describeError }: {
  brand: string
  audience: string
  headline: string
  points: [IconName, string][]
  requestOtp: (phone: string) => Promise<void>
  verifyOtp: (phone: string, code: string) => Promise<void>
  staffPin?: (phone: string, pin: string) => Promise<void>
  devHint?: string
  describeError: (e: unknown) => string
}) {
  usePageTitle(`Sign in · ${brand}`)
  const [mode, setMode] = useState<'otp' | 'staff'>('otp')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [wait, setWait] = useState(0)
  useEffect(() => { if (wait <= 0) return; const t = setTimeout(() => setWait(wait - 1), 1000); return () => clearTimeout(t) }, [wait])

  const phoneOk = isValidIndianMobile(phone)
  const need = mode === 'staff' ? 4 : 6
  async function send() {
    setBusy(true); setError(null)
    try { await requestOtp(toE164(phone)); setSent(true); setCode(''); setWait(30) } catch (e) { setError(describeError(e)) } finally { setBusy(false) }
  }
  async function finish(c = code) {
    if (c.length !== need) return
    setBusy(true); setError(null)
    try { if (mode === 'staff') await staffPin!(toE164(phone), c); else await verifyOtp(toE164(phone), c) }
    catch (e) { setError(describeError(e)); setCode('') } finally { setBusy(false) }
  }
  const step = mode === 'otp' && !sent ? 'phone' : 'code'

  return (
    <AuthLayout brand={brand} headline={headline} points={points}>
      <div className="mobile-brand"><Icon name="droplet" size={20} />{brand}</div>
      <div className="stack tight">
        <h2>{step === 'phone' || mode === 'staff' ? `Sign in to ${audience}` : 'Check your phone'}</h2>
        <p className="muted">{mode === 'staff' ? 'Use the phone number and 4-digit PIN your owner set up for you.' : sent ? <>We sent a 6-digit code to <strong>+91 {pretty(phone)}</strong>.</> : 'We will text you a one-time code. No password needed.'}</p>
      </div>
      {staffPin && <Segmented label="Who is signing in" options={[['otp', 'Owner or manager'], ['staff', 'Counter staff']] as const} value={mode} onChange={(m) => { setMode(m); setSent(false); setCode(''); setError(null) }} />}
      <form className="stack" onSubmit={(e) => { e.preventDefault(); if (step === 'phone') void send(); else void finish() }} noValidate>
        {(step === 'phone' || mode === 'staff') && (
          <label className="field"><span>Mobile number</span><PhoneInput value={phone} onChange={setPhone} autoFocus disabled={busy} /></label>
        )}
        {step === 'code' && (
          <div className="stack tight">
            <span className="small strong">{mode === 'staff' ? '4-digit PIN' : 'One-time code'}</span>
            <CodeInput length={need} value={code} onChange={setCode} secret={mode === 'staff'} autoFocus={mode === 'otp'} label={mode === 'staff' ? 'PIN' : 'Code'} onComplete={mode === 'otp' ? (c) => void finish(c) : undefined} />
            {mode === 'otp' && (
              <div className="row spread small">
                <button type="button" className="btn ghost small" onClick={() => { setSent(false); setCode(''); setError(null) }}><Icon name="arrowLeft" size={13} />Change number</button>
                {wait > 0 ? <span className="muted num">Resend in 0:{String(wait).padStart(2, '0')}</span> : <button type="button" className="btn ghost small" onClick={() => void send()} disabled={busy}>Resend code</button>}
              </div>
            )}
          </div>
        )}
        {error && <div className="alert" role="alert"><Icon name="alert" />{error}</div>}
        <Button variant="primary" big block busy={busy} disabled={step === 'phone' ? !phoneOk : code.length !== need || (mode === 'staff' && !phoneOk)}>
          {step === 'phone' ? 'Send code' : 'Sign in'}{!busy && <Icon name="arrowLeft" size={16} style={{ transform: 'rotate(180deg)' }} />}
        </Button>
        {devHint && <p className="fine"><Icon name="info" size={13} /> {devHint}</p>}
        <p className="fine">By signing in you agree to the platform terms and privacy policy. Trouble signing in? Contact your platform administrator.</p>
      </form>
    </AuthLayout>
  )
}
