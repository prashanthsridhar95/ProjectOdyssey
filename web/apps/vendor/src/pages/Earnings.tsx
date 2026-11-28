import { useEffect, useState } from 'react'
import { dateOnly, dateTime, messageOf, money, useSession } from '@odyssey/api'
import type { Earnings, Payout } from '@odyssey/api'
import { Button, Card, DataTable, EmptyState, ErrorNote, Icon, KpiStrip, Notice, PageHeader, Segmented, StatusBadge, downloadText, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

const RANGES = [['week', 'This week'], ['month', 'This month'], ['all', 'All time']] as const

/** Money screens are behind the Owner PIN. */
export function EarningsPage() {
  const { api } = useSession()
  const withPin = useWithPin()
  const toast = useToast()
  const [range, setRange] = useState<(typeof RANGES)[number][0]>('month')
  const [data, setData] = useState<{ e: Earnings; p: Payout[] } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function unlock(r = range) {
    setError(null); setBusy(true)
    try {
      const res = await withPin(async (pin) => ({ e: await api.provider.earnings(pin, r), p: await api.provider.payouts(pin) }), 'Owner PIN to see earnings')
      if (res) setData(res)
    } catch (e) { setError(messageOf(e)) } finally { setBusy(false) }
  }
  async function download() {
    try {
      const csv = await withPin((pin) => api.provider.statement(pin, range === 'all' ? 'month' : range), 'Owner PIN to download the statement')
      if (csv) { downloadText(`statement-${new Date().toISOString().slice(0, 7)}.csv`, csv); toast('Statement downloaded') }
    } catch (e) { toast(messageOf(e), true) }
  }
  useEffect(() => { void unlock() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return (
    <>
      <PageHeader title="Earnings" />
      <ErrorNote message={error} />
      <Card>
        <div className="lockcard">
          <div className="glyph"><Icon name="lock" size={22} /></div>
          <h2>Earnings are locked</h2>
          <p className="muted" style={{ maxWidth: 380 }}>Only the owner can see earnings, payouts and statements. Enter your Owner PIN to continue.</p>
          <Button variant="primary" busy={busy} onClick={() => void unlock()}>Enter PIN</Button>
        </div>
      </Card>
    </>
  )
  const { e, p } = data
  return (
    <>
      <PageHeader title="Earnings" subtitle="Completed bookings, after the platform's commission and payment fees"
        actions={<><Segmented label="Period" options={RANGES} value={range} onChange={(r) => { setRange(r); void unlock(r) }} /><Button icon="download" onClick={download}>Statement</Button></>} />
      <KpiStrip items={[
        { label: 'Your net earnings', icon: 'wallet', value: money(e.netMinor), foot: `${e.orders} completed bookings` },
        { label: 'Waiting for payout', icon: 'clock', value: money(e.pendingMinor), foot: `Next payout ${dateOnly(e.nextPayoutDate)}` },
        { label: 'Paid to your bank', icon: 'checkCircle', value: money(e.paidOutMinor), foot: e.bankMasked ? `To ${e.bankMasked}` : 'No bank account yet' },
      ]} />
      <div className="spacer" />
      {!e.bankMasked && <><Notice tone="warn">Add a bank account in Settings so your earnings can be paid out.</Notice><div className="spacer" /></>}
      <div className="grid" style={{ gridTemplateColumns: 'minmax(280px, 1fr) minmax(0, 1.6fr)' }}>
        <Card title="How it adds up">
          <table className="money-lines"><tbody>
            <tr><td>Service value</td><td>{money(e.serviceMinor)}</td></tr>
            <tr className="sub"><td>Platform commission ({(e.commissionBps / 100).toFixed(1)}%)</td><td>−{money(e.commissionMinor)}</td></tr>
            <tr className="sub"><td>Payment processing fees</td><td>−{money(e.gatewayFeeMinor)}</td></tr>
            <tr className="total"><td>Your net earnings</td><td>{money(e.netMinor)}</td></tr>
          </tbody></table>
          <p className="faint small" style={{ marginTop: 10 }}>Tax and the customer's convenience fee are not part of your earnings or the commission.</p>
        </Card>
        <Card title="Payouts" flush>
          <DataTable rows={p} rowKey={(x) => x.id} pageSize={10} initialSort={{ key: 'date', dir: 'desc' }}
            empty={<EmptyState icon="wallet" title="No payouts yet">Payouts run on a schedule once bookings are completed.</EmptyState>}
            columns={[
              { key: 'date', header: 'Date', cell: (x) => dateTime(x.createdAt), sort: (x) => x.createdAt },
              { key: 'n', header: 'Bookings', cell: (x) => x.orderCount, align: 'right' },
              { key: 'amt', header: 'Amount', cell: (x) => <strong>{money(x.amountMinor)}</strong>, sort: (x) => x.amountMinor, align: 'right' },
              { key: 'status', header: 'Status', cell: (x) => <StatusBadge status={x.status} /> },
            ]} />
        </Card>
      </div>
    </>
  )
}
