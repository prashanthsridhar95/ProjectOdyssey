import { useState } from 'react'
import { dateTime, messageOf, money, relative, titleCase, useAsync, useSession } from '@odyssey/api'
import type { Ticket } from '@odyssey/api'
import { Badge, Button, Card, DataTable, DescriptionList, Drawer, EmptyState, ErrorNote, Input, PageHeader, StatusBadge, Tabs, TextArea, useToast } from '@odyssey/ui'

/** Customer reports on completed bookings. Upholding one can refund part or all of the booking. */
export function Support({ onChange }: { onChange?: () => void }) {
  const { api } = useSession()
  const [status, setStatus] = useState<'OPEN' | ''>('OPEN')
  const { data, error, loading, reload } = useAsync(() => api.admin.tickets(status || undefined), [status])
  const [open, setOpen] = useState<Ticket | null>(null)
  const overdue = (t: Ticket) => t.status === 'OPEN' && Date.now() - new Date(t.createdAt).getTime() > 24 * 3600_000

  return (
    <>
      <PageHeader title="Support" subtitle="Reports from customers about completed bookings. We promise a reply within 24 hours." />
      <Tabs value={status} onChange={setStatus} tabs={[{ value: 'OPEN', label: 'Open', count: status === 'OPEN' ? data?.length : undefined }, { value: '', label: 'All' }]} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(t) => t.id} onRowClick={setOpen} selectedKey={open?.id} exportName="tickets"
          search={(t) => `${t.orderCode ?? ''} ${t.customerName ?? ''} ${t.locationName ?? ''} ${t.description} ${t.category}`} searchPlaceholder="Search reports"
          initialSort={{ key: 'raised', dir: 'asc' }}
          empty={status === 'OPEN' ? <EmptyState icon="checkCircle" title="Inbox zero">No open reports. Nice work.</EmptyState> : <EmptyState icon="lifebuoy" title="No reports yet" />}
          columns={[
            { key: 'raised', header: 'Raised', cell: (t) => <><span className="nowrap">{relative(t.createdAt)}</span>{overdue(t) && <div><Badge tone="bad">Over 24 h</Badge></div>}</>, sort: (t) => t.createdAt, csv: (t) => t.createdAt },
            { key: 'booking', header: 'Booking', cell: (t) => <><strong className="mono">{t.orderCode}</strong><div className="sub">{t.locationName}</div></>, sort: (t) => t.orderCode ?? '', csv: (t) => t.orderCode ?? '' },
            { key: 'customer', header: 'Customer', cell: (t) => t.customerName, sort: (t) => t.customerName ?? '', csv: (t) => t.customerName ?? '' },
            { key: 'category', header: 'Category', cell: (t) => titleCase(t.category), sort: (t) => t.category, csv: (t) => t.category },
            { key: 'report', header: 'Report', cell: (t) => <span className="truncate" style={{ display: 'block', maxWidth: 320 }}>{t.description}</span>, csv: (t) => t.description },
            { key: 'status', header: 'Status', cell: (t) => <StatusBadge status={t.status} />, sort: (t) => t.status, csv: (t) => t.status },
          ]} />
      </Card>
      {open && <TicketDrawer ticket={open} onClose={() => setOpen(null)} onDone={() => { setOpen(null); reload(); onChange?.() }} />}
    </>
  )
}

function TicketDrawer({ ticket: t, onClose, onDone }: { ticket: Ticket; onClose: () => void; onDone: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const total = t.totalMinor ?? 0
  const [refund, setRefund] = useState(String(total / 100))
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState<'uphold' | 'dismiss' | null>(null)
  const refundMinor = Math.round(parseFloat(refund || '0') * 100)
  const refundBad = Number.isNaN(refundMinor) || refundMinor < 0 || refundMinor > total

  async function resolve(uphold: boolean) {
    setBusy(uphold ? 'uphold' : 'dismiss')
    try {
      await api.admin.resolveTicket(t.id, { uphold, refundMinor: uphold ? refundMinor : 0, note: note.trim() })
      toast(uphold ? (refundMinor ? `Upheld and ${money(refundMinor)} refunded` : 'Upheld') : 'Dismissed'); onDone()
    } catch (e) { toast(messageOf(e), true) } finally { setBusy(null) }
  }

  return (
    <Drawer title={`Report on ${t.orderCode}`} subtitle={`${titleCase(t.category)} · raised ${dateTime(t.createdAt)}`} onClose={onClose} meta={<StatusBadge status={t.status} />}
      footer={t.status === 'OPEN' ? <>
        <Button onClick={() => resolve(false)} disabled={!note.trim()} busy={busy === 'dismiss'}>Dismiss</Button>
        <Button variant="primary" onClick={() => resolve(true)} disabled={!note.trim() || refundBad} busy={busy === 'uphold'}>{refundMinor ? `Uphold and refund ${money(refundMinor)}` : 'Uphold'}</Button>
      </> : undefined}>
      <section><div className="section-title">What the customer said</div><p style={{ whiteSpace: 'pre-wrap' }}>{t.description}</p></section>
      {t.photos.length > 0 && <section><div className="section-title">Photos</div><div className="row wrap">{t.photos.map((src, i) => <a key={i} href={src} target="_blank" rel="noreferrer"><img src={src} alt={`Customer photo ${i + 1}`} style={{ width: 110, height: 110, objectFit: 'cover', border: '1px solid var(--c-border)' }} /></a>)}</div></section>}
      <section><div className="section-title">Booking</div><DescriptionList items={[['Customer', t.customerName], ['Outlet', t.locationName], ['Booking total', money(total)]]} /></section>
      {t.status === 'OPEN' ? (
        <section className="stack">
          <div className="section-title" style={{ marginBottom: 0 }}>Resolution</div>
          <Input label="Refund to the customer (₹)" type="number" min="0" max={total / 100} step="1" value={refund} onChange={(e) => setRefund(e.target.value)}
            error={refundBad ? `Between ₹0 and ${money(total)}` : null} hint="Only applies if you uphold. It comes out of the vendor's earnings for this booking." />
          <div className="row tight">{[0, 0.5, 1].map((f) => <button type="button" key={f} className="chip" onClick={() => setRefund(String(Math.round((total * f) / 100)))}>{f === 0 ? 'No refund' : f === 1 ? 'Full refund' : 'Half'}</button>)}</div>
          <TextArea label="Note" required value={note} onChange={(e) => setNote(e.target.value)} placeholder="What you found and what you decided" hint="The customer sees this." />
        </section>
      ) : (
        <section><div className="section-title">Resolution</div><p>{t.resolutionNote}</p>{t.refundMinor > 0 && <p className="muted small" style={{ marginTop: 6 }}>Refunded {money(t.refundMinor)}.</p>}</section>
      )}
    </Drawer>
  )
}
