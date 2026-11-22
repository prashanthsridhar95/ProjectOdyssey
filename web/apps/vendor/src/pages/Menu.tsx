import { useState } from 'react'
import { messageOf, money, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Item, Provider } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Select, Spinner, Table, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

/** Changing a price, adding or deleting a service needs the Owner PIN. Turning a service on or off does not. */
export function MenuPage({ provider }: { provider: Provider }) {
  const { api, config, user } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const withPin = useWithPin()
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [editing, setEditing] = useState<Partial<Item> | null>(null)
  const { data, error, loading, reload } = useAsync(() => api.provider.items(locationId), [locationId])
  const variants = config?.config.variants.options ?? []
  const owner = user?.role === 'PROVIDER_OWNER'

  async function toggle(item: Item) {
    try { await api.provider.saveItem(locationId, { ...item, active: !item.active }); reload() } catch (e) { toast(messageOf(e), true) }
  }
  async function remove(item: Item) {
    if (!window.confirm(`Delete ${item.name}?`)) return
    try { await withPin((pin) => api.provider.deleteItem(locationId, item.id, pin), 'Owner PIN to delete'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Menu" subtitle={`Your ${labels.item.toLowerCase()}s and prices by ${config?.config.variants.name.toLowerCase()}`} actions={<>
        {provider.locations.length > 1 && <Select label={labels.location} value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
        {owner && <Button variant="primary" onClick={() => setEditing({ active: true, durationMinutes: 30, prices: {} })}>Add {labels.item.toLowerCase()}</Button>}
      </>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No {labels.item.toLowerCase()}s yet.</Empty> : (
          <Table head={[labels.item, 'Time', ...variants.map((v) => v.label), 'On', '']}>
            {data.map((i) => (
              <tr key={i.id}>
                <td><strong>{i.name}</strong><div className="muted small">{i.category}</div></td>
                <td className="nowrap">{i.durationMinutes} min</td>
                {variants.map((v) => <td key={v.key}>{i.prices[v.key] === undefined ? <span className="muted">-</span> : money(i.prices[v.key])}</td>)}
                <td>{i.active ? <Badge tone="good">on</Badge> : <Badge>off</Badge>} <Button small onClick={() => toggle(i)} style={{ marginLeft: 6 }}>{i.active ? 'Turn off' : 'Turn on'}</Button></td>
                <td className="right nowrap">
                  <Button small onClick={() => setEditing(i)}>Edit</Button>{' '}
                  {owner && <Button small variant="danger" onClick={() => remove(i)}>Delete</Button>}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
      {editing && <ItemModal locationId={locationId} item={editing} owner={owner} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
    </>
  )
}

function ItemModal({ locationId, item, owner, onClose, onSaved }: { locationId: string; item: Partial<Item>; owner: boolean; onClose: () => void; onSaved: () => void }) {
  const { api, config } = useSession()
  const withPin = useWithPin()
  const toast = useToast()
  const variants = config?.config.variants.options ?? []
  const templates = config?.config.serviceTemplates ?? []
  const [f, setF] = useState({
    name: item.name ?? '', description: item.description ?? '', category: item.category ?? '', duration: String(item.durationMinutes ?? 30), active: item.active ?? true,
    prices: Object.fromEntries(variants.map((v) => [v.key, item.prices?.[v.key] === undefined ? '' : String(item.prices[v.key] / 100)])) as Record<string, string>,
  })
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    const prices: Record<string, number> = {}
    for (const v of variants) if (f.prices[v.key] !== '') prices[v.key] = Math.round(parseFloat(f.prices[v.key]) * 100)
    if (!Object.keys(prices).length) { setError('Add at least one price'); return }
    const body = { id: item.id, name: f.name, description: f.description, category: f.category, durationMinutes: parseInt(f.duration), active: f.active, prices }
    const priceChanged = JSON.stringify(Object.entries(prices).sort()) !== JSON.stringify(Object.entries(item.prices ?? {}).sort())
    try {
      if (!item.id || priceChanged) {
        if (!owner) { setError('Only the owner can change prices'); return }
        await withPin((pin) => api.provider.saveItem(locationId, body, pin), 'Owner PIN to change prices')
      } else await api.provider.saveItem(locationId, body)
      toast('Saved'); onSaved()
    } catch (err) { setError(messageOf(err)) }
  }

  return (
    <Modal title={item.id ? `Edit ${item.name}` : 'New service'} onClose={onClose} wide>
      <form className="stack" onSubmit={submit}>
        {!item.id && templates.length > 0 && (
          <Select label="Start from a template" value="" onChange={(e) => { const t = templates.find((x) => x.name === e.target.value); if (t) setF({ ...f, name: t.name, category: t.category, duration: String(t.durationMinutes), description: t.description }) }}>
            <option value="">Choose…</option>{templates.map((t) => <option key={t.name}>{t.name}</option>)}
          </Select>
        )}
        <div className="grid cols-2">
          <Input label="Name" required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
          <Input label="Category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} />
          <Input label="Time needed (minutes)" type="number" min="5" step="5" required value={f.duration} onChange={(e) => setF({ ...f, duration: e.target.value })} />
          <Input label="Description" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
        </div>
        <div><h3>Prices (₹)</h3><p className="muted small">Leave blank for types you do not serve. Changing a price needs the Owner PIN.</p></div>
        <div className="grid cols-4">{variants.map((v) => <Input key={v.key} label={v.label} type="number" min="0" step="1" value={f.prices[v.key]} onChange={(e) => setF({ ...f, prices: { ...f.prices, [v.key]: e.target.value } })} />)}</div>
        <label className="check"><input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Available to customers</label>
        <ErrorNote message={error} />
        <div className="row spread"><Button type="button" onClick={onClose}>Cancel</Button><Button variant="primary">Save</Button></div>
      </form>
    </Modal>
  )
}
