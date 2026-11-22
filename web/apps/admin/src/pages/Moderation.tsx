import { messageOf, money, useAsync, useSession } from '@odyssey/api'
import { Button, Card, Empty, ErrorNote, PageHeader, Spinner, Table, useToast } from '@odyssey/ui'

/** Prices far from the median for the same service. Approve to keep them, hide to take them off the app. */
export function Moderation() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.admin.outliers(), [])
  async function act(id: string, hide: boolean) { try { await api.admin.reviewOutlier(id, hide); toast(hide ? 'Hidden from customers' : 'Approved'); reload() } catch (e) { toast(messageOf(e), true) } }
  return (
    <>
      <PageHeader title="Menu moderation" subtitle="Prices that look unusual compared with other vendors" />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>Nothing looks unusual.</Empty> : (
          <Table head={['Vendor', 'Service', 'Variant', 'Price', 'Median', 'Why', '']}>
            {data.map((o) => (
              <tr key={o.itemId + o.variant}><td>{o.vendor}<div className="muted small">{o.locationName}</div></td><td>{o.item}</td><td>{o.variant}</td><td>{money(o.priceMinor)}</td><td>{money(o.medianMinor)}</td><td>{o.reason}</td>
                <td className="nowrap"><Button small onClick={() => act(o.itemId, false)}>Approve</Button> <Button small variant="danger" onClick={() => act(o.itemId, true)}>Hide</Button></td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}
