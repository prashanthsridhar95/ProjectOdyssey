import { useState } from 'react'
import { dateTime, messageOf, money, titleCase, useAsync, useSession } from '@odyssey/api'
import type { Ticket } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Spinner, Table, statusTone, useToast } from '@odyssey/ui'

/** Customer reports on completed bookings. Upholding one can refund part or all of the booking. */
export function Support() {
  const { api } = useSession()
  const toast = useToast()
  const [status, setStatus] = useState('OPEN')
  const { data, error, loading, reload } = useAsync(() => api.admin.tickets(status || undefined), [status])
  const [open, setOpen] = useState<Ticket | null>(null)
  const [refund, setRefund] = useState('0')
  const [note, setNote] = useState('')

  async function resolve(uphold: boolean) {
    try {
      await api.admin.resolveTicket(open!.id, { uphold, refundMinor: uphold ? Math.round(parseFloat(refund || '0') * 100) : 0, note })
      toast(uphold ? 'Upheld' : 'Dismissed'); setOpen(null); setNote(''); reload()
    } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Support" subtitle="Reports from customers" actions={
        <div className="tabs">{[['OPEN', 'Open'], ['', 'All']].map(([k, l]) => <button key={l} className={status === k ? 'on' : ''} onClick={() => setStatus(k)}>{l}</button>)}</div>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No tickets.</Empty> : (
          <Table head={['Raised', 'Booking', 'Customer', 'Category', 'Report', 'Status']}>
            {data.map((t) => (
              <tr key={t.id} className="click" onClick={() => { setOpen(t); setRefund(String((t.orderTotalMinor ?? 0) / 100)); setNote('') }}>
                <td className="nowrap">{dateTime(t.createdAt)}</td><td>{t.orderCode}<div className="muted small">{t.locationName}</div></td><td>{t.customerName}</td><td>{titleCase(t.category)}</td>
                <td>{t.description.slice(0, 70)}</td><td><Badge tone={statusTone(t.status)}>{titleCase(t.status)}</Badge></td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
      {open && (
        <Modal title={`Report on ${open.orderCode}`} onClose={() => setOpen(null)}>
          <div className="stack">
            <p>{open.description}</p>
            <p className="muted small">{open.customerName} · {open.locationName} · booking total {money(open.orderTotalMinor ?? 0)}</p>
            {open.status === 'OPEN' ? (
              <>
                <Input label="Refund to customer (₹)" type="number" min="0" value={refund} onChange={(e) => setRefund(e.target.value)} hint="Comes out of the vendor's earnings" />
                <label className="field">Note<textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
                <div className="row spread"><Button onClick={() => resolve(false)} disabled={!note.trim()}>Dismiss</Button><Button variant="primary" onClick={() => resolve(true)} disabled={!note.trim()}>Uphold and refund</Button></div>
              </>
            ) : <p className="muted">{open.resolutionNote} {open.refundMinor ? `Refunded ${money(open.refundMinor)}.` : ''}</p>}
          </div>
        </Modal>
      )}
    </>
  )
}
