import { dateTime, money, messageOf, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, PageHeader, Spinner, Table, statusTone, useToast } from '@odyssey/ui'
import { useState } from 'react'

export function Payouts() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.payouts(), [])
  const [busy, setBusy] = useState(false)

  async function run() {
    setBusy(true)
    try {
      const made = await api.admin.runPayouts()
      toast(made.length ? `${made.length} payout(s) processed, ${money(made.reduce((s, p) => s + p.amountMinor, 0))} in total` : 'Nothing to pay out right now')
      reload()
    } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title="Payouts" subtitle="Completed bookings, minus commission, paid to each vendor's bank account" actions={<Button variant="primary" onClick={run} disabled={busy}>Run payouts now</Button>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No payouts yet. Complete a booking, then run payouts.</Empty> : (
          <Table head={['Date', 'Vendor', 'Bookings', 'Amount', 'Status']}>
            {data.map((p) => (
              <tr key={p.id}><td className="nowrap">{dateTime(p.createdAt)}</td><td>{p.providerName ?? p.providerId}</td><td>{p.orderCount}</td><td>{money(p.amountMinor)}</td><td><Badge tone={statusTone(p.status)}>{p.status.toLowerCase()}</Badge></td></tr>
            ))}
          </Table>
        )}
      </Card>
      <p className="muted small" style={{ marginTop: 12 }}>Vendors without bank details are skipped. In the proof of concept the bank transfer is simulated.</p>
    </>
  )
}
