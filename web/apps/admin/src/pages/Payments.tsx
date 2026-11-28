import { useState } from 'react'
import { Link } from 'react-router-dom'
import { dateOnly, dateTime, messageOf, money, recent, titleCase, useAsync, useSession } from '@odyssey/api'
import { Button, Card, DataTable, EmptyState, ErrorNote, KpiStrip, Notice, PageHeader, StatusBadge, Tabs, downloadText, useConfirm, useToast } from '@odyssey/ui'

type Tab = 'batch' | 'payouts' | 'collections' | 'refunds' | 'ledger'

/** Money in and out: what customers paid, what is owed to vendors, what was refunded, and the double-entry ledger behind it. */
export function Payments() {
  const [tab, setTab] = useState<Tab>('batch')
  return (
    <>
      <PageHeader title="Payments" subtitle="Collections, vendor payouts, refunds and the ledger behind them" />
      <Tabs value={tab} onChange={setTab} tabs={[{ value: 'batch', label: 'Next payout' }, { value: 'payouts', label: 'Payout history' }, { value: 'collections', label: 'Collections' }, { value: 'refunds', label: 'Refunds' }, { value: 'ledger', label: 'Ledger' }]} />
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
  const confirm = useConfirm()
  const { data, error, loading, reload } = useAsync(() => api.admin.batch(), [])
  const [busy, setBusy] = useState(false)
  const payable = data?.rows.filter((r) => r.status !== 'HELD' && r.account && r.netMinor > 0) ?? []
  const noBank = data?.rows.filter((r) => !r.account).length ?? 0
  const held = data?.rows.filter((r) => r.status === 'HELD').length ?? 0

  async function run() {
    const total = payable.reduce((s, r) => s + r.netMinor, 0)
    const ok = await confirm({
      title: `Pay out ${money(total)} to ${payable.length} vendor${payable.length === 1 ? '' : 's'}?`, confirmLabel: 'Run payouts',
      body: <>Transfers go to each vendor's bank account on file and cannot be pulled back from here.{held ? ` ${held} held vendor${held === 1 ? ' is' : 's are'} left out.` : ''}{noBank ? ` ${noBank} without bank details ${noBank === 1 ? 'is' : 'are'} left out.` : ''}</>,
    })
    if (!ok) return
    setBusy(true)
    try { const made = await api.admin.runPayouts(); toast(made.length ? `${made.length} payout${made.length === 1 ? '' : 's'} sent, ${money(made.reduce((s, p) => s + p.amountMinor, 0))}` : 'Nothing was due'); reload() }
    catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }
  async function hold(id: string, name: string, on: boolean) {
    const ok = await confirm({ title: on ? `Hold payouts to ${name}?` : `Release the hold on ${name}?`, tone: on ? 'danger' : 'primary', confirmLabel: on ? 'Hold' : 'Release', body: on ? 'They are left out of payout runs until you release the hold.' : 'They are included in the next run.' })
    if (!ok) return
    try { await api.admin.setHold(id, on); toast(on ? 'Payout held' : 'Hold released'); reload() } catch (e) { toast(messageOf(e), true) }
  }
  async function retry(id: string) {
    try { await api.admin.retryPayout(id); toast('Payout sent again'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      {data && (
        <>
          <KpiStrip items={[
            { label: 'Vendors in this batch', icon: 'store', value: data.vendors, foot: data.from && data.to ? `${dateOnly(data.from)} – ${dateOnly(data.to)}` : 'Every completed booking not yet paid' },
            { label: 'To pay out', icon: 'wallet', value: money(data.netMinor), foot: 'After commission and gateway fees' },
            { label: 'Commission kept', icon: 'percent', value: money(data.commissionMinor) },
          ]} />
          <div className="spacer" />
          {noBank > 0 && <><Notice tone="warn">{noBank} vendor{noBank === 1 ? ' has' : 's have'} no bank account yet and will be skipped. Ask them to add one in the vendor app.</Notice><div className="spacer" /></>}
        </>
      )}
      <Card title="Due in this batch" flush actions={<Button variant="primary" icon="wallet" onClick={run} busy={busy} disabled={!payable.length}>Run payouts</Button>}>
        <DataTable rows={data?.rows} loading={loading} rowKey={(r) => r.providerId} exportName="payout-batch" search={(r) => r.name}
          empty={<EmptyState icon="checkCircle" title="Nothing is due">Completed bookings become payable here. Everything owed has been paid.</EmptyState>}
          columns={[
            { key: 'name', header: 'Vendor', cell: (r) => <Link to={`/vendors/${r.providerId}`}><strong>{r.name}</strong></Link>, sort: (r) => r.name, csv: (r) => r.name },
            { key: 'bookings', header: 'Bookings', cell: (r) => r.bookings, sort: (r) => r.bookings, csv: (r) => r.bookings, align: 'right' },
            { key: 'service', header: 'Service value', cell: (r) => money(r.serviceMinor), sort: (r) => r.serviceMinor, csv: (r) => r.serviceMinor / 100, align: 'right' },
            { key: 'commission', header: 'Commission', cell: (r) => <span className="muted">−{money(r.commissionMinor)}</span>, csv: (r) => r.commissionMinor / 100, align: 'right' },
            { key: 'fee', header: 'Gateway fee', cell: (r) => <span className="muted">−{money(r.gatewayFeeMinor)}</span>, csv: (r) => r.gatewayFeeMinor / 100, align: 'right' },
            { key: 'net', header: 'Net payout', cell: (r) => <strong>{money(r.netMinor)}</strong>, sort: (r) => r.netMinor, csv: (r) => r.netMinor / 100, align: 'right' },
            { key: 'account', header: 'Account', cell: (r) => (r.account ? <span className="mono small">{r.account}</span> : <span className="badge warn">No bank details</span>), csv: (r) => r.account ?? '' },
            { key: 'status', header: 'Status', cell: (r) => <><StatusBadge status={r.status} />{r.failureReason && <div className="sub">{r.failureReason}</div>}</>, sort: (r) => r.status, csv: (r) => r.status },
            { key: 'act', header: '', cell: (r) => (
              <span className="row tight" style={{ justifyContent: 'flex-end' }}>
                {r.status === 'FAILED' && r.payoutId && <Button small icon="refresh" onClick={() => retry(r.payoutId!)}>Retry</Button>}
                {r.status === 'HELD' ? <Button small onClick={() => hold(r.providerId, r.name, false)}>Release</Button> : <Button small variant="ghost" onClick={() => hold(r.providerId, r.name, true)}>Hold</Button>}
              </span>
            ), align: 'right' },
          ]} />
      </Card>
    </>
  )
}

function PayoutsTab() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.payouts(), [])
  async function retry(id: string) { try { await api.admin.retryPayout(id); toast('Payout sent again'); reload() } catch (e) { toast(messageOf(e), true) } }
  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(p) => p.id} exportName="payouts" search={(p) => `${p.providerName ?? ''} ${p.status}`} searchPlaceholder="Search by vendor"
          initialSort={{ key: 'date', dir: 'desc' }}
          empty={<EmptyState icon="wallet" title="No payouts yet">They appear here after the first payout run.</EmptyState>}
          columns={[
            { key: 'date', header: 'Date', cell: (p) => <><span className="nowrap">{dateTime(p.createdAt)}</span>{recent(p.createdAt) && <div className="sub">{recent(p.createdAt)}</div>}</>, sort: (p) => p.createdAt, csv: (p) => p.createdAt },
            { key: 'vendor', header: 'Vendor', cell: (p) => <Link to={`/vendors/${p.providerId}`}>{p.providerName ?? p.providerId}</Link>, sort: (p) => p.providerName ?? '', csv: (p) => p.providerName ?? p.providerId },
            { key: 'orders', header: 'Bookings', cell: (p) => p.orderCount, sort: (p) => p.orderCount, csv: (p) => p.orderCount, align: 'right' },
            { key: 'amount', header: 'Amount', cell: (p) => <strong>{money(p.amountMinor)}</strong>, sort: (p) => p.amountMinor, csv: (p) => p.amountMinor / 100, align: 'right' },
            { key: 'status', header: 'Status', cell: (p) => <StatusBadge status={p.status} />, sort: (p) => p.status, csv: (p) => p.status },
            { key: 'act', header: '', cell: (p) => p.status === 'FAILED' && <Button small icon="refresh" onClick={() => retry(p.id)}>Retry</Button>, align: 'right' },
          ]} />
      </Card>
    </>
  )
}

function PaymentsTab({ kind }: { kind: 'collections' | 'refunds' }) {
  const { api } = useSession()
  const { data, error, loading, reload } = useAsync(() => (kind === 'collections' ? api.admin.collections() : api.admin.refunds()), [kind])
  const total = (data ?? []).reduce((s, p) => s + (kind === 'refunds' ? p.refundedMinor : p.amountMinor), 0)
  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      {data && data.length > 0 && <><KpiStrip items={[{ label: kind === 'refunds' ? 'Refunded' : 'Collected', icon: kind === 'refunds' ? 'refresh' : 'wallet', value: money(total), foot: `${data.length} payment${data.length === 1 ? '' : 's'}` }]} /><div className="spacer" /></>}
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(p) => p.id} exportName={kind} search={(p) => `${p.orderId} ${p.method} ${p.status}`} searchPlaceholder="Search by booking ID or method"
          initialSort={{ key: 'date', dir: 'desc' }}
          empty={<EmptyState icon={kind === 'refunds' ? 'refresh' : 'wallet'} title={kind === 'refunds' ? 'No refunds' : 'No payments yet'}>{kind === 'refunds' ? 'Refunds from cancellations and upheld reports show here.' : 'Customer payments show here as bookings are paid.'}</EmptyState>}
          columns={[
            { key: 'date', header: 'Date', cell: (p) => <span className="nowrap">{dateTime(p.createdAt)}</span>, sort: (p) => p.createdAt, csv: (p) => p.createdAt },
            { key: 'order', header: 'Booking ID', cell: (p) => <span className="mono small muted">{p.orderId.slice(0, 8)}</span>, csv: (p) => p.orderId },
            { key: 'method', header: 'Method', cell: (p) => titleCase(p.method), sort: (p) => p.method, csv: (p) => p.method },
            { key: 'amount', header: 'Amount', cell: (p) => money(p.amountMinor), sort: (p) => p.amountMinor, csv: (p) => p.amountMinor / 100, align: 'right' },
            { key: 'refunded', header: 'Refunded', cell: (p) => (p.refundedMinor ? money(p.refundedMinor) : <span className="faint">-</span>), sort: (p) => p.refundedMinor, csv: (p) => p.refundedMinor / 100, align: 'right' },
            { key: 'status', header: 'Status', cell: (p) => <StatusBadge status={p.status} />, sort: (p) => p.status, csv: (p) => p.status },
          ]} />
      </Card>
    </>
  )
}

function LedgerTab() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.ledger(), [])
  const [busy, setBusy] = useState(false)
  async function download() {
    setBusy(true)
    try { downloadText(`ledger-${new Date().toISOString().slice(0, 10)}.csv`, await api.admin.ledgerCsv()) } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }
  const balance = (data ?? []).reduce((s, l) => s + l.amountMinor, 0)
  return (
    <>
      <Notice tone="info" action={<Button small icon="download" onClick={download} busy={busy}>Full ledger (CSV)</Button>}>
        Every movement of money is a balanced pair of entries: positive is a debit to the account, negative a credit. The entries below sum to <strong className="num">{money(balance)}</strong>{balance === 0 ? ', as they should.' : '.'}
      </Notice>
      <div className="spacer" />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(l) => l.id} search={(l) => `${l.type} ${l.account} ${l.orderId ?? ''}`} searchPlaceholder="Search type, account or booking"
          initialSort={{ key: 'date', dir: 'desc' }} pageSize={50}
          empty={<EmptyState icon="layers" title="No entries yet">Entries are written when customers pay, bookings settle, refunds go out and payouts run.</EmptyState>}
          columns={[
            { key: 'date', header: 'Date', cell: (l) => <span className="nowrap">{dateTime(l.createdAt)}</span>, sort: (l) => l.createdAt },
            { key: 'type', header: 'Type', cell: (l) => titleCase(l.type), sort: (l) => l.type },
            { key: 'account', header: 'Account', cell: (l) => <span className="mono small">{l.account}</span>, sort: (l) => l.account },
            { key: 'order', header: 'Booking', cell: (l) => <span className="mono small muted">{l.orderId?.slice(0, 8) ?? '-'}</span> },
            { key: 'amount', header: 'Amount', cell: (l) => <span style={{ color: l.amountMinor < 0 ? 'var(--c-muted)' : undefined }}>{money(l.amountMinor)}</span>, sort: (l) => l.amountMinor, align: 'right' },
          ]} />
      </Card>
    </>
  )
}
