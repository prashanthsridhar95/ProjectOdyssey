import { useState } from 'react'
import { dateTime, messageOf, money, titleCase, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, PageHeader, Spinner, Table, statusTone, useToast } from '@odyssey/ui'

const TABS = [['batch', 'Payout batch'], ['payouts', 'Payouts'], ['collections', 'Collections'], ['refunds', 'Refunds'], ['ledger', 'Ledger']] as const

/** Money in and out: what customers paid, what is owed to vendors, what was refunded, and the double-entry ledger behind it. */
export function Payments() {
  const [tab, setTab] = useState<string>('batch')
  return (
    <>
      <PageHeader title="Payments" subtitle="Collections, vendor payouts, refunds and the ledger" actions={
        <div className="tabs">{TABS.map(([k, l]) => <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}</div>} />
      {tab === 'batch' && <BatchTab />}
      {tab === 'payouts' && <PayoutsTab />}
      {tab === 'collections' && <PaymentsTab kind="collections" />}
      {tab === 'refunds' && <PaymentsTab kind="refunds" />}
      {tab === 'ledger' && <LedgerTab />}
    </>
  )
}

function BatchTab() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.batch(), [])
  const [busy, setBusy] = useState(false)
  async function run() {
    setBusy(true)
    try { const made = await api.admin.runPayouts(); toast(made.length ? `${made.length} payout(s) processed, ${money(made.reduce((s, p) => s + p.amountMinor, 0))}` : 'Nothing to pay out right now'); reload() }
    catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }
  async function hold(id: string, on: boolean) {
    try { await api.admin.setHold(id, on); toast(on ? 'Payout held' : 'Hold released'); reload() } catch (e) { toast(messageOf(e), true) }
  }
  async function retry(id: string) {
    try { await api.admin.retryPayout(id); toast('Retried'); reload() } catch (e) { toast(messageOf(e), true) }
  }
  if (loading && !data) return <Spinner />
  if (error || !data) return <ErrorNote message={error} />
  return (
    <>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <div><div className="label">Vendors in this batch</div><div className="value">{data.vendors}</div></div>
        <div><div className="label">To pay out</div><div className="value">{money(data.netMinor)}</div></div>
        <div><div className="label">Commission kept</div><div className="value">{money(data.commissionMinor)}</div></div>
        <div className="row" style={{ padding: 14 }}><Button variant="primary" onClick={run} disabled={busy}>Run payouts now</Button></div>
      </div>
      <Card flush>
        {!data.rows.length ? <Empty>Nothing pending.</Empty> : (
          <Table head={['Vendor', 'Bookings', 'Service', 'Commission', 'Gateway fee', 'Net', 'Account', 'Status', '']}>
            {data.rows.map((r) => (
              <tr key={r.providerId}>
                <td><strong>{r.name}</strong></td><td>{r.bookings}</td><td>{money(r.serviceMinor)}</td><td>{money(r.commissionMinor)}</td><td>{money(r.gatewayFeeMinor)}</td><td><strong>{money(r.netMinor)}</strong></td>
                <td>{r.account ?? <span className="muted">no bank details</span>}</td>
                <td><Badge tone={statusTone(r.status)}>{titleCase(r.status)}</Badge>{r.failureReason && <div className="muted small">{r.failureReason}</div>}</td>
                <td className="nowrap">
                  {r.status === 'FAILED' && r.payoutId && <Button small onClick={() => retry(r.payoutId!)}>Retry</Button>}{' '}
                  {r.status === 'HELD' ? <Button small onClick={() => hold(r.providerId, false)}>Release</Button> : <Button small onClick={() => hold(r.providerId, true)}>Hold</Button>}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}

function PayoutsTab() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.payouts(), [])
  async function retry(id: string) { try { await api.admin.retryPayout(id); toast('Retried'); reload() } catch (e) { toast(messageOf(e), true) } }
  return (
    <>
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No payouts yet. Complete a booking, then run payouts.</Empty> : (
          <Table head={['Date', 'Vendor', 'Bookings', 'Amount', 'Status', '']}>
            {data.map((p) => (
              <tr key={p.id}><td className="nowrap">{dateTime(p.createdAt)}</td><td>{p.providerName ?? p.providerId}</td><td>{p.orderCount}</td><td>{money(p.amountMinor)}</td>
                <td><Badge tone={statusTone(p.status)}>{p.status.toLowerCase()}</Badge></td><td>{p.status === 'FAILED' && <Button small onClick={() => retry(p.id)}>Retry</Button>}</td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}

function PaymentsTab({ kind }: { kind: 'collections' | 'refunds' }) {
  const { api } = useSession()
  const { data, error, loading } = useAsync(() => (kind === 'collections' ? api.admin.collections() : api.admin.refunds()), [kind])
  return (
    <>
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>Nothing here yet.</Empty> : (
          <Table head={['Date', 'Booking', 'Method', 'Amount', 'Refunded', 'Status']}>
            {data.map((p) => (
              <tr key={p.id}><td className="nowrap">{dateTime(p.createdAt)}</td><td className="muted small">{p.orderId.slice(0, 8)}</td><td>{p.method}</td><td>{money(p.amountMinor)}</td><td>{p.refundedMinor ? money(p.refundedMinor) : '-'}</td><td><Badge tone={statusTone(p.status)}>{titleCase(p.status)}</Badge></td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}

function LedgerTab() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading } = useAsync(() => api.admin.ledger(), [])
  async function download() {
    try {
      const csv = await api.admin.ledgerCsv()
      const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })); a.download = 'ledger.csv'; a.click()
    } catch (e) { toast(messageOf(e), true) }
  }
  return (
    <>
      <div className="row spread" style={{ marginBottom: 12 }}><p className="muted small">Every movement of money is a pair of entries. Positive is a debit to the account, negative a credit.</p><Button onClick={download}>Export CSV</Button></div>
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No entries yet.</Empty> : (
          <Table head={['Date', 'Type', 'Account', 'Booking', 'Amount']}>
            {data.map((l) => <tr key={l.id}><td className="nowrap">{dateTime(l.createdAt)}</td><td>{titleCase(l.type)}</td><td>{titleCase(l.account)}</td><td className="muted small">{l.orderId?.slice(0, 8) ?? '-'}</td><td className="right">{money(l.amountMinor)}</td></tr>)}
          </Table>
        )}
      </Card>
    </>
  )
}
