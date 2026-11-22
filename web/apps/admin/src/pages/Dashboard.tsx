import { useState } from 'react'
import { money, useAsync, useLabels, useSession } from '@odyssey/api'
import { Card, Empty, ErrorNote, PageHeader, Spinner, Table } from '@odyssey/ui'
import { Link } from 'react-router-dom'

const RANGES = [['7d', '7 days'], ['30d', '30 days'], ['90d', '90 days']] as const

function Delta({ pct }: { pct?: number | null }) {
  if (pct == null) return null
  return <span className={`delta ${pct >= 0 ? 'up' : 'down'} small`}> {pct >= 0 ? '▲' : '▼'} {Math.abs(pct)}%</span>
}

export function Dashboard() {
  const { api } = useSession()
  const labels = useLabels()
  const [range, setRange] = useState<string>('7d')
  const { data, error, loading } = useAsync(() => api.admin.dashboard(range), [range])
  const tabs = (
    <div className="tabs" role="tablist">{RANGES.map(([k, l]) => <button key={k} className={range === k ? 'on' : ''} onClick={() => setRange(k)}>{l}</button>)}</div>
  )
  if (loading && !data) return <Spinner />
  if (error || !data) return <ErrorNote message={error} />
  const max = Math.max(1, ...data.series.map((d) => d.gmvMinor))
  return (
    <>
      <PageHeader title="Dashboard" subtitle="How the marketplace is doing" actions={tabs} />
      <div className="kpis">
        <div><div className="label">Gross value (GMV)</div><div className="value">{money(data.gmvMinor)}</div><Delta pct={data.gmvDeltaPct} /></div>
        <div><div className="label">{labels.order}s</div><div className="value">{data.bookings}</div><div className="muted small">{data.bookingsPerDay} per day</div></div>
        <div><div className="label">Take rate</div><div className="value">{(data.takeRateBps / 100).toFixed(1)}%</div></div>
        <div><div className="label">Utilisation</div><div className="value">{data.utilisationPct}%</div></div>
        <div><div className="label">Cancellations</div><div className="value">{data.cancellationPct}%</div><div className="muted small">{data.noShowPct}% no-shows</div></div>
        <div><div className="label">{labels.provider}s live</div><div className="value">{data.vendorsLive}</div><div className="muted small">{data.vendorsInReview} in review</div></div>
      </div>
      <div style={{ height: 20 }} />
      <div className="grid cols-2">
        <Card>
          <h2>GMV by day</h2>
          {data.series.length === 0 ? <Empty>No bookings in this range.</Empty> : (
            <>
              <div className="bars" role="img" aria-label="GMV by day">
                {data.series.map((d) => <div key={d.date} className="bar" style={{ height: `${(d.gmvMinor / max) * 100}%` }} title={`${d.date}: ${money(d.gmvMinor)}`} />)}
              </div>
              <div className="bars-x">{data.series.map((d, i) => <span key={d.date}>{data.series.length <= 14 || i % 7 === 0 ? d.date.slice(8) : ''}</span>)}</div>
            </>
          )}
        </Card>
        <Card>
          <h2>Needs attention</h2>
          {data.alerts.length === 0 ? <Empty>Nothing needs attention.</Empty> : data.alerts.map((a, i) => (
            <div className="alert-row" key={i}>
              <div><strong>{a.title}</strong><div className="muted small">{a.meta}</div></div>
              <Link to={a.kind === 'VERIFICATION' ? '/vendors?status=SUBMITTED' : a.kind === 'PAYOUT' ? '/payments' : '/vendors'}>Open</Link>
            </div>
          ))}
        </Card>
      </div>
      <div style={{ height: 20 }} />
      <Card flush>
        <div style={{ padding: '16px 18px 4px' }}><h2>Top {labels.provider.toLowerCase()}s</h2></div>
        {data.topVendors.length === 0 ? <Empty>No data yet.</Empty> : (
          <Table head={[labels.provider, 'Bookings', 'GMV', 'Utilisation', 'Rating', 'Cancelled', 'No-show']}>
            {data.topVendors.map((v) => (
              <tr key={v.providerId}><td><Link to={`/vendors?id=${v.providerId}`}>{v.name}</Link></td><td>{v.bookings}</td><td>{money(v.gmvMinor)}</td><td>{v.utilisationPct}%</td><td>{v.ratingAvg.toFixed(1)}</td><td>{v.cancelPct}%</td><td>{v.noShowPct}%</td></tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}
