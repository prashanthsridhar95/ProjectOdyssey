import { useEffect, useState } from 'react'
import { dateTime, messageOf, money } from '@odyssey/api'
import type { Earnings, Payout } from '@odyssey/api'
import { useSession } from '@odyssey/api'
import { Card, Empty, ErrorNote, PageHeader, Spinner, Stat, Table } from '@odyssey/ui'
import { useWithPin } from '../pin'

/** Money screens are behind the Owner PIN. */
export function EarningsPage() {
  const { api } = useSession()
  const withPin = useWithPin()
  const [data, setData] = useState<{ e: Earnings; p: Payout[] } | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function unlock() {
    setError(null)
    try {
      const r = await withPin(async (pin) => ({ e: await api.provider.earnings(pin), p: await api.provider.payouts(pin) }), 'Owner PIN to view earnings')
      if (r) setData(r)
    } catch (e) { setError(messageOf(e)) }
  }
  useEffect(() => { void unlock() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return <><PageHeader title="Earnings" /><ErrorNote message={error} />{!error && <Spinner />}<button className="btn" onClick={unlock}>Enter PIN</button></>
  const { e, p } = data
  return (
    <>
      <PageHeader title="Earnings" subtitle="Completed bookings, after the platform's commission" />
      <div className="grid cols-4">
        <Stat label="Completed bookings" value={e.orders} />
        <Stat label="Service value" value={money(e.grossMinor)} />
        <Stat label="Commission" value={money(e.commissionMinor)} />
        <Stat label="Your earnings" value={money(e.earnedMinor)} />
        <Stat label="Paid out" value={money(e.paidOutMinor)} />
        <Stat label="Waiting for payout" value={money(e.pendingMinor)} hint="Paid out on the next run" />
      </div>
      <div style={{ height: 20 }} />
      <Card flush>
        <div style={{ padding: '16px 18px 4px' }}><h2>Payout history</h2></div>
        {!p.length ? <Empty>No payouts yet.</Empty> : (
          <Table head={['Date', 'Bookings', 'Amount', 'Status']}>{p.map((x) => <tr key={x.id}><td>{dateTime(x.createdAt)}</td><td>{x.orderCount}</td><td>{money(x.amountMinor)}</td><td>{x.status.toLowerCase()}</td></tr>)}</Table>
        )}
      </Card>
    </>
  )
}
