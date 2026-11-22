import { useState } from 'react'
import { money, useAsync, useSession } from '@odyssey/api'
import { Card, ErrorNote, PageHeader, Spinner } from '@odyssey/ui'

const RANGES = [['today', 'Today'], ['week', 'Week'], ['month', 'Month']] as const

function Delta({ pct }: { pct?: number | null }) {
  if (pct == null) return null
  return <span className={`delta ${pct >= 0 ? 'up' : 'down'} small`}> {pct >= 0 ? '▲' : '▼'} {Math.abs(pct)}%</span>
}

/** Home for the vendor: how the business is doing and when the counter is busiest. */
export function OverviewPage() {
  const { api } = useSession()
  const [range, setRange] = useState<string>('week')
  const { data, error, loading } = useAsync(() => api.provider.overview(range), [range])
  const tabs = <div className="tabs">{RANGES.map(([k, l]) => <button key={k} className={range === k ? 'on' : ''} onClick={() => setRange(k)}>{l}</button>)}</div>
  if (loading && !data) return <Spinner />
  if (error || !data) return <ErrorNote message={error} />
  const peak = Math.max(1, ...data.heatmap.flat())
  return (
    <>
      <PageHeader title="Overview" subtitle="Your business at a glance" actions={tabs} />
      <div className="kpis">
        <div><div className="label">Net earnings</div><div className="value">{money(data.netMinor)}</div><Delta pct={data.netDeltaPct} /></div>
        <div><div className="label">Bookings</div><div className="value">{data.bookings}</div><div className="muted small">{data.bookingsDelta >= 0 ? '+' : ''}{data.bookingsDelta} vs before</div></div>
        <div><div className="label">Utilisation</div><div className="value">{data.utilisationPct}%</div></div>
        <div><div className="label">Rating</div><div className="value">{data.ratingAvg.toFixed(1)}</div><div className="muted small">{data.ratingCount} reviews · {data.newReviews} new</div></div>
      </div>
      <div style={{ height: 20 }} />
      <Card>
        <h2>Busiest hours</h2>
        <p className="muted small" style={{ marginBottom: 12 }}>{data.insight}</p>
        <div className="heat" style={{ gridTemplateColumns: `48px repeat(${data.hours.length}, 1fr)` }}>
          <span />{data.hours.map((h) => <span key={h} className="hd">{h}</span>)}
          {data.days.map((d, di) => (
            <Row key={d} day={d} cells={data.heatmap[di] ?? []} peak={peak} />
          ))}
        </div>
      </Card>
    </>
  )
}

function Row({ day, cells, peak }: { day: string; cells: number[]; peak: number }) {
  return (
    <>
      <span className="hd" style={{ textAlign: 'left' }}>{day}</span>
      {cells.map((c, i) => <div key={i} className="cell" style={{ opacity: c ? 0.15 + 0.85 * (c / peak) : 0.06 }} title={`${c} bookings`} />)}
    </>
  )
}
