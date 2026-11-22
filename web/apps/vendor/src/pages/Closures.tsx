import { useState } from 'react'
import { dateTime, istInstant, isoDate, messageOf, titleCase, useAsync, useSession } from '@odyssey/api'
import type { Provider } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, PageHeader, Select, Spinner, Table, useToast } from '@odyssey/ui'

/** Time the outlet is closed: one-off or repeating, for the whole outlet or only some bays. Online customers stop seeing those slots. */
export function ClosuresPage({ provider }: { provider: Provider }) {
  const { api } = useSession()
  const toast = useToast()
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const loc = provider.locations.find((l) => l.id === locationId)
  const { data, error, loading, reload } = useAsync(() => api.provider.closures(locationId), [locationId])
  const [f, setF] = useState({ date: isoDate(), from: '13:00', to: '14:00', reason: '', repeat: 'NONE', bays: [] as number[] })
  const [formError, setFormError] = useState<string | null>(null)
  const toggleBay = (b: number) => setF({ ...f, bays: f.bays.includes(b) ? f.bays.filter((x) => x !== b) : [...f.bays, b] })

  async function add(e: React.FormEvent) {
    e.preventDefault(); setFormError(null)
    try {
      await api.provider.addClosure(locationId, { startsAt: istInstant(f.date, f.from), endsAt: istInstant(f.date, f.to), reason: f.reason, repeat: f.repeat, bays: f.bays.length ? f.bays : undefined })
      toast('Closure added'); reload()
    } catch (err) { setFormError(messageOf(err)) }
  }
  async function remove(id: string) { try { await api.provider.removeBlock(locationId, id); reload() } catch (e) { toast(messageOf(e), true) } }

  return (
    <>
      <PageHeader title="Closures" subtitle="Lunch breaks, maintenance and holidays" actions={provider.locations.length > 1 ? <Select label="Outlet" value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select> : undefined} />
      <div className="grid cols-2">
        <form onSubmit={add}>
          <Card className="stack">
            <h2>Add closure</h2>
            <Input label="Date" type="date" required value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />
            <div className="grid cols-2"><Input label="From" type="time" required value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /><Input label="To" type="time" required value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} /></div>
            <Select label="Repeats" value={f.repeat} onChange={(e) => setF({ ...f, repeat: e.target.value })}><option value="NONE">Once</option><option value="DAILY">Every day</option><option value="WEEKLY">Every week</option></Select>
            <div className="stack tight"><span className="small" style={{ fontWeight: 600 }}>Bays closed</span>
              <div className="row wrap">{Array.from({ length: loc?.capacity ?? 1 }, (_, i) => i + 1).map((b) => <label className="check" key={b}><input type="checkbox" checked={f.bays.includes(b)} onChange={() => toggleBay(b)} />Bay {b}</label>)}</div>
              <span className="muted small">None selected closes the whole outlet.</span></div>
            <Input label="Reason" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} placeholder="Lunch break" />
            <ErrorNote message={formError} />
            <div><Button variant="primary">Add closure</Button></div>
          </Card>
        </form>
        <Card flush>
          <div style={{ padding: '16px 18px 4px' }}><h2>Upcoming and repeating</h2></div>
          <ErrorNote message={error} />
          {loading ? <Spinner /> : !data?.length ? <Empty>No closures.</Empty> : (
            <Table head={['When', 'Bays', 'Repeats', 'Reason', '']}>
              {data.map((c) => <tr key={c.id}><td className="nowrap">{dateTime(c.startsAt)}</td><td>{c.bays?.length ? c.bays.join(', ') : <Badge>All</Badge>}</td><td>{c.repeat === 'NONE' ? 'Once' : titleCase(c.repeat)}</td><td>{c.reason}</td><td><Button small variant="danger" onClick={() => remove(c.id)}>Remove</Button></td></tr>)}
            </Table>
          )}
        </Card>
      </div>
    </>
  )
}
