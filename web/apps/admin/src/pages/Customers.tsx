import { useState } from 'react'
import { dateOnly, messageOf, money, useAsync, useLabels, useSession } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, PageHeader, Spinner, Table, useToast } from '@odyssey/ui'

export function Customers() {
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const [q, setQ] = useState('')
  const { data, error, loading, reload } = useAsync(() => api.admin.customers(q || undefined), [q])
  async function toggle(id: string, active: boolean) { try { await api.admin.setCustomerActive(id, active); reload() } catch (e) { toast(messageOf(e), true) } }
  return (
    <>
      <PageHeader title={`${labels.customer}s`} subtitle="People who book through the app" actions={<Input label="Search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or phone" />} />
      <ErrorNote message={error} />
      <Card flush>
        {loading && !data ? <Spinner /> : !data?.length ? <Empty>No {labels.customer.toLowerCase()}s found.</Empty> : (
          <Table head={['Name', 'Phone', labels.order + 's', 'Spend', 'Last booking', 'Status', '']}>
            {data.map((c) => (
              <tr key={c.id}><td><strong>{c.name}</strong></td><td>{c.phone}</td><td>{c.bookings}</td><td>{money(c.spendMinor)}</td><td>{c.lastBooking ? dateOnly(c.lastBooking) : '-'}</td>
                <td><Badge tone={c.active ? 'good' : 'bad'}>{c.active ? 'Active' : 'Blocked'}</Badge></td>
                <td><Button small variant={c.active ? 'danger' : undefined} onClick={() => toggle(c.id, !c.active)}>{c.active ? 'Block' : 'Unblock'}</Button></td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}
