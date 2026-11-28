import { useState, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { money, useAsync, useSession } from '@odyssey/api'
import type { Provider } from '@odyssey/api'
import { Card, Delta, ErrorNote, Icon, KpiStrip, Notice, PageHeader, Segmented, Skeleton } from '@odyssey/ui'

const RANGES = [['today', 'Today'], ['week', 'This week'], ['month', 'This month']] as const

/** Home for the vendor: how the business is doing and when the counter is busiest. */
export function OverviewPage({ provider }: { provider: Provider }) {
  const { api, user } = useSession()
  const [range, setRange] = useState<(typeof RANGES)[number][0]>('week')
  const { data, error, loading, reload } = useAsync(() => api.provider.overview(range), [range])
  const peak = Math.max(1, ...(data?.heatmap.flat() ?? [0]))
  const loc = provider.locations[0]
  const setupGaps = [
    !provider.bank && user?.role === 'PROVIDER_OWNER' ? ['Add a bank account so we can pay you', '/settings'] : null,
    loc && !loc.open ? [`${loc.name} is paused and not taking bookings`, '/settings'] : null,
  ].filter(Boolean) as [string, string][]

  return (
    <>
      <PageHeader title={`Hello, ${user?.name.split(' ')[0] ?? ''}`} subtitle={`How ${provider.name} is doing`} actions={<Segmented label="Period" options={RANGES} value={range} onChange={setRange} />} />
      {setupGaps.map(([t, to]) => <div key={t} style={{ marginBottom: 12 }}><Notice tone="warn" action={<Link to={to} className="small nowrap">Fix this <Icon name="chevronRight" size={12} /></Link>}>{t}</Notice></div>)}
      <ErrorNote message={error} onRetry={reload} />
      {loading && !data ? <div className="kpis">{[1, 2, 3, 4].map((i) => <div key={i} className="stack tight"><Skeleton width="50%" /><Skeleton width="70%" height={28} /></div>)}</div> : data && (
        <>
          <KpiStrip items={[
            { label: 'Net earnings', icon: 'wallet', value: money(data.netMinor), foot: <Delta pct={data.netDeltaPct} /> },
            { label: 'Bookings', icon: 'calendar', value: data.bookings, foot: `${data.bookingsDelta >= 0 ? '+' : ''}${data.bookingsDelta} vs the previous period` },
            { label: 'Bays in use', icon: 'grid', value: `${data.utilisationPct}%`, foot: 'Share of open slot time booked' },
            { label: 'Rating', icon: 'star', value: data.ratingCount ? data.ratingAvg.toFixed(1) : 'New', foot: <Link to="/reviews">{data.ratingCount} reviews{data.newReviews ? ` · ${data.newReviews} new` : ''}</Link> },
          ]} />
          <div className="spacer" />
          <Card title="Busiest hours" actions={<span className="legend"><span><i style={{ background: 'var(--c-primary)', opacity: 0.15 }} />Quiet</span><span><i style={{ background: 'var(--c-primary)' }} />Busy</span></span>}>
            {data.insight && <Notice tone="info">{data.insight}</Notice>}
            <div className="heat" style={{ gridTemplateColumns: `44px repeat(${data.hours.length}, 1fr)`, marginTop: 14 }} role="img" aria-label="Bookings by day and hour">
              <span />{data.hours.map((h) => <span key={h} className="hd">{h > 12 ? `${h - 12}p` : h === 12 ? '12p' : `${h}a`}</span>)}
              {data.days.map((d, di) => (
                <Row key={d} row={di} day={d} hours={data.hours} cells={data.heatmap[di] ?? []} peak={peak} />
              ))}
            </div>
          </Card>
        </>
      )}
    </>
  )
}

function Row({ day, hours, cells, peak, row = 0 }: { day: string; hours: number[]; cells: number[]; peak: number; row?: number }) {
  return (
    <>
      <span className="hd" style={{ textAlign: 'left' }}>{day}</span>
      {cells.map((c, i) => <div key={i} className="cell" style={{ opacity: c ? 0.15 + 0.85 * (c / peak) : 0.05, '--i': i + row * 3 } as CSSProperties} title={`${day} ${hours[i]}:00 · ${c} booking${c === 1 ? '' : 's'}`} />)}
    </>
  )
}
