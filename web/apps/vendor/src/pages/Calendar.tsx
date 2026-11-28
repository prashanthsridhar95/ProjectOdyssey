import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { addDays, dayLabel, isoDate, istInstant, messageOf, money, timeOf, useAsync, useLabels, useSession } from '@odyssey/api'
import type { DayCalendar, Item, Order, Provider, Window } from '@odyssey/api'
import { Badge, Button, Card, Chips, DescriptionList, Drawer, ErrorNote, Icon, IconButton, KpiStrip, Modal, Notice, PageHeader, Select, Skeleton, StatusBadge, useConfirm, useToast } from '@odyssey/ui'

const ZONE = 'Asia/Kolkata'
const PX_PER_MIN = 1.6
const DEAD = new Set(['CANCELLED', 'REJECTED', 'EXPIRED', 'PENDING_PAYMENT'])

/** Minutes since midnight in India time. */
function minutesOf(iso: string): number {
  const p = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: ZONE }).formatToParts(new Date(iso))
  return Number(p.find((x) => x.type === 'hour')?.value ?? 0) * 60 + Number(p.find((x) => x.type === 'minute')?.value ?? 0)
}
const clock = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
function label(m: number) { const h = Math.floor(m / 60), mm = m % 60; return `${h % 12 || 12}${mm ? `:${String(mm).padStart(2, '0')}` : ''} ${h < 12 ? 'am' : 'pm'}` }
const nowMinutes = () => minutesOf(new Date().toISOString())

type Pick = { bay: number; start: number }

/**
 * The day as the counter sees it: one column per bay, time running down, bookings where they sit, closed time hatched,
 * and the past greyed out under a "now" line. Click free time to add a walk-in or block it.
 */
export function CalendarPage({ provider }: { provider: Provider }) {
  const { api, config } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const [date, setDate] = useState(isoDate())
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [pick, setPick] = useState<Pick | null>(null)
  const [walkIn, setWalkIn] = useState<Pick | null>(null)
  const [blocking, setBlocking] = useState<Pick | 'new' | null>(null)
  const [open, setOpen] = useState<Order | null>(null)
  const [now, setNow] = useState(nowMinutes())
  const location = provider.locations.find((l) => l.id === locationId)
  const cal = useAsync(() => api.provider.calendar(locationId, date), [locationId, date])
  const blocks = useAsync(() => api.provider.blocks(locationId, date), [locationId, date])
  const items = useAsync(() => api.provider.items(locationId), [locationId])
  const reload = () => { cal.reload(); blocks.reload() }
  const today = isoDate()
  const isToday = date === today
  const isPast = date < today

  useEffect(() => { const t = setInterval(() => setNow(nowMinutes()), 60_000); return () => clearInterval(t) }, [])
  useEffect(() => { if (!isToday) return; const t = setInterval(reload, 30_000); return () => clearInterval(t) }, [isToday, locationId]) // eslint-disable-line react-hooks/exhaustive-deps

  const c = cal.data
  const view = useMemo(() => (c ? buildView(c, isToday ? now : isPast ? 24 * 60 : -1) : null), [c, now, isToday, isPast])

  async function reopen(id: string, reason: string) {
    if (!(await confirm({ title: `Reopen "${reason}"?`, confirmLabel: 'Reopen', body: 'Customers can book this time again straight away.' }))) return
    try { await api.provider.removeBlock(locationId, id); toast('Time reopened'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  const status = !c || !view ? null
    : !location?.open ? { tone: 'warn' as const, text: `${location?.name} is paused, so customers cannot book online. Walk-ins still work.`, action: <Link to="/settings" className="small nowrap">Turn bookings back on</Link> }
      : isPast ? { tone: 'info' as const, text: `${dayLabel(date)} has passed. This is what happened.` }
        : isToday && now >= view.close ? { tone: 'info' as const, text: `Closed for today at ${label(view.close)}. Tomorrow opens at ${label(view.open)}.`, action: <Button small onClick={() => setDate(addDays(today, 1))}>Show tomorrow</Button> }
          : isToday && now < view.open ? { tone: 'info' as const, text: `Opens at ${label(view.open)} today.` }
            : null

  return (
    <>
      <PageHeader title="Schedule" subtitle={location ? `${location.name} · open ${label(clock(location.openTime))} to ${label(clock(location.closeTime))} · ${location.capacity} bays` : undefined}
        actions={<>
          {provider.locations.length > 1 && <Select label={labels.location} value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
          <div className="row tight">
            <IconButton icon="chevronLeft" label="Previous day" onClick={() => setDate(addDays(date, -1))} />
            <Button onClick={() => setDate(today)} disabled={isToday}>Today</Button>
            <input type="date" aria-label="Day" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} style={{ width: 150 }} />
            <IconButton icon="chevronRight" label="Next day" onClick={() => setDate(addDays(date, 1))} />
          </div>
          {!isPast && view && view.ticks.some((t) => t >= view.cutoff) && <Button icon="ban" onClick={() => setBlocking('new')}>Block time</Button>}
          {!isPast && view && <Button variant="primary" icon="plus" disabled={!view.nextFree} onClick={() => view.nextFree && setWalkIn(view.nextFree)}>Add walk-in</Button>}
        </>} />
      <ErrorNote message={cal.error} onRetry={reload} />

      {cal.loading && !c ? <Card><div className="stack">{[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} height={40} />)}</div></Card> : c && view && (
        <>
          <KpiStrip items={[
            { label: isPast ? 'Bookings' : 'Booked', icon: 'calendar', value: view.booked.length, foot: `${view.booked.filter((o) => o.source === 'WALK_IN').length} walk-ins` },
            { label: 'Bays in use', icon: 'grid', value: `${view.usedPct}%`, foot: 'Share of open bay time' },
            ...(!isPast ? [{ label: 'Free slots left', icon: 'clock' as const, value: view.freeLeft, foot: view.freeLeft ? `Across ${c.capacity} bays` : 'Nothing left to book' }] : []),
            ...(!isPast ? [{ label: 'Next free', icon: 'zap' as const, value: view.nextFree ? label(view.nextFree.start) : '—', foot: view.nextFree ? `Bay ${view.nextFree.bay}` : isToday && now >= view.close ? 'Closed for today' : 'Fully booked' }] : []),
            { label: 'Earned', icon: 'wallet' as const, value: money(view.booked.filter((o) => o.status === 'COMPLETED').reduce((s, o) => s + o.subtotalMinor - (o.commissionMinor ?? 0), 0)), foot: 'Completed, after commission' },
          ]} />
          <div className="spacer" style={{ height: 14 }} />
          {status && <><Notice tone={status.tone} action={status.action}>{status.text}</Notice><div className="spacer" style={{ height: 14 }} /></>}

          <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 280px', alignItems: 'start' }}>
            <Card flush>
              <div className="card-head">
                <h2>{isToday ? 'Today' : dayLabel(date)}</h2>
                <span className="legend">
                  <span><i style={{ background: 'var(--c-primary-soft)', borderColor: 'var(--c-primary)' }} />Booked in app</span>
                  <span><i style={{ background: 'var(--c-surface-2)' }} />Walk-in</span>
                  <span><i style={{ background: 'var(--c-good-soft)' }} />Done</span>
                  <span><i className="hatch" />Closed</span>
                </span>
              </div>
              <div className="sched" style={{ ['--bays' as string]: c.capacity }}>
                <div className="sched-head"><span />{view.bays.map((b) => <span key={b}>Bay {b}</span>)}</div>
                <div className="sched-body" style={{ height: (view.close - view.open) * PX_PER_MIN }}>
                  <div className="sched-times">
                    {view.ticks.map((t) => <span key={t} style={{ top: (t - view.open) * PX_PER_MIN }}>{t % 60 === 0 ? label(t) : ''}</span>)}
                  </div>
                  {view.bays.map((b) => (
                    <div className="sched-col" key={b}>
                      {view.ticks.map((t) => {
                        const past = t + c.slotMinutes <= view.cutoff
                        const free = !past && view.isFree(b, t)
                        return <button key={t} type="button" className={`sched-cell ${t % 60 === 0 ? 'hour' : ''} ${past ? 'past' : ''}`} style={{ top: (t - view.open) * PX_PER_MIN, height: c.slotMinutes * PX_PER_MIN }}
                          disabled={!free} aria-label={free ? `${label(t)}, bay ${b}, free` : undefined} onClick={() => setPick({ bay: b, start: t })}>{free && <span className="plus"><Icon name="plus" size={14} /></span>}</button>
                      })}
                      {view.windows.filter((w) => w.bays.includes(b)).map((w, i) => (
                        <div key={i} className="sched-closed" style={{ top: (w.from - view.open) * PX_PER_MIN, height: (w.to - w.from) * PX_PER_MIN }} title={w.reason}>
                          <Icon name="ban" size={12} /><span className="truncate">{w.reason || 'Closed'}{w.repeat !== 'NONE' ? ` · every ${w.repeat === 'DAILY' ? 'day' : 'week'}` : ''}</span>
                        </div>
                      ))}
                      {view.booked.filter((o) => (o.bay ?? 1) === b).map((o) => {
                        const from = Math.max(minutesOf(o.startsAt), view.open), to = Math.min(Math.max(minutesOf(o.endsAt), from + c.slotMinutes), view.close)
                        const cls = o.status === 'COMPLETED' ? 'done' : ['IN_PROGRESS', 'CHECKED_IN'].includes(o.status) ? 'active' : o.status === 'NO_SHOW' ? 'noshow' : o.source === 'WALK_IN' ? 'walkin' : 'app'
                        return (
                          <button key={o.id} type="button" className={`sched-booking ${cls}`} style={{ top: (from - view.open) * PX_PER_MIN + 1, height: (to - from) * PX_PER_MIN - 3 }} onClick={() => setOpen(o)}>
                            <strong className="truncate">{o.assetLabel ?? o.customerName}</strong>
                            {(to - from) * PX_PER_MIN > 34 && <span className="truncate">{timeOf(o.startsAt)} · {o.lines.map((l) => l.name).join(', ')}</span>}
                          </button>
                        )
                      })}
                    </div>
                  ))}
                  {isToday && now > view.open && now < view.close && <div className="sched-now" style={{ top: (now - view.open) * PX_PER_MIN }}><span>{label(now)}</span></div>}
                </div>
              </div>
            </Card>

            <div className="stack">
              <Card title="Closed time">
                {view.windows.length === 0 ? <p className="muted small">Nothing closed on this day. Every bay is open for its hours.</p> : (
                  <div className="stack tight">
                    {view.windowGroups.map((w, i) => {
                      const block = (blocks.data ?? []).find((x) => minutesOf(x.startsAt) === w.from && minutesOf(x.endsAt) === w.to)
                      return (
                        <div key={i} className="row spread top">
                          <div><strong className="small">{label(w.from)} – {label(w.to)}</strong><div className="muted small">{w.reason || 'Closed'} · {w.bays.length === c.capacity ? 'all bays' : `bay ${w.bays.join(', ')}`}{w.repeat !== 'NONE' ? ` · repeats ${w.repeat.toLowerCase()}` : ''}</div></div>
                          {block && !isPast ? <Button small variant="ghost" onClick={() => reopen(block.id, w.reason || 'closure')}>Reopen</Button> : w.repeat !== 'NONE' ? <Link to="/closures" className="small">Edit</Link> : null}
                        </div>
                      )
                    })}
                  </div>
                )}
                {!isPast && view.ticks.some((t) => t >= view.cutoff) && <div style={{ marginTop: 12 }}><Button small icon="ban" onClick={() => setBlocking('new')}>Block time</Button></div>}
              </Card>
              <Card title="How this works">
                <ul className="stack tight small muted" style={{ margin: 0, paddingLeft: 18 }}>
                  <li>Click any free square to add a walk-in or block it.</li>
                  <li>Walk-ins take a bay, so online customers only see real free time.</li>
                  <li>Lunch breaks and holidays that repeat go under <Link to="/closures">Closures</Link>.</li>
                </ul>
              </Card>
            </div>
          </div>
        </>
      )}

      {pick && (
        <Modal title={`${label(pick.start)} · Bay ${pick.bay}`} description={new Date(`${date}T12:00:00+05:30`).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', timeZone: ZONE })} onClose={() => setPick(null)}>
          <div className="stack">
            <Button variant="primary" icon="plus" block onClick={() => { setWalkIn(pick); setPick(null) }}>Add a walk-in here</Button>
            <Button icon="ban" block onClick={() => { setBlocking(pick); setPick(null) }}>Block this time</Button>
          </div>
        </Modal>
      )}
      {walkIn && c && <WalkInModal at={walkIn} date={date} locationId={locationId} slotMinutes={c.slotMinutes} items={items.data ?? []} variants={config?.config.variants.options ?? []} variantName={config?.config.variants.name ?? 'Type'}
        onClose={() => setWalkIn(null)} onDone={() => { setWalkIn(null); reload() }} />}
      {blocking && c && view && <BlockModal at={blocking === 'new' ? null : blocking} date={date} locationId={locationId} view={view} slotMinutes={c.slotMinutes} capacity={c.capacity}
        onClose={() => setBlocking(null)} onDone={() => { setBlocking(null); reload() }} />}
      {open && <BookingDrawer order={open} onClose={() => setOpen(null)} />}
    </>
  )
}

// ---- the day, worked out once ----

interface ClosedWindow { from: number; to: number; bays: number[]; reason: string; repeat: string }
interface View {
  open: number; close: number; bays: number[]; ticks: number[]; cutoff: number; booked: Order[]; windows: ClosedWindow[]; windowGroups: ClosedWindow[]
  isFree: (bay: number, start: number) => boolean; freeLeft: number; nextFree: Pick | null; usedPct: number
}

function buildView(c: DayCalendar, cutoff: number): View {
  const open = clock(c.openTime), close = clock(c.closeTime)
  const bays = Array.from({ length: c.capacity }, (_, i) => i + 1)
  const ticks: number[] = []
  for (let t = open; t < close; t += c.slotMinutes) ticks.push(t)
  const booked = c.orders.filter((o) => !DEAD.has(o.status)).sort((a, b) => a.startsAt.localeCompare(b.startsAt))
  const windows: ClosedWindow[] = c.windows.map((w: Window) => ({ from: Math.max(minutesOf(w.startsAt), open), to: Math.min(minutesOf(w.endsAt) || 24 * 60, close), bays: w.bays?.length ? w.bays : bays, reason: w.reason, repeat: w.repeat })).filter((w) => w.to > w.from)
  const busy = (b: number, s: number, e: number) =>
    booked.some((o) => (o.bay ?? 1) === b && o.status !== 'NO_SHOW' && minutesOf(o.startsAt) < e && Math.max(minutesOf(o.endsAt), minutesOf(o.startsAt) + c.slotMinutes) > s) ||
    windows.some((w) => w.bays.includes(b) && w.from < e && w.to > s)
  const isFree = (b: number, s: number) => s >= cutoff && !busy(b, s, s + c.slotMinutes)
  let freeLeft = 0; let nextFree: Pick | null = null
  for (const t of ticks) for (const b of bays) if (t >= cutoff && !busy(b, t, t + c.slotMinutes)) { freeLeft++; if (!nextFree) nextFree = { bay: b, start: t } }
  const openMinutes = (close - open) * c.capacity
  const usedMinutes = booked.filter((o) => o.status !== 'NO_SHOW').reduce((s, o) => s + Math.max(c.slotMinutes, minutesOf(o.endsAt) - minutesOf(o.startsAt)), 0)
  const key = (w: ClosedWindow) => `${w.from}-${w.to}-${w.reason}-${w.repeat}`
  const groups = new Map<string, ClosedWindow>()
  for (const w of windows) { const g = groups.get(key(w)); if (g) g.bays = [...new Set([...g.bays, ...w.bays])].sort(); else groups.set(key(w), { ...w, bays: [...w.bays] }) }
  return { open, close, bays, ticks, cutoff, booked, windows, windowGroups: [...groups.values()].sort((a, b) => a.from - b.from), isFree, freeLeft, nextFree, usedPct: openMinutes ? Math.min(100, Math.round((usedMinutes / openMinutes) * 100)) : 0 }
}

// ---- walk-in ----

function WalkInModal({ at, date, locationId, slotMinutes, items, variants, variantName, onClose, onDone }: {
  at: Pick; date: string; locationId: string; slotMinutes: number; items: Item[]; variants: { key: string; label: string }[]; variantName: string; onClose: () => void; onDone: () => void
}) {
  const { api } = useSession()
  const toast = useToast()
  const [variant, setVariant] = useState(variants[0]?.key ?? '')
  const offered = items.filter((i) => i.active && i.prices[variant] !== undefined)
  const [chosen, setChosen] = useState<string[]>([])
  const [name, setName] = useState('')
  const [plate, setPlate] = useState('')
  const [busy, setBusy] = useState(false)
  const picked = offered.filter((i) => chosen.includes(i.id))
  const minutes = picked.reduce((s, i) => s + i.durationMinutes, 0)
  const total = picked.reduce((s, i) => s + (i.prices[variant] ?? 0), 0)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true)
    try {
      await api.provider.walkIn({ locationId, itemIds: chosen, variantKey: variant, startsAt: istInstant(date, hhmm(at.start)), customerName: name.trim() || 'Walk-in', plate: plate.trim() || undefined, bay: at.bay })
      toast(`Walk-in added at ${label(at.start)}, bay ${at.bay}`); onDone()
    } catch (err) { toast(messageOf(err), true) } finally { setBusy(false) }
  }
  return (
    <Modal title={`Walk-in at ${label(at.start)}`} description={`Bay ${at.bay}. Paid at the counter, so no commission.`} onClose={onClose} wide
      footer={<><span className="muted small grow">{picked.length ? `${minutes} min · ${money(total)}` : 'Pick at least one service'}{minutes > slotMinutes ? ` · uses ${Math.ceil(minutes / slotMinutes)} slots` : ''}</span><Button onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="walkin" busy={busy} disabled={!chosen.length}>Add walk-in</Button></>}>
      <form id="walkin" className="stack" onSubmit={submit}>
        <div className="stack tight"><span className="small strong">{variantName}</span>
          <Chips label={variantName} value={variant} onChange={(v) => { setVariant(v); setChosen([]) }} options={variants.map((v) => ({ value: v.key, label: v.label }))} /></div>
        <div className="stack tight"><span className="small strong">Services</span>
          {offered.length === 0 ? <p className="muted small">Nothing on the menu for this {variantName.toLowerCase()} yet. <Link to="/menu">Add services</Link>.</p> : (
            <div className="grid cols-2" style={{ gap: 8 }}>
              {offered.map((i) => (
                <label key={i.id} className={`check slot ${chosen.includes(i.id) ? 'sel' : ''}`} style={{ justifyContent: 'space-between' }}>
                  <span className="row tight"><input type="checkbox" checked={chosen.includes(i.id)} onChange={() => setChosen(chosen.includes(i.id) ? chosen.filter((x) => x !== i.id) : [...chosen, i.id])} />{i.name}</span>
                  <span className="muted small nowrap">{i.durationMinutes} min · {money(i.prices[variant])}</span>
                </label>
              ))}
            </div>
          )}
        </div>
        <div className="grid cols-2">
          <label className="field"><span>Customer name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="Walk-in" /></label>
          <label className="field"><span>Number plate</span><input value={plate} onChange={(e) => setPlate(e.target.value.toUpperCase())} placeholder="KA 03 MN 4821" /></label>
        </div>
      </form>
    </Modal>
  )
}

// ---- block time ----

function BlockModal({ at, date, locationId, view, slotMinutes, capacity, onClose, onDone }: {
  at: Pick | null; date: string; locationId: string; view: View; slotMinutes: number; capacity: number; onClose: () => void; onDone: () => void
}) {
  const { api } = useSession()
  const toast = useToast()
  const first = at?.start ?? Math.max(view.open, Math.ceil(Math.max(view.cutoff, view.open) / slotMinutes) * slotMinutes)
  const [from, setFrom] = useState(first)
  const [to, setTo] = useState(Math.min(first + (at ? slotMinutes : 60), view.close))
  const [bays, setBays] = useState<number[]>(at ? [at.bay] : [])
  const [reason, setReason] = useState('Break')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const starts = view.ticks.filter((t) => t >= Math.floor(view.cutoff / slotMinutes) * slotMinutes || view.cutoff < 0)
  const ends = [...view.ticks.slice(1), view.close].filter((t) => t > from)
  const clash = view.booked.filter((o) => o.status !== 'NO_SHOW' && minutesOf(o.startsAt) < to && minutesOf(o.endsAt) > from && (!bays.length || bays.includes(o.bay ?? 1)))

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    if (clash.length) { setError(`${clash.length} booking${clash.length === 1 ? ' is' : 's are'} in that time. Move or cancel ${clash.length === 1 ? 'it' : 'them'} first.`); return }
    setBusy(true)
    try { await api.provider.addClosure(locationId, { startsAt: istInstant(date, hhmm(from)), endsAt: istInstant(date, hhmm(to)), reason: reason.trim() || 'Closed', bays: bays.length && bays.length < capacity ? bays : undefined, repeat: 'NONE' }); toast(`Blocked ${label(from)} – ${label(to)}`); onDone() }
    catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }
  return (
    <Modal title="Block time" description="Customers stop seeing this time. Bookings already in it are not touched." onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="block" busy={busy} disabled={to <= from}>Block {label(from)} – {label(to)}</Button></>}>
      <form id="block" className="stack" onSubmit={submit}>
        <div className="grid cols-2">
          <Select label="From" value={from} onChange={(e) => { const f = Number(e.target.value); setFrom(f); if (to <= f) setTo(Math.min(f + slotMinutes, view.close)) }}>{starts.map((t) => <option key={t} value={t}>{label(t)}</option>)}</Select>
          <Select label="To" value={to} onChange={(e) => setTo(Number(e.target.value))}>{ends.map((t) => <option key={t} value={t}>{label(t)}</option>)}</Select>
        </div>
        <div className="stack tight"><span className="small strong">Bays</span>
          <div className="chips">
            <button type="button" className={`chip ${bays.length === 0 ? 'on' : ''}`} onClick={() => setBays([])}>All bays</button>
            {Array.from({ length: capacity }, (_, i) => i + 1).map((b) => <button type="button" key={b} className={`chip ${bays.includes(b) ? 'on' : ''}`} onClick={() => setBays(bays.includes(b) ? bays.filter((x) => x !== b) : [...bays, b].sort())}>Bay {b}</button>)}
          </div>
        </div>
        <div className="stack tight"><span className="small strong">Reason</span>
          <div className="chips">{['Break', 'Maintenance', 'Staff short', 'Water supply'].map((r) => <button type="button" key={r} className={`chip ${reason === r ? 'on' : ''}`} onClick={() => setReason(r)}>{r}</button>)}</div>
          <input value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
        </div>
        {clash.length > 0 && <Notice tone="warn">{clash.map((o) => `${timeOf(o.startsAt)} ${o.customerName}`).join(', ')} {clash.length === 1 ? 'is' : 'are'} booked in this time.</Notice>}
        {error && <ErrorNote message={error} />}
        <p className="faint small">For a break every day or a weekly holiday, use <Link to="/closures">Closures</Link>.</p>
      </form>
    </Modal>
  )
}

function BookingDrawer({ order: o, onClose }: { order: Order; onClose: () => void }) {
  const { config } = useSession()
  const variant = config?.config.variants.options.find((v) => v.key === o.variantKey)?.label ?? o.variantKey
  return (
    <Drawer title={o.assetLabel ?? o.customerName} subtitle={`${timeOf(o.startsAt)} – ${timeOf(o.endsAt)} · Bay ${o.bay ?? 1}`} onClose={onClose}
      meta={<><StatusBadge status={o.status} label={o.statusLabel} />{o.source === 'WALK_IN' && <Badge tone="info">Walk-in</Badge>}<span className="mono small faint">{o.code}</span></>}
      footer={<Link to="/bookings"><Button>Open in Bookings</Button></Link>}>
      <DescriptionList items={[
        ['Customer', o.customerName], ...(o.source !== 'WALK_IN' && o.customerPhone ? [['Phone', <a href={`tel:${o.customerPhone}`}>{o.customerPhone}</a>] as [string, React.ReactNode]] : []),
        ['Vehicle', `${o.assetLabel ?? '-'} · ${variant}`], ['Services', o.lines.map((l) => `${l.name} (${l.durationMinutes} min)`).join(', ')],
        ['Service value', money(o.subtotalMinor)], ['You get', money(o.subtotalMinor - (o.commissionMinor ?? 0))],
      ]} />
    </Drawer>
  )
}

