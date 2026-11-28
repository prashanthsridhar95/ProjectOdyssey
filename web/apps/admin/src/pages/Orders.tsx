import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { bpsToPercent, dateTime, messageOf, money, phone, recent, relative, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Order } from '@odyssey/api'
import { Badge, Button, Card, Chips, DataTable, DescriptionList, Drawer, EmptyState, ErrorNote, PageHeader, StatusBadge, useConfirm, useToast } from '@odyssey/ui'

/** Groups of states an operator thinks in, built from the vertical's own flow so a new vertical needs no change here. */
function bucketOf(o: Order, paid: string, completed: string): string {
  if (o.status === completed) return 'done'
  if (['CANCELLED', 'REJECTED', 'EXPIRED', 'NO_SHOW'].includes(o.status)) return 'closed'
  if (o.status === 'PENDING_PAYMENT') return 'unpaid'
  if (o.status === paid) return 'waiting'
  return 'active'
}

export function OrdersPage() {
  const { api, config } = useSession()
  const labels = useLabels()
  const { data, error, loading, reload } = useAsync(() => api.admin.orders(), [])
  const [bucket, setBucket] = useState('all')
  const [open, setOpen] = useState<Order | null>(null)
  const flow = config?.config.flow
  const of = (o: Order) => bucketOf(o, flow?.paid ?? 'PAID', flow?.completed ?? 'COMPLETED')

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: data?.length ?? 0 }
    for (const o of data ?? []) c[of(o)] = (c[of(o)] ?? 0) + 1
    return c
  }, [data]) // eslint-disable-line react-hooks/exhaustive-deps
  const rows = useMemo(() => (bucket === 'all' ? data : data?.filter((o) => of(o) === bucket)), [data, bucket]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <PageHeader title={`${labels.order}s`} subtitle={`Every ${labels.order.toLowerCase()} across the marketplace, newest first. The latest 200 are loaded.`} actions={<Button icon="refresh" onClick={reload} busy={loading && !!data}>Refresh</Button>} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable
          rows={rows} loading={loading} rowKey={(o) => o.id} onRowClick={setOpen} selectedKey={open?.id} exportName="bookings"
          search={(o) => `${o.code} ${o.customerName} ${o.customerPhone} ${o.locationName} ${o.assetLabel ?? ''}`}
          searchPlaceholder="Code, customer, phone or outlet"
          toolbar={<Chips label="Status" value={bucket} onChange={setBucket} options={[
            { value: 'all', label: 'All', count: counts.all }, { value: 'waiting', label: 'Waiting to accept', count: counts.waiting ?? 0 },
            { value: 'active', label: 'Upcoming and in progress', count: counts.active ?? 0 }, { value: 'done', label: 'Completed', count: counts.done ?? 0 },
            { value: 'closed', label: 'Cancelled or no-show', count: counts.closed ?? 0 }, { value: 'unpaid', label: 'Unpaid', count: counts.unpaid ?? 0 },
          ]} />}
          empty={<EmptyState icon="calendar" title={`No ${labels.order.toLowerCase()}s here`}>{bucket === 'all' ? `${labels.order}s appear as soon as customers book.` : 'Try another status.'}</EmptyState>}
          columns={[
            { key: 'code', header: 'Code', cell: (o) => <><strong className="mono">{o.code}</strong><div className="sub">{o.source === 'WALK_IN' ? 'Walk-in' : 'App'}</div></>, sort: (o) => o.code, csv: (o) => o.code },
            { key: 'when', header: 'When', cell: (o) => <><span className="nowrap">{dateTime(o.startsAt)}</span>{recent(o.startsAt) && <div className="sub">{recent(o.startsAt)}</div>}</>, sort: (o) => o.startsAt, csv: (o) => o.startsAt },
            { key: 'customer', header: labels.customer, cell: (o) => <>{o.customerName}<div className="sub">{phone(o.customerPhone)}</div></>, sort: (o) => o.customerName, csv: (o) => o.customerName },
            { key: 'location', header: labels.location, cell: (o) => o.locationName, sort: (o) => o.locationName, csv: (o) => o.locationName },
            { key: 'total', header: 'Total', cell: (o) => money(o.totalMinor), sort: (o) => o.totalMinor, csv: (o) => o.totalMinor / 100, align: 'right' },
            { key: 'commission', header: 'Commission', cell: (o) => <span className="muted">{money(o.commissionMinor)}</span>, sort: (o) => o.commissionMinor ?? 0, csv: (o) => (o.commissionMinor ?? 0) / 100, align: 'right' },
            { key: 'status', header: 'Status', cell: (o) => <StatusBadge status={o.status} label={o.statusLabel} />, sort: (o) => o.statusLabel, csv: (o) => o.statusLabel },
          ]}
        />
      </Card>
      {open && <OrderDrawer order={open} onClose={() => setOpen(null)} onChanged={(o) => { setOpen(o); reload() }} />}
    </>
  )
}

export function OrderDrawer({ order: o, onClose, onChanged }: { order: Order; onClose: () => void; onChanged: (o: Order) => void }) {
  const { api, config } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const [busy, setBusy] = useState(false)
  const variant = config?.config.variants.options.find((v) => v.key === o.variantKey)?.label ?? o.variantKey

  async function cancel() {
    const r = await confirm({
      title: `Cancel ${o.code} and refund?`, tone: 'danger', confirmLabel: 'Cancel and refund',
      body: <>The {labels.customer.toLowerCase()} gets <strong>{money(o.totalMinor)}</strong> back in full, the slot opens again and the {labels.provider.toLowerCase()} is told. This cannot be undone.</>,
      reason: { label: 'Reason', required: true, placeholder: 'For example: outlet closed unexpectedly', hint: 'Shown to the customer and the vendor, and kept in the audit log.' },
    })
    if (!r) return
    setBusy(true)
    try { onChanged(await api.orders.act(o.id, 'ADMIN_CANCEL', r.reason)); toast('Cancelled and refunded') } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }

  return (
    <Drawer title={<span className="mono">{o.code}</span>} subtitle={`${o.locationName} · booked ${relative(o.createdAt)}`} onClose={onClose}
      meta={<><StatusBadge status={o.status} label={o.statusLabel} /><Badge>{o.source === 'WALK_IN' ? 'Walk-in' : 'Booked in app'}</Badge>{o.reviewed && <Badge tone="info">Reviewed</Badge>}</>}
      footer={o.actions.includes('ADMIN_CANCEL') ? <Button variant="danger" icon="ban" busy={busy} onClick={cancel}>Cancel and refund</Button> : <span className="muted small">No actions available in this state.</span>}>
      <section>
        <div className="section-title">Appointment</div>
        <DescriptionList items={[
          ['When', `${dateTime(o.startsAt)} – ${new Date(o.endsAt).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Kolkata' })}`],
          [labels.location, <Link to={`/vendors/${o.providerId}`}>{o.locationName}</Link>],
          [config?.config.variants.name ?? 'Type', variant],
          ...(o.assetLabel ? [['Vehicle', o.assetLabel] as [string, string]] : []),
        ]} />
      </section>
      <section>
        <div className="section-title">{labels.customer}</div>
        <DescriptionList items={[['Name', o.customerName], ['Phone', <a href={`tel:${o.customerPhone}`}>{phone(o.customerPhone)}</a>]]} />
      </section>
      <section>
        <div className="section-title">Charges</div>
        <table className="money-lines"><tbody>
          {o.lines.map((l) => <tr key={l.itemId}><td>{l.name} <span className="muted small">· {l.durationMinutes} min</span></td><td>{money(l.priceMinor)}</td></tr>)}
          <tr className="sub"><td>Tax</td><td>{money(o.taxMinor)}</td></tr>
          <tr className="sub"><td>Convenience fee</td><td>{money(o.feeMinor)}</td></tr>
          <tr className="total"><td>Customer paid</td><td>{money(o.totalMinor)}</td></tr>
          <tr className="sub"><td>Commission {o.commissionBps !== undefined && `(${bpsToPercent(o.commissionBps)} of service)`}</td><td>{money(o.commissionMinor)}</td></tr>
          <tr className="sub"><td>{labels.provider} share of service</td><td>{money(o.subtotalMinor - (o.commissionMinor ?? 0))}</td></tr>
        </tbody></table>
      </section>
      {o.cancellationReason && <section><div className="section-title">Cancellation</div><p>{o.cancellationReason}</p></section>}
      <p className="faint tiny mono">ID {o.id}</p>
    </Drawer>
  )
}

