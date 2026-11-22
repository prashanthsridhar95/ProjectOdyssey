import { useState } from 'react'
import { messageOf, useSession } from '@odyssey/api'
import { Button, Card, ErrorNote, Input } from '@odyssey/ui'

/** Phone + OTP sign-in. The identity provider behind it is swappable (dev code today, our IAM product later). */
export function Login() {
  const { api, login, config } = useSession()
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null)
    try {
      if (!sent) { await api.auth.requestOtp(phone); setSent(true) }
      else { const r = await api.auth.verifyOtp(phone, code); login(r.user, r.accessToken) }
    } catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }

  return (
    <div className="login">
      <Card>
        <form className="stack" onSubmit={submit}>
          <div><h1>{config?.tenant.name ?? 'Odyssey'}</h1><p className="muted">Admin sign in</p></div>
          <Input label="Phone number" type="tel" autoFocus value={phone} onChange={(e) => setPhone(e.target.value)} disabled={sent} placeholder="+91…" />
          {sent && <Input label="One-time code" inputMode="numeric" autoFocus value={code} onChange={(e) => setCode(e.target.value)} hint="Local dev code: 123456" />}
          <ErrorNote message={error} />
          <Button variant="primary" disabled={busy || !phone || (sent && !code)}>{sent ? 'Sign in' : 'Send code'}</Button>
        </form>
      </Card>
    </div>
  )
}
