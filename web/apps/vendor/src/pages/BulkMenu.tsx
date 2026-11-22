import { useState } from 'react'
import { messageOf, useAsync, useSession } from '@odyssey/api'
import type { BulkEdit, Provider } from '@odyssey/api'
import { Button, Card, Empty, ErrorNote, PageHeader, Spinner, Table, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

/** Edit prices for several services at once, and apply them to every outlet you pick. Needs the Owner PIN. */
export function BulkMenuPage({ provider }: { provider: Provider }) {
  const { api, config } = useSession()
  const toast = useToast()
  const withPin = useWithPin()
  const first = provider.locations[0]?.id ?? ''
  const { data, error, loading, reload } = useAsync(() => api.provider.items(first), [first])
  const variants = config?.config.variants.options ?? []
  const [prices, setPrices] = useState<Record<string, Record<string, string>>>({})
  const [stock, setStock] = useState<Record<string, boolean>>({})
  const [targets, setTargets] = useState<string[]>(provider.locations.map((l) => l.id))

  const setPrice = (name: string, v: string, val: string) => setPrices({ ...prices, [name]: { ...(prices[name] ?? {}), [v]: val } })

  async function apply() {
    const edits: BulkEdit[] = []
    for (const [name, row] of Object.entries(prices)) {
      const p = Object.fromEntries(Object.entries(row).filter(([, v]) => v !== '').map(([k, v]) => [k, Math.round(parseFloat(v) * 100)]))
      if (Object.keys(p).length) edits.push({ name, prices: p })
    }
    for (const [name, out] of Object.entries(stock)) edits.push({ name, outOfStock: out })
    if (!edits.length) { toast('Nothing changed', true); return }
    try {
      const r = await withPin((pin) => api.provider.bulkMenu(targets, edits, pin), 'Owner PIN to change prices')
      if (r) { toast(`${r.changed} price${r.changed === 1 ? '' : 's'} updated`); setPrices({}); setStock({}); reload() }
    } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Bulk edit" subtitle="Change several prices at once. Leave a box empty to keep the price." actions={<Button variant="primary" onClick={apply}>Apply changes</Button>} />
      {provider.locations.length > 1 && (
        <Card className="stack tight" >
          <strong>Apply to</strong>
          <div className="row wrap">{provider.locations.map((l) => (
            <label className="check" key={l.id}><input type="checkbox" checked={targets.includes(l.id)} onChange={() => setTargets(targets.includes(l.id) ? targets.filter((t) => t !== l.id) : [...targets, l.id])} />{l.name}</label>
          ))}</div>
        </Card>
      )}
      <div style={{ height: 16 }} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No services yet.</Empty> : (
          <Table head={['Service', ...variants.map((v) => `${v.label} (₹)`), 'Out of stock']}>
            {data.map((it) => (
              <tr key={it.id}>
                <td><strong>{it.name}</strong><div className="muted small">{it.category}</div></td>
                {variants.map((v) => <td key={v.key}><input style={{ width: 90 }} type="number" min="0" placeholder={it.prices[v.key] != null ? String(it.prices[v.key] / 100) : '-'} value={prices[it.name]?.[v.key] ?? ''} onChange={(e) => setPrice(it.name, v.key, e.target.value)} /></td>)}
                <td><input type="checkbox" style={{ width: 'auto' }} checked={stock[it.name] ?? false} onChange={(e) => setStock({ ...stock, [it.name]: e.target.checked })} /></td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}
