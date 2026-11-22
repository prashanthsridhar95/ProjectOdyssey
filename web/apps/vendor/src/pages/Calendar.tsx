import { useState } from 'react'
import { isoDate, istInstant, messageOf, timeOf, useAsync, useSession } from '@odyssey/api'
import type { Provider, Slot } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Select, Spinner, useToast } from '@odyssey/ui'

export function CalendarPage({ provider }: { provider: Provider }) {
  const { api, config } = useSession()
  const toast = useToast()
  const [date, setDate] = useState(isoDate(0))
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [picked, setPicked] = useState<Slot | null>(null)
  const [walkIn, setWalkIn] = useState(false)
  const [range, setRange] = useState({ from: '12:00', to: '13:00', reason: 'Break' })
  const location = provider.locations.find((l) => l.id === locationId)
  const slots = useAsync(() => api.provider.slots(locationId, date), [locationId, date])
  const blocks = useAsync(() => api.provider.blocks(locationId, date), [locationId, date])
  const items = useAsync(() => api.provider.items(locationId), [locationId])
  const reloadAll = () => { slots.reload(); blocks.reload() }

  const isBlocked = (s: Slot) => (blocks.data ?? []).some((b) => new Date(b.startsAt) < new Date(s.endsAt) && new Date(b.endsAt) > new Date(s.startsAt))

  async function addBlock(from: string, to: string, reason: string) {
    try { await api.provider.addBlock(locationId, from, to, reason); toast('Blocked'); setPicked(null); reloadAll() }
    catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Slots" subtitle="Block time for breaks or maintenance, or add a walk-in so the calendar stays true" actions={<>
        {provider.locations.length > 1 && <Select label="Outlet" value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
        <Input label="Day" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </>} />
      <ErrorNote message={slots.error ?? blocks.error} />
      <Card>
        <div className="row spread" style={{ marginBottom: 12 }}>
          <h2>{location?.name} · {location?.capacity} at a time</h2>
          <span className="muted small">Tap a slot for options</span>
        </div>
        {slots.loading ? <Spinner /> : !slots.data?.length ? <Empty>Closed on this day.</Empty> : (
          <div className="slotgrid">
            {slots.data.map((s) => {
              const blocked = isBlocked(s)
              const state = blocked ? 'blocked' : s.free === 0 ? 'full' : ''
              return (
                <button key={s.startsAt} className={`slot ${state} ${picked?.startsAt === s.startsAt ? 'sel' : ''}`} onClick={() => setPicked(s)}>
                  <div className="t">{timeOf(s.startsAt)}</div>
                  <div className="f">{blocked ? 'Blocked' : s.free === 0 ? 'Full' : !s.bookable ? 'Not open' : `${s.free} free`}</div>
                </button>
              )
            })}
          </div>
        )}
      </Card>
      <div style={{ height: 16 }} />
      <div className="grid cols-2">
        <Card>
          <h2>Block a time range</h2>
          <form className="stack" style={{ marginTop: 12 }} onSubmit={(e) => { e.preventDefault(); void addBlock(istInstant(date, range.from), istInstant(date, range.to), range.reason) }}>
            <div className="row"><Input label="From" type="time" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /><Input label="To" type="time" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></div>
            <Input label="Reason" value={range.reason} onChange={(e) => setRange({ ...range, reason: e.target.value })} />
            <p className="muted small">A range with existing bookings cannot be blocked. Cancel those bookings first, with a reason.</p>
            <Button variant="primary">Block</Button>
          </form>
        </Card>
        <Card>
          <h2>Blocked today</h2>
          <div className="stack tight" style={{ marginTop: 12 }}>
            {!blocks.data?.length && <p className="muted">Nothing blocked.</p>}
            {blocks.data?.map((b) => (
              <div key={b.id} className="row spread">
                <span>{timeOf(b.startsAt)} – {timeOf(b.endsAt)} <span className="muted">{b.reason}</span></span>
                <Button small onClick={async () => { try { await api.provider.removeBlock(locationId, b.id); reloadAll() } catch (e) { toast(messageOf(e), true) } }}>Reopen</Button>
              </div>
            ))}
          </div>
        </Card>
      </div>

      {picked && !walkIn && (
        <Modal title={`${timeOf(picked.startsAt)} · ${date}`} onClose={() => setPicked(null)}>
          <div className="stack">
            <p>{isBlocked(picked) ? <Badge tone="warn">Blocked</Badge> : picked.free === 0 ? <Badge tone="bad">Full</Badge> : <Badge tone="good">{picked.free} free</Badge>}</p>
            {!isBlocked(picked) && <Button onClick={() => addBlock(picked.startsAt, picked.endsAt, 'Blocked from calendar')}>Block this slot</Button>}
            {picked.bookable && <Button variant="primary" onClick={() => setWalkIn(true)}>Add a walk-in</Button>}
          </div>
        </Modal>
      )}
      {picked && walkIn && <WalkInModal slot={picked} locationId={locationId} items={items.data ?? []} variants={config?.config.variants.options ?? []} onClose={() => { setWalkIn(false); setPicked(null) }} onDone={() => { setWalkIn(false); setPicked(null); reloadAll() }} />}
    </>
  )
}

function WalkInModal({ slot, locationId, items, variants, onClose, onDone }: {
  slot: Slot; locationId: string; items: { id: string; name: string; active: boolean; prices: Record<string, number> }[]
  variants: { key: string; label: string }[]; onClose: () => void; onDone: () => void
}) {
  const { api } = useSession()
  const toast = useToast()
  const active = items.filter((i) => i.active)
  const [itemId, setItemId] = useState(active[0]?.id ?? '')
  const [variant, setVariant] = useState(variants[0]?.key ?? '')
  const [name, setName] = useState('')
  async function submit(e: React.FormEvent) {
    e.preventDefault()
    try { await api.provider.walkIn({ locationId, itemIds: [itemId], variantKey: variant, startsAt: slot.startsAt, customerName: name || 'Walk-in' }); toast('Walk-in added'); onDone() }
    catch (err) { toast(messageOf(err), true) }
  }
  return (
    <Modal title={`Walk-in at ${timeOf(slot.startsAt)}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Select label="Service" value={itemId} onChange={(e) => setItemId(e.target.value)}>{active.filter((i) => i.prices[variant] !== undefined).map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}</Select>
        <Select label="Vehicle type" value={variant} onChange={(e) => setVariant(e.target.value)}>{variants.map((v) => <option key={v.key} value={v.key}>{v.label}</option>)}</Select>
        <Input label="Customer name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Walk-in" />
        <p className="muted small">Payment is taken at the counter. This does not go through the platform.</p>
        <div className="row spread"><Button type="button" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!itemId}>Add</Button></div>
      </form>
    </Modal>
  )
}
