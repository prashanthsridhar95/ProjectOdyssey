import { useState } from 'react'
import { Link } from 'react-router-dom'
import { money, moneyShort, useAsync, useLabels, useSession } from '@odyssey/api'
import type { AlertItem } from '@odyssey/api'
import { BarChart, Card, DataTable, Delta, EmptyState, ErrorNote, Icon, KpiStrip, PageHeader, Segmented, Skeleton, type IconName } from '@odyssey/ui'

const RANGES = [['7d', '7 days'], ['30d', '30 days'], ['90d', '90 days']] as const

function alertLink(a: AlertItem): string {
  if (a.kind === 'VERIFICATION') return '/vendors?status=SUBMITTED'
  if (a.kind === 'PAYOUT') return '/payments'
  if (a.kind === 'TICKET' || /ticket/i.test(a.title)) return '/support'
  return '/vendors'
}
function alertLook(a: AlertItem): [IconName, 'bad' | 'warn' | 'info'] {
  if (/flag|fail|suspend/i.test(a.title)) return ['alert', 'bad']
  if (a.kind === 'VERIFICATION' || /review/i.test(a.title)) return ['shield', 'info']
  return ['clock', 'warn']
}

export function Dashboard() {
  const { api, user } = useSession()
  const labels = useLabels()
  const [range, setRange] = useState<(typeof RANGES)[number][0]>('7d')
  const { data, error, loading, reload } = useAsync(() => api.admin.dashboard(range), [range])
  const hour = Number(new Intl.DateTimeFormat('en-IN', { hour: 'numeric', hour12: false, timeZone: 'Asia/Kolkata' }).format(new Date()))
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'

  return (
    <>
      <PageHeader title="Dashboard" subtitle={`${greeting}, ${user?.name.split(' ')[0] ?? ''}. Here is how the marketplace is doing.`} actions={<Segmented label="Period" options={RANGES} value={range} onChange={setRange} />} />
      <ErrorNote message={error} onRetry={reload} />
      {loading && !data ? (
        <div className="kpis">{Array.from({ length: 6 }, (_, i) => <div key={i} className="stack tight"><Skeleton width="60%" /><Skeleton width="80%" height={28} /></div>)}</div>
      ) : data && (
        <>
          <KpiStrip items={[
            { label: 'Gross value (GMV)', icon: 'trend', value: money(data.gmvMinor), foot: <Delta pct={data.gmvDeltaPct} /> },
            { label: `${labels.order}s`, icon: 'calendar', value: data.bookings.toLocaleString('en-IN'), foot: `${data.bookingsPerDay} a day on average` },
            { label: 'Take rate', icon: 'percent', value: `${(data.takeRateBps / 100).toFixed(1)}%`, foot: 'Commission over GMV' },
            { label: 'Utilisation', icon: 'grid', value: `${data.utilisationPct}%`, foot: 'Booked share of open slots' },
            { label: 'Cancellations', icon: 'ban', value: `${data.cancellationPct}%`, foot: `${data.noShowPct}% no-shows` },
            { label: `${labels.provider}s live`, icon: 'store', value: data.vendorsLive, foot: <Link to="/vendors?status=SUBMITTED">{data.vendorsInReview} waiting for review</Link> },
          ]} />
          <div className="spacer" />
          <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1.6fr) minmax(300px, 1fr)' }}>
            <Card title="GMV by day" actions={<span className="muted small num">{money(data.series.reduce((s, d) => s + d.gmvMinor, 0))} in {RANGES.find((r) => r[0] === range)![1]}</span>}>
              {data.series.every((d) => d.gmvMinor === 0)
                ? <EmptyState icon="trend" title="No bookings in this period">GMV appears here as soon as customers pay for bookings.</EmptyState>
                : <BarChart label="GMV by day" format={moneyShort} data={data.series.map((d) => ({ key: d.date, label: d.date.slice(8), value: d.gmvMinor, detail: new Date(`${d.date}T12:00:00+05:30`).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' }) }))} />}
            </Card>
            <Card title="Needs attention" actions={data.alerts.length > 0 && <span className="badge warn">{data.alerts.length}</span>}>
              {data.alerts.length === 0 ? <EmptyState icon="checkCircle" title="All clear">Nothing is waiting on the platform team.</EmptyState> : data.alerts.map((a, i) => {
                const [icon, tone] = alertLook(a)
                return (
                  <div className="alert-row" key={i}>
                    <span className={`sev ${tone}`}><Icon name={icon} size={15} /></span>
                    <div className="grow"><strong>{a.title}</strong><div className="muted small">{a.meta}</div></div>
                    <Link to={alertLink(a)} className="small nowrap">Open <Icon name="chevronRight" size={12} /></Link>
                  </div>
                )
              })}
            </Card>
          </div>
          <div className="spacer" />
          <Card title={`Top ${labels.provider.toLowerCase()}s`} flush actions={<Link to="/vendors" className="small">All {labels.provider.toLowerCase()}s</Link>}>
            <DataTable rows={data.topVendors} rowKey={(v) => v.providerId} pageSize={10}
              empty={<EmptyState icon="store" title="No activity yet">{labels.provider}s show here once they take bookings.</EmptyState>}
              initialSort={{ key: 'gmv', dir: 'desc' }}
              columns={[
                { key: 'name', header: labels.provider, cell: (v) => <Link to={`/vendors/${v.providerId}`}><strong>{v.name}</strong></Link>, sort: (v) => v.name },
                { key: 'bookings', header: `${labels.order}s`, cell: (v) => v.bookings, sort: (v) => v.bookings, align: 'right' },
                { key: 'gmv', header: 'GMV', cell: (v) => money(v.gmvMinor), sort: (v) => v.gmvMinor, align: 'right' },
                { key: 'util', header: 'Utilisation', cell: (v) => `${v.utilisationPct}%`, sort: (v) => v.utilisationPct, align: 'right' },
                { key: 'rating', header: 'Rating', cell: (v) => <span className="row tight" style={{ justifyContent: 'flex-end' }}><span className="stars">★</span>{v.ratingAvg.toFixed(1)}</span>, sort: (v) => v.ratingAvg, align: 'right' },
                { key: 'cancel', header: 'Cancelled', cell: (v) => <span className={v.cancelPct >= 15 ? 'strong' : ''} style={{ color: v.cancelPct >= 15 ? 'var(--c-bad)' : undefined }}>{v.cancelPct}%</span>, sort: (v) => v.cancelPct, align: 'right' },
                { key: 'noshow', header: 'No-show', cell: (v) => `${v.noShowPct}%`, sort: (v) => v.noShowPct, align: 'right' },
              ]} />
          </Card>
        </>
      )}
    </>
  )
}
