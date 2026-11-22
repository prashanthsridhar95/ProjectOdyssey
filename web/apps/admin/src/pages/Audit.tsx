import { dateTime, useAsync, useSession } from '@odyssey/api'
import { Badge, Card, Empty, ErrorNote, PageHeader, Spinner, Table } from '@odyssey/ui'

export function Audit() {
  const { api } = useSession()
  const { data, error, loading } = useAsync(() => api.admin.audit(), [])
  return (
    <>
      <PageHeader title="Audit log" subtitle="Every change to prices, slots, commission, payouts and settings" />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>Nothing logged yet.</Empty> : (
          <Table head={['When', 'Who', 'Action', 'Details']}>
            {data.map((a) => (
              <tr key={a.id}><td className="nowrap">{dateTime(a.at)}</td><td><Badge>{a.actorRole.toLowerCase().replace('_', ' ')}</Badge></td><td>{a.action.replace(/_/g, ' ').toLowerCase()}</td><td className="muted">{a.detail}</td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}
