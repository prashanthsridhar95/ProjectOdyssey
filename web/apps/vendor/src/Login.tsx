import { useState } from 'react'
import { messageOf, useSession } from '@odyssey/api'
import { Button, Card, ErrorNote, Input } from '@odyssey/ui'

/** Owners sign in with phone + OTP. Counter staff sign in on the shared device with phone + 4-digit PIN. */
export function Login() {
  const { api, login, config } = useSession()
  const labels = config?.config.labels
  const [mode, setMode] = useState<'owner' | 'staff'>('owner')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null)
    try {
      if (mode === 'staff') { const r = await api.auth.staffPin(phone, code); login(r.user, r.accessToken) }
      else if (!sent) { await api.auth.requestOtp(phone); setSent(true) }
      else { const r = await api.auth.verifyOtp(phone, code); login(r.user, r.accessToken) }
    } catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }
  const switchMode = (m: 'owner' | 'staff') => { setMode(m); setSent(false); setCode(''); setError(null) }

  return (
    <div className="login">
      <Card>
        <form className="stack" onSubmit={submit}>
          <div><h1>{config?.tenant.name ?? 'Odyssey'}</h1><p className="muted">{labels?.provider ?? 'Vendor'} sign in</p></div>
          <div className="row">
            <Button type="button" variant={mode === 'owner' ? 'primary' : undefined} onClick={() => switchMode('owner')}>Owner</Button>
            <Button type="button" variant={mode === 'staff' ? 'primary' : undefined} onClick={() => switchMode('staff')}>Counter staff</Button>
          </div>
          <Input label="Phone number" type="tel" autoFocus value={phone} onChange={(e) => setPhone(e.target.value)} disabled={sent} placeholder="+91…" />
          {mode === 'staff' && <Input label="4-digit PIN" type="password" inputMode="numeric" maxLength={4} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />}
          {mode === 'owner' && sent && <Input label="One-time code" inputMode="numeric" autoFocus value={code} onChange={(e) => setCode(e.target.value)} hint="Local dev code: 123456" />}
          <ErrorNote message={error} />
          <Button variant="primary" disabled={busy || !phone || (mode === 'staff' && code.length !== 4) || (mode === 'owner' && sent && !code)}>{mode === 'owner' && !sent ? 'Send code' : 'Sign in'}</Button>
        </form>
      </Card>
    </div>
  )
}
