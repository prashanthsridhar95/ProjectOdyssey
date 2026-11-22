import { useState } from 'react'
import { bpsToPercent, dateOnly, isoDate, messageOf, money, titleCase, useAsync, useSession } from '@odyssey/api'
import { Button, Card, Empty, ErrorNote, Input, PageHeader, Select, Spinner, Table, useToast } from '@odyssey/ui'

/** Commission rules beat the default: an outlet rule beats a vendor rule, which beats a category rule. */
export function Rules() {
  const { api } = useSession()
  const toast = useToast()
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
    try {
      await api.admin.addRule({ scope: f.scope, target: f.target, rateBps: Math.round(parseFloat(f.pct) * 100), effectiveFrom: new Date(`${f.from}T00:00:00+05:30`).toISOString(), effectiveUntil: f.until ? new Date(`${f.until}T23:59:59+05:30`).toISOString() : undefined })
      toast('Rule added'); rules.reload()
    } catch (err) { setError(messageOf(err)) }
  }
  async function remove(id: string) { try { await api.admin.deleteRule(id); rules.reload() } catch (e) { toast(messageOf(e), true) } }

  return (
    <>
      <PageHeader title="Commission rules" subtitle="Override the default rate for a vendor, an outlet or a category" />
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
            <div><Button variant="primary">Add rule</Button></div>
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
      <Card flush>
        <div style={{ padding: '16px 18px 4px' }}><h2>Active rules</h2></div>
        {rules.loading ? <Spinner /> : !rules.data?.length ? <Empty>No rules. Every vendor pays the default rate.</Empty> : (
          <Table head={['Applies to', 'Target', 'Rate', 'From', 'Until', 'Set by', '']}>
            {rules.data.map((r) => <tr key={r.id}><td>{titleCase(r.scope)}</td><td>{r.targetLabel}</td><td>{bpsToPercent(r.rateBps)}</td><td>{dateOnly(r.effectiveFrom)}</td><td>{r.effectiveUntil ? dateOnly(r.effectiveUntil) : '-'}</td><td className="muted small">{r.setBy}</td><td><Button small variant="danger" onClick={() => remove(r.id)}>Remove</Button></td></tr>)}
          </Table>
        )}
      </Card>
    </>
  )
}
