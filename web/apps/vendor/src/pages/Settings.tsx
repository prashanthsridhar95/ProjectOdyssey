import { useState } from 'react'
import { messageOf, useLabels, useSession } from '@odyssey/api'
import type { Location, Provider } from '@odyssey/api'
import { Button, Card, ErrorNote, Input, Notice, PageHeader, Select, SwitchRow, useConfirm, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

export function SettingsPage({ provider, onChanged, owner }: { provider: Provider; onChanged: () => void; owner: boolean }) {
  const labels = useLabels()
  return (
    <>
      <PageHeader title="Settings" subtitle="Hours, capacity and how bookings come in. Owners also manage the PIN and bank account here." />
      <div className="stack">
        {provider.locations.map((l) => <LocationCard key={l.id} location={l} title={`${labels.location}: ${l.name}`} onSaved={onChanged} />)}
        {owner && <PinCard />}
        {owner && <BankCard provider={provider} onSaved={onChanged} />}
      </div>
    </>
  )
}

function LocationCard({ location, title, onSaved }: { location: Location; title: string; onSaved: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const [busy, setBusy] = useState(false)
  const [f, setF] = useState({ openTime: location.openTime, closeTime: location.closeTime, slotMinutes: String(location.slotMinutes), capacity: String(location.capacity), autoAccept: location.autoAccept, open: location.open })
  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (f.openTime >= f.closeTime) { toast('Closing time must be after opening time', true); return }
    if (location.open && !f.open && !(await confirm({ title: `Pause bookings at ${location.name}?`, tone: 'danger', confirmLabel: 'Pause bookings', body: 'Customers stop seeing you in search until you turn this back on. Bookings already made are not affected.' }))) return
    setBusy(true)
    try {
      await api.provider.updateLocation(location.id, { openTime: f.openTime, closeTime: f.closeTime, slotMinutes: parseInt(f.slotMinutes), capacity: parseInt(f.capacity), autoAccept: f.autoAccept, open: f.open })
      toast(`${location.name} saved`); onSaved()
    } catch (err) { toast(messageOf(err), true) } finally { setBusy(false) }
  }
  const dirty = f.openTime !== location.openTime || f.closeTime !== location.closeTime || f.slotMinutes !== String(location.slotMinutes) || f.capacity !== String(location.capacity) || f.autoAccept !== location.autoAccept || f.open !== location.open
  return (
    <Card>
      <div className="row spread"><h2>{title}</h2><span className="muted small">{location.address}</span></div>
      <form className="stack" onSubmit={save} style={{ marginTop: 12 }}>
        <div className="grid cols-4">
          <Input label="Opens" type="time" value={f.openTime} onChange={(e) => setF({ ...f, openTime: e.target.value })} />
          <Input label="Closes" type="time" value={f.closeTime} onChange={(e) => setF({ ...f, closeTime: e.target.value })} />
          <Select label="Slot length" value={f.slotMinutes} onChange={(e) => setF({ ...f, slotMinutes: e.target.value })} hint="How often a booking can start">{[15, 20, 30, 45, 60].map((m) => <option key={m} value={m}>Every {m} minutes</option>)}</Select>
          <Select label="Bays" value={f.capacity} onChange={(e) => setF({ ...f, capacity: e.target.value })} hint="Cars you can wash at the same time">{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n} {n === 1 ? 'bay' : 'bays'}</option>)}</Select>
        </div>
        <div>
          <SwitchRow title="Taking bookings" description="Turn off to pause online bookings, for example during renovation." checked={f.open} onChange={(v) => setF({ ...f, open: v })} />
          <SwitchRow title="Accept bookings automatically" description="New bookings are confirmed without waiting for you. Turn off to accept each one yourself; unanswered bookings are rejected and refunded after a while." checked={f.autoAccept} onChange={(v) => setF({ ...f, autoAccept: v })} />
        </div>
        <div className="row"><Button variant="primary" busy={busy} disabled={!dirty}>Save changes</Button>{dirty && <span className="muted small">Unsaved changes</span>}</div>
      </form>
    </Card>
  )
}

function PinCard() {
  const { api } = useSession()
  const toast = useToast()
  const [f, setF] = useState({ current: '', next: '', confirm: '' })
  const [error, setError] = useState<string | null>(null)
  async function save(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    if (/^(\d)\1{5}$/.test(f.next) || ['123456', '654321', '012345'].includes(f.next)) { setError('Pick a PIN that is harder to guess'); return }
    if (f.next !== f.confirm) { setError('The two new PINs do not match'); return }
    try { await api.provider.setOwnerPin(f.next, f.current || undefined); toast('Owner PIN updated'); setF({ current: '', next: '', confirm: '' }) }
    catch (err) { setError(messageOf(err)) }
  }
  return (
    <Card>
      <h2>Owner PIN</h2>
      <p className="muted small">Needed for prices, staff, bank details and earnings, even when you are signed in. Share it with nobody.</p>
      <form className="stack" onSubmit={save} style={{ marginTop: 12, maxWidth: 360 }}>
        <Input label="Current PIN" type="password" inputMode="numeric" maxLength={6} value={f.current} onChange={(e) => setF({ ...f, current: e.target.value.replace(/\D/g, '') })} hint="Leave blank if you have not set one" />
        <Input label="New 6-digit PIN" type="password" inputMode="numeric" maxLength={6} autoComplete="new-password" value={f.next} onChange={(e) => setF({ ...f, next: e.target.value.replace(/\D/g, '') })} />
        <Input label="New PIN again" type="password" inputMode="numeric" maxLength={6} autoComplete="new-password" value={f.confirm} onChange={(e) => setF({ ...f, confirm: e.target.value.replace(/\D/g, '') })} />
        <ErrorNote message={error} />
        <div><Button variant="primary" disabled={f.next.length !== 6 || f.confirm.length !== 6}>Update PIN</Button></div>
      </form>
    </Card>
  )
}

function BankCard({ provider, onSaved }: { provider: Provider; onSaved: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const withPin = useWithPin()
  const [f, setF] = useState({ accountName: provider.bank?.accountName ?? '', accountNumber: '', confirm: '', ifsc: provider.bank?.ifsc ?? '' })
  const [error, setError] = useState<string | null>(null)
  async function save(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    if (!/^\d{9,18}$/.test(f.accountNumber)) { setError('Account numbers are 9 to 18 digits'); return }
    if (f.accountNumber !== f.confirm) { setError('The two account numbers do not match'); return }
    if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(f.ifsc)) { setError('An IFSC looks like HDFC0001234: 4 letters, a zero, then 6 letters or digits'); return }
    try {
      const r = await withPin((pin) => api.provider.saveBank({ accountName: f.accountName.trim(), accountNumber: f.accountNumber, ifsc: f.ifsc }, pin), 'Owner PIN to change bank details')
      if (r) { toast('Bank details saved. Payouts go to this account from now on.'); setF({ ...f, accountNumber: '', confirm: '' }); onSaved() }
    } catch (err) { setError(messageOf(err)) }
  }
  return (
    <Card>
      <h2>Bank account for payouts</h2>
      {provider.bank ? <p className="muted small">Payouts go to <span className="mono">{provider.bank.accountNumberMasked}</span> · {provider.bank.ifsc} ({provider.bank.accountName}).</p> : <div style={{ marginTop: 10 }}><Notice tone="warn">No bank account yet. Add one so your earnings can be paid out.</Notice></div>}
      <form className="stack" onSubmit={save} style={{ marginTop: 12, maxWidth: 420 }}>
        <Input label="Account holder" required value={f.accountName} onChange={(e) => setF({ ...f, accountName: e.target.value })} />
        <Input label="Account number" required inputMode="numeric" autoComplete="off" value={f.accountNumber} onChange={(e) => setF({ ...f, accountNumber: e.target.value.replace(/\D/g, '') })} />
        <Input label="Account number again" required inputMode="numeric" autoComplete="off" value={f.confirm} onPaste={(e) => e.preventDefault()} onChange={(e) => setF({ ...f, confirm: e.target.value.replace(/\D/g, '') })} hint="Type it again to avoid a payout to the wrong account" />
        <Input label="IFSC" required maxLength={11} value={f.ifsc} onChange={(e) => setF({ ...f, ifsc: e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '') })} />
        <ErrorNote message={error} />
        <div><Button variant="primary" icon="lock">{provider.bank ? 'Change bank account' : 'Save bank account'}</Button></div>
      </form>
    </Card>
  )
}
