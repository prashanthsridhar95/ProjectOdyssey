import { useState } from 'react'
import { bpsToPercent, money, useAsync, useSession } from '@odyssey/api'
import { Card, ErrorNote, Input, PageHeader, Spinner, Stat } from '@odyssey/ui'

/** What the framework owner invoices this tenant: base subscription plus a per-order charge above the threshold. */
export function BillingPage() {
  const { api } = useSession()
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 7))
  const { data, error, loading } = useAsync(() => api.admin.billing(month), [month])
  const cfg = useAsync(() => api.admin.config(), [])
  return (
    <>
      <PageHeader title="Platform billing" subtitle="Your subscription to the Odyssey platform" actions={<Input label="Month" type="month" value={month} onChange={(e) => setMonth(e.target.value)} />} />
      <ErrorNote message={error} />
      {loading || !data ? <Spinner /> : (
        <div className="grid cols-4">
          <Stat label="Orders this month" value={data.orders} hint={`${data.includedOrders} included in the subscription`} />
          <Stat label="Orders above the threshold" value={data.overageOrders} />
          <Stat label="Base subscription" value={money(data.baseSubscriptionMinor)} />
          <Stat label="Per-order charge" value={money(data.perOrderChargeMinor)} hint={cfg.data ? `${bpsToPercent(cfg.data.billing.perOrderBps)} of each order above the threshold` : undefined} />
          <Stat label="Total due" value={money(data.totalMinor)} />
        </div>
      )}
      <Card className="small muted" >Billing terms are set by the platform owner. The subscription covers a set number of orders each month; above that, a small percentage of each order is charged.</Card>
    </>
  )
}
