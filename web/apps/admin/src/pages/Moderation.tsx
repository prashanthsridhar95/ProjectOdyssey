import { Link } from 'react-router-dom'
import { messageOf, money, useAsync, useSession } from '@odyssey/api'
import { Button, Card, DataTable, EmptyState, ErrorNote, PageHeader, useConfirm, useToast } from '@odyssey/ui'

/** Prices far from the median for the same service. Approve to keep them, hide to take them off the app. */
export function Moderation({ onChange }: { onChange?: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const { data, error, loading, reload } = useAsync(() => api.admin.outliers(), [])
  async function act(id: string, item: string, vendor: string, hide: boolean) {
    if (hide && !(await confirm({ title: `Hide ${item} at ${vendor}?`, tone: 'danger', confirmLabel: 'Hide from customers', body: 'Customers stop seeing this service until the vendor changes the price or you approve it.' }))) return
    try { await api.admin.reviewOutlier(id, hide); toast(hide ? 'Hidden from customers' : 'Price approved'); reload(); onChange?.() } catch (e) { toast(messageOf(e), true) }
  }
  return (
    <>
      <PageHeader title="Menu moderation" subtitle="Prices that look unusual next to what other vendors charge for the same service" />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(o) => o.itemId + o.variant} search={(o) => `${o.vendor} ${o.item} ${o.locationName}`} searchPlaceholder="Search vendor or service"
          empty={<EmptyState icon="checkCircle" title="Nothing looks unusual">Prices more than twice or less than half the median for a service show up here.</EmptyState>}
          columns={[
            { key: 'vendor', header: 'Vendor', cell: (o) => <><Link to={`/vendors/${o.providerId}`}><strong>{o.vendor}</strong></Link><div className="sub">{o.locationName}</div></>, sort: (o) => o.vendor },
            { key: 'item', header: 'Service', cell: (o) => <>{o.item}<div className="sub">{o.variant}</div></>, sort: (o) => o.item },
            { key: 'price', header: 'Price', cell: (o) => <strong>{money(o.priceMinor)}</strong>, sort: (o) => o.priceMinor, align: 'right' },
            { key: 'median', header: 'Median', cell: (o) => <span className="muted">{money(o.medianMinor)}</span>, align: 'right' },
            { key: 'diff', header: 'Difference', cell: (o) => { const d = o.medianMinor ? Math.round(((o.priceMinor - o.medianMinor) / o.medianMinor) * 100) : 0; return <span className="strong" style={{ color: d > 0 ? 'var(--c-bad)' : 'var(--c-warn)' }}>{d > 0 ? '+' : ''}{d}%</span> }, sort: (o) => (o.medianMinor ? o.priceMinor / o.medianMinor : 0), align: 'right' },
            { key: 'why', header: 'Why flagged', cell: (o) => <span className="muted small">{o.reason}</span> },
            { key: 'act', header: '', cell: (o) => <span className="row tight" style={{ justifyContent: 'flex-end' }}><Button small icon="check" onClick={() => act(o.itemId, o.item, o.vendor, false)}>Approve</Button><Button small variant="danger" icon="eyeOff" onClick={() => act(o.itemId, o.item, o.vendor, true)}>Hide</Button></span>, align: 'right' },
          ]} />
      </Card>
    </>
  )
}
