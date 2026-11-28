import { useState } from 'react'
import { bpsToPercent, dateOnly, isoDate, messageOf, money, titleCase, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, EmptyState, ErrorNote, Input, PageHeader, Select, SkeletonRows, Table, useConfirm, useToast } from '@odyssey/ui'

/** Commission rules beat the default: an outlet rule beats a vendor rule, which beats a category rule. */
export function Rules() {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const rules = useAsync(() => api.admin.rules(), [])
  const providers = useAsync(() => api.admin.providers(), [])
  const [f, setF] = useState({ scope: 'VENDOR', target: '', pct: '12', from: isoDate(), until: '' })
  const [prev, setPrev] = useState({ service: '500', pct: '15' })
  const preview = useAsync(() => api.admin.preview(Math.round(parseFloat(prev.service || '0') * 100), Math.round(parseFloat(prev.pct || '0') * 100)), [prev.service, prev.pct])
  const [error, setError] = useState<string | null>(null)

  const targets = f.scope === 'VENDOR'
    ? (providers.data ?? []).map((p) => ({ id: p.id, label: p.name }))
    : f.scope === 'OUTLET' ? (providers.data ?? []).flatMap((p) => p.locations.map((l) => ({ id: l.id, label: `${p.name} · ${l.name}` }))) : []

  async function add(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    const pct = parseFloat(f.pct)
    if (!f.target.trim()) { setError('Choose what the rule applies to'); return }
    if (Number.isNaN(pct) || pct < 0 || pct > 50) { setError('Commission must be between 0% and 50%'); return }
    if (f.until && f.until < f.from) { setError('The end date is before the start date'); return }
    try {
      await api.admin.addRule({ scope: f.scope, target: f.target, rateBps: Math.round(parseFloat(f.pct) * 100), effectiveFrom: new Date(`${f.from}T00:00:00+05:30`).toISOString(), effectiveUntil: f.until ? new Date(`${f.until}T23:59:59+05:30`).toISOString() : undefined })
      toast('Rule added'); rules.reload(); setF({ ...f, target: '', until: '' })
    } catch (err) { setError(messageOf(err)) }
  }
  async function remove(id: string, label: string) {
    if (!(await confirm({ title: `Remove the rule for ${label}?`, tone: 'danger', confirmLabel: 'Remove rule', body: 'New bookings go back to the next rule that applies, or the default rate. Bookings already made keep their rate.' }))) return
    try { await api.admin.deleteRule(id); toast('Rule removed'); rules.reload() } catch (e) { toast(messageOf(e), true) }
  }
  const today = isoDate()
  const live = (r: { effectiveFrom: string; effectiveUntil?: string | null }) => r.effectiveFrom.slice(0, 10) <= today && (!r.effectiveUntil || r.effectiveUntil.slice(0, 10) >= today)

  return (
    <>
      <PageHeader title="Commission rules" subtitle="Override the default rate for a vendor, an outlet or a category, for a period. An outlet rule beats a vendor rule, which beats a category rule." />
      <div className="grid cols-2">
        <form onSubmit={add}>
          <Card className="stack">
            <h2>New rule</h2>
            <Select label="Applies to" value={f.scope} onChange={(e) => setF({ ...f, scope: e.target.value, target: '' })}><option value="VENDOR">A vendor</option><option value="OUTLET">An outlet</option><option value="CATEGORY">A service category</option></Select>
            {f.scope === 'CATEGORY'
              ? <Input label="Category" required value={f.target} onChange={(e) => setF({ ...f, target: e.target.value })} placeholder="e.g. Detailing" />
              : <Select label="Target" required value={f.target} onChange={(e) => setF({ ...f, target: e.target.value })}><option value="">Choose…</option>{targets.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</Select>}
            <Input label="Commission (%)" type="number" step="0.5" min="0" max="50" required value={f.pct} onChange={(e) => setF({ ...f, pct: e.target.value })} />
            <div className="grid cols-2"><Input label="From" type="date" required value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /><Input label="Until (optional)" type="date" value={f.until} onChange={(e) => setF({ ...f, until: e.target.value })} /></div>
            <ErrorNote message={error} />
            <div><Button variant="primary" icon="plus">Add rule</Button></div>
          </Card>
        </form>
        <Card className="stack">
          <h2>Preview</h2>
          <div className="grid cols-2"><Input label="Service price (₹)" type="number" value={prev.service} onChange={(e) => setPrev({ ...prev, service: e.target.value })} /><Input label="Rate (%)" type="number" step="0.5" value={prev.pct} onChange={(e) => setPrev({ ...prev, pct: e.target.value })} /></div>
          {preview.data && (
            <Table head={['', 'Amount']}>
              <tr><td>Commission</td><td>{money(preview.data.commissionMinor)}</td></tr>
              <tr><td>Payment gateway fee (recovered from vendor)</td><td>{money(preview.data.gatewayFeeMinor)}</td></tr>
              <tr><td><strong>Vendor receives</strong></td><td><strong>{money(preview.data.payoutMinor)}</strong></td></tr>
            </Table>
          )}
        </Card>
      </div>
      <div style={{ height: 16 }} />
      <Card title="Rules" flush>
        {rules.loading && !rules.data ? <SkeletonRows rows={3} /> : !rules.data?.length ? <EmptyState icon="percent" title="No rules yet">Every vendor pays the default rate set in Settings.</EmptyState> : (
          <Table head={['Applies to', 'Target', 'Rate', 'Period', 'State', 'Set by', '']}>
            {rules.data.map((r) => <tr key={r.id}><td>{titleCase(r.scope)}</td><td><strong>{r.targetLabel}</strong></td><td className="num">{bpsToPercent(r.rateBps)}</td><td className="nowrap">{dateOnly(r.effectiveFrom)} – {r.effectiveUntil ? dateOnly(r.effectiveUntil) : 'no end'}</td><td>{live(r) ? <Badge tone="good" dot>In effect</Badge> : r.effectiveFrom.slice(0, 10) > today ? <Badge tone="info" dot>Scheduled</Badge> : <Badge dot>Ended</Badge>}</td><td className="muted small">{r.setBy}</td><td className="right"><Button small variant="ghost" icon="trash" onClick={() => remove(r.id, r.targetLabel)}>Remove</Button></td></tr>)}
          </Table>
        )}
      </Card>
    </>
  )
}
