import { useEffect, useMemo, useState } from 'react'
import { bpsToPercent, money, messageOf, useAsync, useSession } from '@odyssey/api'
import type { TenantConfig } from '@odyssey/api'
import { Button, Card, DescriptionList, EmptyState, ErrorNote, Input, Notice, PageHeader, SaveBar, Skeleton, useConfirm, useToast } from '@odyssey/ui'

interface Area { name: string; lat: number; lng: number; radiusKm: number }

/** Tenant-level rules: commission default, tax, fees, cancellation policy and booking rules. Changes apply to new bookings. */
export function SettingsPage() {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const { data, error, loading, reload, setData } = useAsync(() => api.admin.config(), [])
  const [cfg, setCfg] = useState<TenantConfig | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (data) setCfg(data) }, [data])
  const dirty = useMemo(() => !!data && !!cfg && JSON.stringify(data) !== JSON.stringify(cfg), [data, cfg])

  if (loading && !cfg) return <><PageHeader title="Settings" /><div className="grid cols-2">{[1, 2, 3, 4].map((i) => <Card key={i}><div className="stack"><Skeleton width="40%" height={18} /><Skeleton /><Skeleton /></div></Card>)}</div></>
  if (error && !cfg) return <><PageHeader title="Settings" /><ErrorNote message={error} onRetry={reload} /></>
  if (!cfg) return null

  const num = (v: string) => (v === '' ? 0 : Number(v))
  const areas = ((cfg as unknown as { serviceAreas?: Area[] }).serviceAreas ?? []) as Area[]
  const problems = [
    cfg.money.defaultCommissionBps < 0 || cfg.money.defaultCommissionBps > 5000 ? 'Default commission must be between 0% and 50%.' : null,
    cfg.money.taxBps < 0 || cfg.money.taxBps > 5000 ? 'Tax must be between 0% and 50%.' : null,
    cfg.cancellation.lateRetainBps < 0 || cfg.cancellation.lateRetainBps > 10000 ? 'The kept share must be between 0% and 100%.' : null,
    cfg.availability.windowDays < 1 ? 'Customers must be able to book at least 1 day ahead.' : null,
    cfg.availability.holdMinutes < 1 ? 'The checkout hold must be at least 1 minute.' : null,
    areas.some((a) => !a.name.trim()) ? 'Every area needs a name.' : null,
  ].filter(Boolean) as string[]

  async function save() {
    if (problems.length) { toast(problems[0]!, true); return }
    const moneyChanged = JSON.stringify(data!.money) !== JSON.stringify(cfg!.money) || JSON.stringify(data!.cancellation) !== JSON.stringify(cfg!.cancellation)
    if (moneyChanged && !(await confirm({ title: 'Change fees and policy for every new booking?', confirmLabel: 'Save changes', body: 'Bookings already made keep the terms they were made with. Vendors and customers see the new terms straight away.' }))) return
    setBusy(true)
    try { const saved = await api.admin.saveConfig(cfg!); setData(saved); setCfg(saved); toast('Settings saved') } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title="Settings" subtitle="Rules that apply to every new booking on the marketplace" />
      {problems.length > 0 && <><Notice tone="bad">{problems.join(' ')}</Notice><div className="spacer" /></>}
      <div className="grid cols-2">
        <Card title="Money">
          <div className="stack">
            <Input label="Default commission (%)" type="number" step="0.5" min="0" max="50" value={cfg.money.defaultCommissionBps / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, defaultCommissionBps: Math.round(num(e.target.value) * 100) } })} hint="Taken from the service amount, not tax or fees. A vendor can have its own rate." />
            <Input label="Tax on service (%)" type="number" step="0.5" min="0" value={cfg.money.taxBps / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, taxBps: Math.round(num(e.target.value) * 100) } })} />
            <Input label="Customer convenience fee (₹)" type="number" min="0" value={cfg.money.customerFeeMinor / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, customerFeeMinor: Math.round(num(e.target.value) * 100) } })} hint="A flat fee added to every booking" />
          </div>
        </Card>
        <Card title="Cancellation policy">
          <div className="stack">
            <Input label="Full refund until (hours before the start)" type="number" min="0" value={cfg.cancellation.fullRefundHoursBefore} onChange={(e) => setCfg({ ...cfg, cancellation: { ...cfg.cancellation, fullRefundHoursBefore: num(e.target.value) } })} />
            <Input label="Kept after that (%)" type="number" min="0" max="100" value={cfg.cancellation.lateRetainBps / 100} onChange={(e) => setCfg({ ...cfg, cancellation: { ...cfg.cancellation, lateRetainBps: Math.round(num(e.target.value) * 100) } })} hint="The kept part goes to the vendor, less commission" />
            <Notice tone="info">A customer who cancels {cfg.cancellation.fullRefundHoursBefore} h or more before gets everything back; later, they get {100 - cfg.cancellation.lateRetainBps / 100}% back. Vendor cancellations and rejections always refund in full. No-shows are not refunded.</Notice>
          </div>
        </Card>
        <Card title="Booking rules">
          <div className="stack">
            <Input label="Minimum notice (minutes)" type="number" min="0" value={cfg.availability.leadMinutes} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, leadMinutes: num(e.target.value) } })} hint="How soon before a slot it can still be booked" />
            <Input label="How far ahead customers can book (days)" type="number" min="1" value={cfg.availability.windowDays} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, windowDays: num(e.target.value) } })} />
            <Input label="Slot hold during checkout (minutes)" type="number" min="1" value={cfg.availability.holdMinutes} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, holdMinutes: num(e.target.value) } })} hint="Keeps the slot for the customer while they pay" />
          </div>
        </Card>
        <Card title="Platform terms">
          <DescriptionList items={[
            ['Base subscription', `${money(cfg.billing.baseSubscriptionMinor)} a month`],
            ['Included orders', `${cfg.billing.includedOrders.toLocaleString('en-IN')} a month`],
            ['Above that', `${bpsToPercent(cfg.billing.perOrderBps)} of each order`],
          ]} />
          <p className="muted small" style={{ marginTop: 12 }}>Set by the platform owner. See Platform billing for this month's statement.</p>
        </Card>
      </div>
      <div className="spacer" />
      <ServiceAreas areas={areas} set={(next) => setCfg({ ...cfg, serviceAreas: next } as TenantConfig)} />
      <SaveBar dirty={dirty} busy={busy} onSave={save} onDiscard={() => setCfg(data!)} />
    </>
  )
}

/** Cities and areas the marketplace serves. The customer app offers a nearby area when the phone is somewhere else. */
function ServiceAreas({ areas, set }: { areas: Area[]; set: (a: Area[]) => void }) {
  const edit = (i: number, patch: Partial<Area>) => set(areas.map((a, j) => (j === i ? { ...a, ...patch } : a)))
  return (
    <Card title="Cities and areas" actions={<Button small icon="plus" onClick={() => set([...areas, { name: '', lat: 12.9716, lng: 77.5946, radiusKm: 15 }])}>Add area</Button>}>
      {!areas.length ? <EmptyState icon="mapPin" title="No areas yet">Customers anywhere can search. Add areas to suggest one when a customer is outside them.</EmptyState> : (
        <div className="stack">
          {areas.map((a, i) => (
            <div className="row wrap end" key={i}>
              <div className="grow" style={{ minWidth: 180 }}><Input label="Name" value={a.name} onChange={(e) => edit(i, { name: e.target.value })} placeholder="e.g. Bengaluru East" error={!a.name.trim() ? 'Required' : null} /></div>
              <div style={{ width: 130 }}><Input label="Latitude" type="number" step="any" value={a.lat} onChange={(e) => edit(i, { lat: Number(e.target.value) })} /></div>
              <div style={{ width: 130 }}><Input label="Longitude" type="number" step="any" value={a.lng} onChange={(e) => edit(i, { lng: Number(e.target.value) })} /></div>
              <div style={{ width: 110 }}><Input label="Radius (km)" type="number" min="1" value={a.radiusKm} onChange={(e) => edit(i, { radiusKm: Number(e.target.value) })} /></div>
              <Button variant="ghost" icon="trash" onClick={() => set(areas.filter((_, j) => j !== i))} aria-label={`Remove ${a.name || 'area'}`}>Remove</Button>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}
