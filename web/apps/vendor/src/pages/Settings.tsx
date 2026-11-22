import { useState } from 'react'
import { messageOf, useLabels, useSession } from '@odyssey/api'
import type { Location, Provider } from '@odyssey/api'
import { Button, Card, ErrorNote, Input, PageHeader, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

export function SettingsPage({ provider, onChanged, owner }: { provider: Provider; onChanged: () => void; owner: boolean }) {
  const labels = useLabels()
  return (
    <>
      <PageHeader title="Settings" />
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
  const [f, setF] = useState({ openTime: location.openTime, closeTime: location.closeTime, slotMinutes: String(location.slotMinutes), capacity: String(location.capacity), autoAccept: location.autoAccept, open: location.open })
  async function save(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api.provider.updateLocation(location.id, { openTime: f.openTime, closeTime: f.closeTime, slotMinutes: parseInt(f.slotMinutes), capacity: parseInt(f.capacity), autoAccept: f.autoAccept, open: f.open })
      toast('Saved'); onSaved()
    } catch (err) { toast(messageOf(err), true) }
  }
  return (
    <Card>
      <h2>{title}</h2>
      <form className="stack" onSubmit={save} style={{ marginTop: 12 }}>
        <div className="grid cols-4">
          <Input label="Opens" type="time" value={f.openTime} onChange={(e) => setF({ ...f, openTime: e.target.value })} />
          <Input label="Closes" type="time" value={f.closeTime} onChange={(e) => setF({ ...f, closeTime: e.target.value })} />
          <Input label="Slot length (min)" type="number" min="10" step="5" value={f.slotMinutes} onChange={(e) => setF({ ...f, slotMinutes: e.target.value })} />
          <Input label="Bays at a time" type="number" min="1" value={f.capacity} onChange={(e) => setF({ ...f, capacity: e.target.value })} />
        </div>
        <label className="check"><input type="checkbox" checked={f.autoAccept} onChange={(e) => setF({ ...f, autoAccept: e.target.checked })} /> Accept bookings automatically</label>
        <label className="check"><input type="checkbox" checked={f.open} onChange={(e) => setF({ ...f, open: e.target.checked })} /> Open for bookings</label>
        <div><Button variant="primary">Save</Button></div>
      </form>
    </Card>
  )
}

function PinCard() {
  const { api } = useSession()
  const toast = useToast()
  const [f, setF] = useState({ current: '', next: '' })
  const [error, setError] = useState<string | null>(null)
  async function save(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    try { await api.provider.setOwnerPin(f.next, f.current || undefined); toast('Owner PIN updated'); setF({ current: '', next: '' }) }
    catch (err) { setError(messageOf(err)) }
  }
  return (
    <Card>
      <h2>Owner PIN</h2>
      <p className="muted small">Needed for prices, staff, bank details and earnings, even when you are signed in. Share it with nobody.</p>
      <form className="stack" onSubmit={save} style={{ marginTop: 12, maxWidth: 360 }}>
        <Input label="Current PIN" type="password" inputMode="numeric" maxLength={6} value={f.current} onChange={(e) => setF({ ...f, current: e.target.value.replace(/\D/g, '') })} hint="Leave blank if you have not set one" />
        <Input label="New 6-digit PIN" type="password" inputMode="numeric" maxLength={6} value={f.next} onChange={(e) => setF({ ...f, next: e.target.value.replace(/\D/g, '') })} />
        <ErrorNote message={error} />
        <div><Button variant="primary" disabled={f.next.length !== 6}>Update PIN</Button></div>
      </form>
    </Card>
  )
}

function BankCard({ provider, onSaved }: { provider: Provider; onSaved: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const withPin = useWithPin()
  const [f, setF] = useState({ accountName: provider.bank?.accountName ?? '', accountNumber: '', ifsc: provider.bank?.ifsc ?? '' })
  async function save(e: React.FormEvent) {
    e.preventDefault()
    try { const r = await withPin((pin) => api.provider.saveBank(f, pin), 'Owner PIN to change bank details'); if (r) { toast('Bank details saved'); onSaved() } }
    catch (err) { toast(messageOf(err), true) }
  }
  return (
    <Card>
      <h2>Bank account for payouts</h2>
      <p className="muted small">Current: {provider.bank ? `${provider.bank.accountNumberMasked} · ${provider.bank.ifsc}` : 'none'}</p>
      <form className="stack" onSubmit={save} style={{ marginTop: 12, maxWidth: 420 }}>
        <Input label="Account holder" required value={f.accountName} onChange={(e) => setF({ ...f, accountName: e.target.value })} />
        <Input label="Account number" required inputMode="numeric" value={f.accountNumber} onChange={(e) => setF({ ...f, accountNumber: e.target.value })} />
        <Input label="IFSC" required value={f.ifsc} onChange={(e) => setF({ ...f, ifsc: e.target.value.toUpperCase() })} />
        <div><Button variant="primary">Save</Button></div>
      </form>
    </Card>
  )
}
