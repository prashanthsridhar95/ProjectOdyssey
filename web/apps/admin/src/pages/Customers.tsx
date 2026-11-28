import { useState } from 'react'
import { dateOnly, messageOf, money, phone, relative, useAsync, useLabels, useSession } from '@odyssey/api'
import { Button, Card, DataTable, EmptyState, ErrorNote, PageHeader, SearchInput, StatusBadge, useConfirm, useDebounced, useToast } from '@odyssey/ui'

export function Customers() {
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const [q, setQ] = useState('')
  const query = useDebounced(q.trim(), 300)
  const { data, error, loading, reload } = useAsync(() => api.admin.customers(query || undefined), [query])
  const c = labels.customer.toLowerCase()

  async function toggle(id: string, name: string, active: boolean) {
    const r = await confirm(active
      ? { title: `Unblock ${name}?`, body: 'They can sign in and book again.', confirmLabel: 'Unblock' }
      : { title: `Block ${name}?`, tone: 'danger', confirmLabel: 'Block', body: 'They are signed out and cannot book. Their existing bookings are not cancelled.' })
    if (!r) return
    try { await api.admin.setCustomerActive(id, active); toast(active ? `${name} unblocked` : `${name} blocked`); reload() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title={`${labels.customer}s`} subtitle={`People who book through the app. Search runs across every ${c}.`} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(x) => x.id} exportName={`${c}s`}
          toolbar={<SearchInput value={q} onChange={setQ} placeholder="Name or phone number" />}
          initialSort={{ key: 'last', dir: 'desc' }}
          empty={query ? <EmptyState icon="search" title={`No ${c}s match "${query}"`} action={<Button small onClick={() => setQ('')}>Clear search</Button>} /> : <EmptyState icon="users" title={`No ${c}s yet`}>They appear when people sign in to the app.</EmptyState>}
          columns={[
            { key: 'name', header: 'Name', cell: (x) => <strong>{x.name}</strong>, sort: (x) => x.name, csv: (x) => x.name },
            { key: 'phone', header: 'Phone', cell: (x) => <span className="nowrap">{phone(x.phone)}</span>, csv: (x) => x.phone },
            { key: 'bookings', header: `${labels.order}s`, cell: (x) => x.bookings, sort: (x) => x.bookings, csv: (x) => x.bookings, align: 'right' },
            { key: 'spend', header: 'Lifetime spend', cell: (x) => money(x.spendMinor), sort: (x) => x.spendMinor, csv: (x) => x.spendMinor / 100, align: 'right' },
            { key: 'last', header: `Last ${labels.order.toLowerCase()}`, cell: (x) => (x.lastBooking ? <span title={dateOnly(x.lastBooking)}>{relative(x.lastBooking)}</span> : <span className="faint">Never</span>), sort: (x) => x.lastBooking ?? '', csv: (x) => x.lastBooking ?? '' },
            { key: 'status', header: 'Status', cell: (x) => <StatusBadge status={x.active ? 'ACTIVE' : 'BLOCKED'} />, sort: (x) => (x.active ? 1 : 0), csv: (x) => (x.active ? 'active' : 'blocked') },
            { key: 'act', header: '', cell: (x) => <Button small variant={x.active ? 'ghost' : undefined} onClick={() => toggle(x.id, x.name, !x.active)}>{x.active ? 'Block' : 'Unblock'}</Button>, align: 'right' },
          ]} />
      </Card>
    </>
  )
}
