import { useState } from 'react'
import { bpsToPercent, money, useAsync, useSession } from '@odyssey/api'
import { Card, ErrorNote, Input, KpiStrip, Notice, PageHeader, Skeleton } from '@odyssey/ui'

/** What the framework owner invoices this tenant: base subscription plus a per-order charge above the threshold. */
export function BillingPage() {
  const { api } = useSession()
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7))
  const { data, error, loading, reload } = useAsync(() => api.admin.billing(month), [month])
  const cfg = useAsync(() => api.admin.config(), [])
  const used = data ? Math.min(100, Math.round((data.orders / Math.max(1, data.includedOrders)) * 100)) : 0
  return (
    <>
      <PageHeader title="Platform billing" subtitle="Your subscription to the Odyssey platform" actions={<Input label="Month" type="month" value={month} max={new Date().toISOString().slice(0, 7)} onChange={(e) => setMonth(e.target.value)} />} />
      <ErrorNote message={error} onRetry={reload} />
      {loading && !data ? <Card><Skeleton height={60} /></Card> : data && (
        <>
          <KpiStrip items={[
            { label: 'Total due', icon: 'receipt', value: money(data.totalMinor), foot: 'For this month so far' },
            { label: 'Base subscription', icon: 'layers', value: money(data.baseSubscriptionMinor) },
            { label: 'Orders', icon: 'calendar', value: data.orders.toLocaleString('en-IN'), foot: `${data.includedOrders.toLocaleString('en-IN')} included` },
            { label: 'Above the threshold', icon: 'trend', value: data.overageOrders.toLocaleString('en-IN'), foot: cfg.data ? `${bpsToPercent(cfg.data.billing.perOrderBps)} of each, ${money(data.perOrderChargeMinor)} so far` : undefined },
          ]} />
          <div className="spacer" />
          <Card title="Included orders used">
            <div style={{ height: 10, background: 'var(--c-surface-2)', border: '1px solid var(--c-border)' }} role="progressbar" aria-valuenow={used} aria-valuemin={0} aria-valuemax={100}>
              <div style={{ width: `${used}%`, height: '100%', background: used >= 100 ? 'var(--c-warn)' : 'var(--c-primary)' }} />
            </div>
            <p className="muted small" style={{ marginTop: 8 }}>{data.orders.toLocaleString('en-IN')} of {data.includedOrders.toLocaleString('en-IN')} ({used}%). Orders above the threshold are charged per order.</p>
          </Card>
        </>
      )}
      <div className="spacer" />
      <Notice tone="info">Billing terms are set by the platform owner. Questions about an invoice go to your account manager.</Notice>
    </>
  )
}
