import { useEffect, useState } from 'react'
import { bpsToPercent, money, messageOf, useAsync, useSession } from '@odyssey/api'
import type { TenantConfig } from '@odyssey/api'
import { Button, Card, ErrorNote, Input, PageHeader, Spinner, useToast } from '@odyssey/ui'

/** Tenant-level rules: commission default, tax, fees, cancellation policy and booking rules. Changes apply to new bookings. */
export function SettingsPage() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading } = useAsync(() => api.admin.config(), [])
  const [cfg, setCfg] = useState<TenantConfig | null>(null)
  useEffect(() => { if (data) setCfg(data) }, [data])
  if (loading || !cfg) return <Spinner />
  if (error) return <ErrorNote message={error} />

  const num = (v: string) => (v === '' ? 0 : Number(v))
  async function save() {
    try { setCfg(await api.admin.saveConfig(cfg!)); toast('Settings saved') } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Settings" subtitle="Rules that apply to every new booking" actions={<Button variant="primary" onClick={save}>Save changes</Button>} />
      <div className="grid cols-2">
        <Card>
          <h2>Money</h2>
          <div className="stack" style={{ marginTop: 12 }}>
            <Input label="Default commission (%)" type="number" step="0.5" min="0" max="50" value={cfg.money.defaultCommissionBps / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, defaultCommissionBps: Math.round(num(e.target.value) * 100) } })} hint="Taken from the service amount; a vendor can have its own rate" />
            <Input label="Tax on service (%)" type="number" step="0.5" min="0" value={cfg.money.taxBps / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, taxBps: Math.round(num(e.target.value) * 100) } })} />
            <Input label="Customer convenience fee (₹)" type="number" min="0" value={cfg.money.customerFeeMinor / 100} onChange={(e) => setCfg({ ...cfg, money: { ...cfg.money, customerFeeMinor: Math.round(num(e.target.value) * 100) } })} hint="Flat fee added to every booking" />
          </div>
        </Card>
        <Card>
          <h2>Cancellation policy</h2>
          <div className="stack" style={{ marginTop: 12 }}>
            <Input label="Full refund until (hours before start)" type="number" min="0" value={cfg.cancellation.fullRefundHoursBefore} onChange={(e) => setCfg({ ...cfg, cancellation: { ...cfg.cancellation, fullRefundHoursBefore: num(e.target.value) } })} />
            <Input label="Kept after that (%)" type="number" min="0" max="100" value={cfg.cancellation.lateRetainBps / 100} onChange={(e) => setCfg({ ...cfg, cancellation: { ...cfg.cancellation, lateRetainBps: Math.round(num(e.target.value) * 100) } })} hint="The kept part goes to the vendor, less commission" />
            <p className="muted small">Vendor cancellations and rejections always refund in full. No-shows are not refunded.</p>
          </div>
        </Card>
        <Card>
          <h2>Booking rules</h2>
          <div className="stack" style={{ marginTop: 12 }}>
            <Input label="Minimum lead time (minutes)" type="number" min="0" value={cfg.availability.leadMinutes} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, leadMinutes: num(e.target.value) } })} />
            <Input label="How far ahead customers can book (days)" type="number" min="1" value={cfg.availability.windowDays} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, windowDays: num(e.target.value) } })} />
            <Input label="Slot hold during checkout (minutes)" type="number" min="1" value={cfg.availability.holdMinutes} onChange={(e) => setCfg({ ...cfg, availability: { ...cfg.availability, holdMinutes: num(e.target.value) } })} />
          </div>
        </Card>
        <Card>
          <h2>Platform terms</h2>
          <div className="stack tight" style={{ marginTop: 12 }}>
            <p>Base subscription: <strong>{money(cfg.billing.baseSubscriptionMinor)}</strong> a month</p>
            <p>Included orders: <strong>{cfg.billing.includedOrders}</strong> a month</p>
            <p>Above that: <strong>{bpsToPercent(cfg.billing.perOrderBps)}</strong> of each order</p>
            <p className="muted small">Set by the platform owner. See Platform billing for this month's statement.</p>
          </div>
        </Card>
      </div>
    </>
  )
}
