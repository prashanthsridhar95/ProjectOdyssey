import { useState } from 'react'
import { dateTime, money, useAsync, useLabels, useSession, messageOf } from '@odyssey/api'
import type { Order } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Modal, PageHeader, Select, Spinner, Table, statusTone, useToast } from '@odyssey/ui'

export function OrdersPage() {
  const { api, config } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const [status, setStatus] = useState('')
  const [cancelFor, setCancelFor] = useState<Order | null>(null)
  const [reason, setReason] = useState('')
  const { data, error, loading, reload } = useAsync(() => api.admin.orders(status || undefined), [status])
  const states = config?.config.flow.states ?? []

  async function cancel() {
    if (!cancelFor) return
    try { await api.orders.act(cancelFor.id, 'ADMIN_CANCEL', reason); toast('Cancelled and refunded'); setCancelFor(null); setReason(''); reload() }
    catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title={`${labels.order}s`} actions={
        <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All</option>{states.map((s) => <option key={s.name} value={s.name}>{s.label}</option>)}
        </Select>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No {labels.order.toLowerCase()}s yet.</Empty> : (
          <Table head={['Code', labels.customer, labels.location, 'When', 'Total', 'Commission', 'Status', '']}>
            {data.map((o) => (
              <tr key={o.id}>
                <td><strong>{o.code}</strong><div className="muted small">{o.source === 'WALK_IN' ? 'walk-in' : 'app'}</div></td>
                <td>{o.customerName}<div className="muted small">{o.customerPhone}</div></td>
                <td>{o.locationName}</td>
                <td className="nowrap">{dateTime(o.startsAt)}</td>
                <td>{money(o.totalMinor)}</td>
                <td>{money(o.commissionMinor)}</td>
                <td><Badge tone={statusTone(o.status)}>{o.statusLabel}</Badge></td>
                <td className="right">{o.actions.includes('ADMIN_CANCEL') && <Button small variant="danger" onClick={() => setCancelFor(o)}>Cancel + refund</Button>}</td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
      {cancelFor && (
        <Modal title={`Cancel ${cancelFor.code}`} onClose={() => setCancelFor(null)}>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); void cancel() }}>
            <p>The customer is refunded {money(cancelFor.totalMinor)} in full and the slot is released.</p>
            <label className="field">Reason (required)<textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} /></label>
            <div className="row spread"><Button type="button" onClick={() => setCancelFor(null)}>Keep it</Button><Button variant="danger" disabled={!reason.trim()}>Cancel and refund</Button></div>
          </form>
        </Modal>
      )}
    </>
  )
}
