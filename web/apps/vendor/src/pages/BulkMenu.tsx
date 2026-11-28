import { useEffect, useMemo, useState } from 'react'
import { messageOf, money, useAsync, useSession } from '@odyssey/api'
import type { BulkEdit, Item, Provider } from '@odyssey/api'
import { Button, Card, EmptyState, ErrorNote, Notice, PageHeader, SaveBar, SkeletonRows, useConfirm, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

type Grid = Record<string, Record<string, string>>   // item name -> variant -> rupees as typed

/**
 * Edit many prices at once and apply them to every outlet you pick. Boxes start at today's price; changed ones are
 * highlighted with the difference, and a percentage tool moves a whole column or the whole menu in one go.
 */
export function BulkMenuPage({ provider }: { provider: Provider }) {
  const { api, config } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const withPin = useWithPin()
  const first = provider.locations[0]?.id ?? ''
  const { data, error, loading, reload } = useAsync(() => api.provider.items(first), [first])
  const variants = config?.config.variants.options ?? []
  const [grid, setGrid] = useState<Grid>({})
  const [stock, setStock] = useState<Record<string, boolean>>({})
  const [targets, setTargets] = useState<string[]>(provider.locations.map((l) => l.id))
  const [pct, setPct] = useState('10')
  const [column, setColumn] = useState('ALL')
  const [busy, setBusy] = useState(false)

  const original = useMemo(() => {
    const g: Grid = {}
    for (const it of data ?? []) g[it.name] = Object.fromEntries(variants.map((v) => [v.key, it.prices[v.key] === undefined ? '' : String(it.prices[v.key]! / 100)]))
    return g
  }, [data, variants])
  useEffect(() => { setGrid(original); setStock(Object.fromEntries((data ?? []).map((i) => [i.name, i.outOfStock ?? false]))) }, [original, data])

  const changes = useMemo(() => {
    const edits: { item: Item; variant: string; from: number; to: number }[] = []
    for (const it of data ?? []) for (const v of variants) {
      const before = original[it.name]?.[v.key] ?? '', after = grid[it.name]?.[v.key] ?? ''
      if (after !== before && after !== '' && before !== '') edits.push({ item: it, variant: v.key, from: Number(before), to: Number(after) })
    }
    return edits
  }, [grid, original, data, variants])
  const stockChanges = (data ?? []).filter((i) => (stock[i.name] ?? false) !== (i.outOfStock ?? false))
  const invalid = changes.filter((c) => !(c.to > 0) || !Number.isFinite(c.to))
  const dirty = changes.length > 0 || stockChanges.length > 0

  function adjust(sign: 1 | -1) {
    const p = Number(pct)
    if (!(p > 0 && p <= 100)) { toast('Enter a percentage between 1 and 100', true); return }
    const next: Grid = {}
    for (const [name, row] of Object.entries(grid)) {
      next[name] = { ...row }
      for (const v of variants) {
        if (column !== 'ALL' && column !== v.key) continue
        const base = original[name]?.[v.key]
        if (!base) continue
        next[name]![v.key] = String(Math.max(1, Math.round(Number(base) * (1 + (sign * p) / 100))))
      }
    }
    setGrid(next)
  }

  async function apply() {
    if (invalid.length) { toast('Every price must be more than ₹0', true); return }
    if (!targets.length) { toast('Pick at least one outlet', true); return }
    const outlets = provider.locations.filter((l) => targets.includes(l.id)).map((l) => l.name).join(', ')
    if (!(await confirm({
      title: `Apply ${changes.length + stockChanges.length} change${changes.length + stockChanges.length === 1 ? '' : 's'}?`, confirmLabel: 'Apply changes',
      body: <>New prices show to customers straight away at {outlets}. Bookings already made keep their price.{changes.length > 0 && <ul style={{ margin: '10px 0 0', paddingLeft: 18 }}>{changes.slice(0, 6).map((c) => <li key={c.item.id + c.variant}>{c.item.name}, {variants.find((v) => v.key === c.variant)?.label}: {money(c.from * 100)} → <strong>{money(c.to * 100)}</strong></li>)}{changes.length > 6 && <li>and {changes.length - 6} more</li>}</ul>}</>,
    }))) return
    const byItem = new Map<string, BulkEdit>()
    for (const c of changes) { const e = byItem.get(c.item.name) ?? { name: c.item.name, prices: {} }; e.prices![c.variant] = Math.round(c.to * 100); byItem.set(c.item.name, e) }
    for (const i of stockChanges) { const e = byItem.get(i.name) ?? { name: i.name }; e.outOfStock = stock[i.name]; byItem.set(i.name, e) }
    setBusy(true)
    try {
      const r = await withPin((pin) => api.provider.bulkMenu(targets, [...byItem.values()], pin), 'Owner PIN to change prices')
      if (r) { toast(`${r.changed} price${r.changed === 1 ? '' : 's'} updated`); reload() }
    } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title="Bulk price edit" subtitle="Change many prices at once. Boxes show today's price; edit the ones you want to change." />
      <div className="grid" style={{ gridTemplateColumns: provider.locations.length > 1 ? '1fr 1fr' : '1fr', marginBottom: 16 }}>
        <Card title="Raise or lower by a percentage">
          <div className="row wrap end">
            <label className="field" style={{ width: 110 }}><span>Percent</span><input type="number" min="1" max="100" value={pct} onChange={(e) => setPct(e.target.value)} /></label>
            <label className="field" style={{ width: 170 }}><span>For</span>
              <select value={column} onChange={(e) => setColumn(e.target.value)}><option value="ALL">Every vehicle type</option>{variants.map((v) => <option key={v.key} value={v.key}>{v.label} only</option>)}</select></label>
            <Button icon="arrowUp" onClick={() => adjust(1)}>Raise</Button>
            <Button icon="arrowDown" onClick={() => adjust(-1)}>Lower</Button>
          </div>
          <p className="muted small" style={{ marginTop: 8 }}>Works from today's prices and rounds to the rupee. Nothing is saved until you apply.</p>
        </Card>
        {provider.locations.length > 1 && (
          <Card title="Apply to">
            <div className="stack tight">{provider.locations.map((l) => (
              <label className="check" key={l.id}><input type="checkbox" checked={targets.includes(l.id)} onChange={() => setTargets(targets.includes(l.id) ? targets.filter((t) => t !== l.id) : [...targets, l.id])} />{l.name}</label>
            ))}</div>
          </Card>
        )}
      </div>
      <ErrorNote message={error} onRetry={reload} />
      {invalid.length > 0 && <><Notice tone="bad">Some prices are empty or zero. Every price must be more than ₹0.</Notice><div className="spacer" style={{ height: 12 }} /></>}
      <Card flush>
        {loading && !data ? <SkeletonRows /> : !data?.length ? <EmptyState icon="tag" title="No services yet">Add services on the Menu page first.</EmptyState> : (
          <div className="scroll-x">
            <table className="table">
              <thead><tr><th>Service</th>{variants.map((v) => <th key={v.key}>{v.label} (₹)</th>)}<th>Out of stock</th></tr></thead>
              <tbody>
                {data.map((it) => (
                  <tr key={it.id}>
                    <td><strong>{it.name}</strong><div className="sub">{it.category}</div></td>
                    {variants.map((v) => {
                      const before = original[it.name]?.[v.key] ?? '', after = grid[it.name]?.[v.key] ?? ''
                      if (before === '') return <td key={v.key}><span className="faint small">Not offered</span></td>
                      const changed = after !== before, diff = Number(after) - Number(before)
                      return (
                        <td key={v.key}>
                          <input type="number" min="1" style={{ width: 96, borderColor: changed ? 'var(--c-primary)' : undefined, background: changed ? 'var(--c-primary-soft)' : undefined }}
                            aria-label={`${it.name}, ${v.label}`} value={after} onChange={(e) => setGrid({ ...grid, [it.name]: { ...grid[it.name], [v.key]: e.target.value } })} />
                          {changed && after !== '' && <div className="tiny" style={{ color: diff >= 0 ? 'var(--c-good)' : 'var(--c-bad)', marginTop: 2 }}>{diff >= 0 ? '+' : '−'}₹{Math.abs(diff)} ({diff >= 0 ? '+' : '−'}{Math.abs(Math.round((diff / Number(before)) * 100))}%) <span className="faint">was ₹{before}</span></div>}
                        </td>
                      )
                    })}
                    <td><input type="checkbox" role="switch" className="toggle" aria-label={`${it.name} out of stock`} checked={stock[it.name] ?? false} onChange={(e) => setStock({ ...stock, [it.name]: e.target.checked })} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <SaveBar dirty={dirty} busy={busy} onSave={apply} onDiscard={() => { setGrid(original); setStock(Object.fromEntries((data ?? []).map((i) => [i.name, i.outOfStock ?? false]))) }}
        text={`${changes.length} price${changes.length === 1 ? '' : 's'}${stockChanges.length ? ` and ${stockChanges.length} stock change${stockChanges.length === 1 ? '' : 's'}` : ''} not applied yet`} />
    </>
  )
}
