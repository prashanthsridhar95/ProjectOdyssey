import { money, useAsync, useLabels, useSession } from '@odyssey/api'
import { Card, ErrorNote, PageHeader, Spinner, Stat, Table } from '@odyssey/ui'
import { Link } from 'react-router-dom'

export function Dashboard() {
  const { api } = useSession()
  const labels = useLabels()
  const { data, error, loading } = useAsync(() => api.admin.dashboard(), [])
  if (loading) return <Spinner />
  if (error || !data) return <ErrorNote message={error} />
  const rate = data.ordersTotal ? Math.round((data.cancelled / data.ordersTotal) * 100) : 0
  return (
    <>
      <PageHeader title="Dashboard" subtitle="How the marketplace is doing right now" />
      <div className="grid cols-4">
        <Stat label={`${labels.order}s today`} value={data.ordersToday} hint={`${data.ordersTotal} in total`} />
        <Stat label="Gross value (GMV)" value={money(data.gmvMinor)} />
        <Stat label="Commission earned" value={money(data.commissionMinor)} hint="after completed or no-show" />
        <Stat label="Cancellation rate" value={`${rate}%`} hint={`${data.cancelled} cancelled or rejected`} />
        <Stat label="Completed" value={data.completed} />
        <Stat label="Customers" value={data.customers} />
      </div>
      <div style={{ height: 20 }} />
      <Card flush>
        <div style={{ padding: '16px 18px 4px' }}><h2>{labels.provider}s by status</h2></div>
        <Table head={['Status', 'Count']}>
          {Object.entries(data.providersByStatus).map(([s, n]) => (
            <tr key={s}><td><Link to={`/vendors?status=${s}`}>{s.replace(/_/g, ' ').toLowerCase()}</Link></td><td>{n}</td></tr>
          ))}
        </Table>
      </Card>
    </>
  )
}
