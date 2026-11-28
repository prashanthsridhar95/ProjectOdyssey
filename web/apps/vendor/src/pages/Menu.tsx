import { useState } from 'react'
import { messageOf, money, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Item, Provider } from '@odyssey/api'
import { Button, Card, DataTable, EmptyState, ErrorNote, Input, Modal, PageHeader, Select, useConfirm, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

/** Changing a price, adding or deleting a service needs the Owner PIN. Turning a service on or off does not. */
export function MenuPage({ provider }: { provider: Provider }) {
  const { api, config, user } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const withPin = useWithPin()
  const confirm = useConfirm()
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [editing, setEditing] = useState<Partial<Item> | null>(null)
  const { data, error, loading, reload } = useAsync(() => api.provider.items(locationId), [locationId])
  const variants = config?.config.variants.options ?? []
  const owner = user?.role === 'PROVIDER_OWNER'

  async function toggle(item: Item) {
    try { await api.provider.saveItem(locationId, { ...item, active: !item.active }); toast(item.active ? `${item.name} hidden from customers` : `${item.name} is bookable again`); reload() } catch (e) { toast(messageOf(e), true) }
  }
  async function remove(item: Item) {
    if (!(await confirm({ title: `Delete ${item.name}?`, tone: 'danger', confirmLabel: 'Delete', body: 'It disappears from your menu for good. Existing bookings for it are not affected. To hide it for a while instead, turn it off.' }))) return
    try { const r = await withPin((pin) => api.provider.deleteItem(locationId, item.id, pin).then(() => true), 'Owner PIN to delete'); if (r) { toast(`${item.name} deleted`); reload() } } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Menu" subtitle={`Your ${labels.item.toLowerCase()}s and prices by ${config?.config.variants.name.toLowerCase()}`} actions={<>
        {provider.locations.length > 1 && <Select label={labels.location} value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
        {owner && <Button variant="primary" icon="plus" onClick={() => setEditing({ active: true, durationMinutes: 30, prices: {} })}>Add {labels.item.toLowerCase()}</Button>}
      </>} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(i) => i.id} search={(i) => `${i.name} ${i.category}`} searchPlaceholder={`Search ${labels.item.toLowerCase()}s`} pageSize={50}
          empty={<EmptyState icon="tag" title={`No ${labels.item.toLowerCase()}s yet`} action={owner && <Button icon="plus" onClick={() => setEditing({ active: true, durationMinutes: 30, prices: {} })}>Add your first {labels.item.toLowerCase()}</Button>}>Customers see these with prices for each {config?.config.variants.name.toLowerCase()}.</EmptyState>}
          columns={[
            { key: 'name', header: labels.item, cell: (i) => <><strong>{i.name}</strong><div className="sub">{i.category || 'No category'}</div></>, sort: (i) => i.name },
            { key: 'time', header: 'Time', cell: (i) => <span className="nowrap">{i.durationMinutes} min</span>, sort: (i) => i.durationMinutes },
            ...variants.map((v) => ({ key: v.key, header: v.label, align: 'right' as const, cell: (i: Item) => (i.prices[v.key] === undefined ? <span className="faint">–</span> : money(i.prices[v.key])), sort: (i: Item) => i.prices[v.key] ?? -1 })),
            { key: 'on', header: 'Bookable', cell: (i) => <input type="checkbox" role="switch" className="toggle" checked={i.active} aria-label={`${i.name} bookable`} onChange={() => void toggle(i)} /> },
            { key: 'act', header: '', align: 'right', cell: (i) => <span className="row tight" style={{ justifyContent: 'flex-end' }}><Button small variant="ghost" icon="edit" onClick={() => setEditing(i)}>Edit</Button>{owner && <Button small variant="ghost" icon="trash" onClick={() => remove(i)} aria-label={`Delete ${i.name}`} />}</span> },
          ]} />
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
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    const prices: Record<string, number> = {}
    for (const v of variants) if (f.prices[v.key] !== '') prices[v.key] = Math.round(parseFloat(f.prices[v.key]) * 100)
    if (!f.name.trim()) { setError('Give the service a name'); return }
    if (!(parseInt(f.duration) >= 5)) { setError('The time needed must be at least 5 minutes'); return }
    if (Object.values(prices).some((v) => Number.isNaN(v) || v <= 0)) { setError('Prices must be more than ₹0'); return }
    if (!Object.keys(prices).length) { setError('Add at least one price'); return }
    const body = { id: item.id, name: f.name, description: f.description, category: f.category, durationMinutes: parseInt(f.duration), active: f.active, prices }
    const priceChanged = JSON.stringify(Object.entries(prices).sort()) !== JSON.stringify(Object.entries(item.prices ?? {}).sort())
    setBusy(true)
    try {
      if (!item.id || priceChanged) {
        if (!owner) { setError('Only the owner can change prices'); return }
        const r = await withPin((pin) => api.provider.saveItem(locationId, body, pin), 'Owner PIN to change prices')
        if (!r) return
      } else await api.provider.saveItem(locationId, body)
      toast(item.id ? `${f.name} saved` : `${f.name} added to your menu`); onSaved()
    } catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }

  return (
    <Modal title={item.id ? `Edit ${item.name}` : 'New service'} description={owner ? 'Changing a price asks for your Owner PIN.' : 'Only the owner can change prices.'} onClose={onClose} wide
      footer={<><Button type="button" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="item-form" busy={busy}>Save</Button></>}>
      <form id="item-form" className="stack" onSubmit={submit}>
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
        <div><h3>Prices (₹)</h3><p className="muted small">Leave blank for types you do not serve.</p></div>
        <div className="grid cols-4">{variants.map((v) => <Input key={v.key} label={v.label} type="number" min="0" step="1" value={f.prices[v.key]} onChange={(e) => setF({ ...f, prices: { ...f.prices, [v.key]: e.target.value } })} />)}</div>
        <label className="check"><input type="checkbox" role="switch" className="toggle" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Customers can book this</label>
        <ErrorNote message={error} />
      </form>
    </Modal>
  )
}
