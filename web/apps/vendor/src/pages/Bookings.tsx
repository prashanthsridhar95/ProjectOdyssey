import { useEffect, useMemo, useRef, useState } from 'react'
import { addDays, dayLabel, isoDate, messageOf, money, phone, timeOf, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Order, Provider } from '@odyssey/api'
import { Badge, Button, Card, Chips, EmptyState, ErrorNote, IconButton, LiveIndicator, PageHeader, SearchInput, Select, Skeleton, StatusBadge, useConfirm, useToast } from '@odyssey/ui'

const LABEL: Record<string, string> = {
  ACCEPT: 'Accept', REJECT: 'Reject', CHECK_IN: 'Check in', START: 'Start', COMPLETE: 'Mark complete', NO_SHOW: 'No-show', CANCEL_BY_PROVIDER: 'Cancel',
}
const PRIMARY = new Set(['ACCEPT', 'START', 'COMPLETE', 'CHECK_IN'])
const DESTRUCTIVE = new Set(['REJECT', 'CANCEL_BY_PROVIDER'])
const ORDER = ['ACCEPT', 'CHECK_IN', 'START', 'COMPLETE', 'NO_SHOW', 'REJECT', 'CANCEL_BY_PROVIDER']
const SOUND_KEY = 'odyssey.vendor.sound'

type Lane = 'needs' | 'upcoming' | 'active' | 'done' | 'closed'
function laneOf(o: Order): Lane {
  if (o.actions.includes('ACCEPT')) return 'needs'
  if (['IN_PROGRESS', 'CHECKED_IN'].includes(o.status)) return 'active'
  if (o.status === 'COMPLETED') return 'done'
  if (['CANCELLED', 'REJECTED', 'NO_SHOW', 'EXPIRED'].includes(o.status)) return 'closed'
  return 'upcoming'
}

/** A short two-tone beep, so a counter with a noisy bay still hears a new booking. */
function beep() {
  try {
    const ctx = new AudioContext()
    ;[880, 1175].forEach((f, i) => {
      const o = ctx.createOscillator(); const g = ctx.createGain()
      o.frequency.value = f; g.gain.value = 0.15; o.connect(g); g.connect(ctx.destination)
      o.start(ctx.currentTime + i * 0.18); o.stop(ctx.currentTime + i * 0.18 + 0.15)
    })
  } catch { /* audio not allowed until the page is clicked once */ }
}

export function BookingsPage({ provider, onChange }: { provider: Provider; onChange?: () => void }) {
  const { api, config } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const [date, setDate] = useState(isoDate())
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [lane, setLane] = useState<Lane | 'all'>('all')
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [sound, setSound] = useState(() => localStorage.getItem(SOUND_KEY) !== 'off')
  const [updatedAt, setUpdatedAt] = useState<number | null>(null)
  const [, tick] = useState(0)
  const { data, error, loading, reload } = useAsync(() => api.provider.orders({ date, locationId }), [date, locationId])
  const known = useRef<Set<string> | null>(null)
  const isToday = date === isoDate()

  // Poll for new bookings while the tab is visible; real-time push replaces this once the socket endpoint exists.
  useEffect(() => {
    if (!isToday) return
    const t = setInterval(() => { if (document.visibilityState === 'visible') reload() }, 15_000)
    const v = () => { if (document.visibilityState === 'visible') reload() }
    document.addEventListener('visibilitychange', v)
    return () => { clearInterval(t); document.removeEventListener('visibilitychange', v) }
  }, [reload, isToday])
  useEffect(() => { const t = setInterval(() => tick((n) => n + 1), 5000); return () => clearInterval(t) }, [])
  useEffect(() => {
    if (!data) return
    setUpdatedAt(Date.now())
    const ids = new Set(data.map((o) => o.id))
    const fresh = known.current ? data.filter((o) => !known.current!.has(o.id) && o.source === 'APP') : []
    if (fresh.length) {
      if (sound) beep()
      toast(`${fresh.length} new ${labels.order.toLowerCase()}${fresh.length === 1 ? '' : 's'}: ${fresh.map((o) => `${timeOf(o.startsAt)} ${o.customerName}`).join(', ')}`)
      onChange?.()
    }
    known.current = ids
  }, [data]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { known.current = null }, [date, locationId])

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: data?.length ?? 0 }
    for (const o of data ?? []) c[laneOf(o)] = (c[laneOf(o)] ?? 0) + 1
    return c
  }, [data])
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (data ?? [])
      .filter((o) => lane === 'all' || laneOf(o) === lane)
      .filter((o) => !needle || `${o.code} ${o.customerName} ${o.customerPhone} ${o.assetLabel ?? ''}`.toLowerCase().includes(needle))
      .sort((a, b) => (laneOf(a) === 'needs' ? -1 : 0) - (laneOf(b) === 'needs' ? -1 : 0) || a.startsAt.localeCompare(b.startsAt))
  }, [data, lane, q])
  const takings = (data ?? []).filter((o) => o.status === 'COMPLETED').reduce((s, o) => s + o.subtotalMinor - (o.commissionMinor ?? 0), 0)

  async function act(o: Order, action: string) {
    let note: string | undefined
    if (DESTRUCTIVE.has(action)) {
      const r = await confirm({
        title: `${LABEL[action]} ${o.code}?`, tone: 'danger', confirmLabel: LABEL[action],
        body: `${o.customerName} is refunded ${money(o.totalMinor)} in full and told straight away. It counts against your cancellation rate, which customers and the platform can see.`,
        reason: { label: 'Reason (the customer sees this)', required: true, placeholder: 'For example: water supply is down today' },
      })
      if (!r) return
      note = r.reason
    } else if (action === 'NO_SHOW') {
      if (!(await confirm({ title: `Mark ${o.customerName} as a no-show?`, confirmLabel: 'Mark no-show', body: 'Use this only if they did not arrive. The booking is not refunded and you are paid as usual.' }))) return
    }
    setBusy(o.id + action)
    try { await api.orders.act(o.id, action, note); toast(`${o.code}: ${LABEL[action]?.toLowerCase() ?? action} done`); reload(); onChange?.() }
    catch (e) { toast(messageOf(e), true) } finally { setBusy(null) }
  }
  const variantLabel = (k: string) => config?.config.variants.options.find((v) => v.key === k)?.label ?? k
  const ago = updatedAt ? Math.round((Date.now() - updatedAt) / 1000) : null

  return (
    <>
      <PageHeader title={`${labels.order}s`} subtitle={isToday ? <LiveIndicator on text={ago === null ? 'Connecting…' : ago < 10 ? 'Live · updated just now' : `Live · updated ${ago}s ago`} /> : <LiveIndicator on={false} text={`Showing ${dayLabel(date)}`} />}
        actions={<>
          {provider.locations.length > 1 && <Select label={labels.location} value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
          <div className="row tight">
            <IconButton icon="chevronLeft" label="Previous day" onClick={() => setDate(addDays(date, -1))} />
            <Button onClick={() => setDate(isoDate())} disabled={isToday}>Today</Button>
            <input type="date" aria-label="Day" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} style={{ width: 150 }} />
            <IconButton icon="chevronRight" label="Next day" onClick={() => setDate(addDays(date, 1))} />
          </div>
          <IconButton icon={sound ? 'bell' : 'bellOff'} label={sound ? 'Sound on for new bookings' : 'Sound off'} onClick={() => { const n = !sound; setSound(n); localStorage.setItem(SOUND_KEY, n ? 'on' : 'off'); if (n) beep() }} />
        </>} />
      <ErrorNote message={error} onRetry={reload} />
      <div className="kpis" style={{ marginBottom: 16 }}>
        <div><div className="label">{labels.order}s {isToday ? 'today' : 'this day'}</div><div className="value">{data ? counts.all : '–'}</div></div>
        <div><div className="label">Waiting for you</div><div className="value" style={{ color: counts.needs ? 'var(--c-warn)' : undefined }}>{data ? counts.needs ?? 0 : '–'}</div></div>
        <div><div className="label">In the bay now</div><div className="value">{data ? counts.active ?? 0 : '–'}</div></div>
        <div><div className="label">Earned so far</div><div className="value">{data ? money(takings) : '–'}</div><div className="foot">From completed, after commission</div></div>
      </div>
      <Card flush>
        <div className="toolbar">
          <SearchInput value={q} onChange={setQ} placeholder="Code, name, phone or plate" />
          <Chips label="Show" value={lane} onChange={setLane} options={[
            { value: 'all', label: 'All', count: counts.all }, { value: 'needs', label: 'Needs action', count: counts.needs ?? 0 }, { value: 'upcoming', label: 'Upcoming', count: counts.upcoming ?? 0 },
            { value: 'active', label: 'In progress', count: counts.active ?? 0 }, { value: 'done', label: 'Done', count: counts.done ?? 0 }, { value: 'closed', label: 'Cancelled', count: counts.closed ?? 0 },
          ]} />
        </div>
        <div style={{ padding: 14 }} className="stack tight">
          {loading && !data ? [1, 2, 3].map((i) => <div key={i} className="booking-card"><Skeleton height={22} /><div className="stack tight"><Skeleton width="50%" /><Skeleton width="70%" /></div><Skeleton width={90} height={28} /></div>)
            : !rows.length ? (
              q || lane !== 'all' ? <EmptyState icon="search" title="Nothing matches" action={<Button small onClick={() => { setQ(''); setLane('all') }}>Show everything</Button>} />
                : <EmptyState icon="calendar" title={isToday ? 'No bookings yet today' : `No bookings on ${dayLabel(date)}`}>{isToday ? 'New bookings appear here with a sound. Keep this page open at the counter.' : 'Pick another day.'}</EmptyState>
            ) : rows.map((o) => {
              const l = laneOf(o)
              return (
                <article key={o.id} className={`booking-card ${l === 'needs' ? 'needs' : l === 'active' ? 'active' : l === 'done' ? 'done' : l === 'closed' ? 'off' : ''}`}>
                  <div><div className="when">{timeOf(o.startsAt)}</div><div className="muted small num">to {timeOf(o.endsAt)}</div></div>
                  <div className="stack xtight" style={{ minWidth: 0 }}>
                    <div className="row tight wrap">
                      <strong style={{ fontSize: 16 }}>{o.customerName}</strong>
                      <StatusBadge status={o.status} label={o.statusLabel} />
                      {o.source === 'WALK_IN' && <Badge tone="info">Walk-in</Badge>}
                      <span className="mono small faint">{o.code}</span>
                    </div>
                    <div className="small">{o.assetLabel ?? variantLabel(o.variantKey)}{o.assetLabel ? <span className="muted"> · {variantLabel(o.variantKey)}</span> : null}{o.customerPhone && o.source !== 'WALK_IN' && <> · <a href={`tel:${o.customerPhone}`}>{phone(o.customerPhone)}</a></>}</div>
                    <div className="muted small">{o.lines.map((x) => x.name).join(' + ')} · {money(o.subtotalMinor)}{o.commissionMinor ? <> · you get <strong>{money(o.subtotalMinor - (o.commissionMinor ?? 0))}</strong></> : null}</div>
                    {o.cancellationReason && <div className="small" style={{ color: 'var(--c-bad)' }}>Reason: {o.cancellationReason}</div>}
                  </div>
                  <div className="actions">
                    {o.actions.filter((a) => LABEL[a]).sort((a, b) => ORDER.indexOf(a) - ORDER.indexOf(b)).map((a) => (
                      <Button key={a} small variant={PRIMARY.has(a) ? 'primary' : DESTRUCTIVE.has(a) ? 'danger' : 'ghost'} busy={busy === o.id + a} onClick={() => void act(o, a)}>{LABEL[a]}</Button>
                    ))}
                  </div>
                </article>
              )
            })}
        </div>
      </Card>
    </>
  )
}
