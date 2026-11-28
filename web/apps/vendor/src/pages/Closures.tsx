import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { addDays, dayLabel, isoDate, istInstant, messageOf, useAsync, useSession } from '@odyssey/api'
import type { Closure, Provider } from '@odyssey/api'
import { Button, Card, EmptyState, ErrorNote, Icon, Modal, Notice, PageHeader, Select, SkeletonRows, useConfirm, useToast, type IconName } from '@odyssey/ui'

const ZONE = 'Asia/Kolkata'
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const clock = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
function label(m: number) { const h = Math.floor(m / 60) % 24, mm = m % 60; return `${h % 12 || 12}${mm ? `:${String(mm).padStart(2, '0')}` : ''} ${h < 12 ? 'am' : 'pm'}` }
function minutesOf(iso: string) {
  const p = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: ZONE }).formatToParts(new Date(iso))
  return Number(p.find((x) => x.type === 'hour')?.value) * 60 + Number(p.find((x) => x.type === 'minute')?.value)
}
const dateOf = (iso: string) => new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(new Date(iso))
const weekdayOf = (date: string) => new Date(`${date}T12:00:00+05:30`).getDay()
function nextWeekday(day: number) { let d = isoDate(); while (weekdayOf(d) !== day) d = addDays(d, 1); return d }
const baysText = (bays: number[] | null | undefined, capacity: number) => (!bays?.length || bays.length >= capacity ? 'all bays' : bays.length === 1 ? `bay ${bays[0]}` : `bays ${bays.join(', ')}`)

/** One closure in plain words, the way an owner would say it. */
function describe(c: Closure, open: number, close: number, capacity: number): { title: string; detail: string } {
  const from = minutesOf(c.startsAt), to = minutesOf(c.endsAt), d1 = dateOf(c.startsAt), d2 = dateOf(new Date(new Date(c.endsAt).getTime() - 1).toISOString())
  const wholeDay = from <= open && (to >= close || to === 0 || d2 > d1)
  const time = wholeDay ? 'all day' : `${label(from)} – ${label(to)}`
  if (c.repeat === 'DAILY') return { title: `Every day, ${time}`, detail: `${c.reason || 'Closed'} · ${baysText(c.bays, capacity)} · since ${dayLabel(d1)}` }
  if (c.repeat === 'WEEKLY') return { title: `Every ${WEEKDAYS[weekdayOf(d1)]}, ${time}`, detail: `${c.reason || 'Closed'} · ${baysText(c.bays, capacity)}` }
  return { title: d1 === d2 ? `${dayLabel(d1)}, ${time}` : `${dayLabel(d1)} – ${dayLabel(d2)}`, detail: `${c.reason || 'Closed'} · ${baysText(c.bays, capacity)}` }
}

/** Time the outlet is closed: a daily break, a weekly day off, or a holiday. Online customers stop seeing those times. */
export function ClosuresPage({ provider }: { provider: Provider }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const loc = provider.locations.find((l) => l.id === locationId)
  const { data, error, loading, reload } = useAsync(() => api.provider.closures(locationId), [locationId])
  const [adding, setAdding] = useState<Kind | null>(null)
  const [showPast, setShowPast] = useState(false)
  const open = clock(loc?.openTime ?? '08:00'), close = clock(loc?.closeTime ?? '20:00'), capacity = loc?.capacity ?? 1
  const now = new Date().toISOString()

  const groups = useMemo(() => {
    const all = data ?? []
    return {
      daily: all.filter((c) => c.repeat === 'DAILY'),
      weekly: all.filter((c) => c.repeat === 'WEEKLY').sort((a, b) => weekdayOf(dateOf(a.startsAt)) - weekdayOf(dateOf(b.startsAt))),
      upcoming: all.filter((c) => c.repeat === 'NONE' && c.endsAt > now).sort((a, b) => a.startsAt.localeCompare(b.startsAt)),
      past: all.filter((c) => c.repeat === 'NONE' && c.endsAt <= now).sort((a, b) => b.startsAt.localeCompare(a.startsAt)),
    }
  }, [data, now])

  async function remove(c: Closure) {
    const d = describe(c, open, close, capacity)
    if (!(await confirm({ title: `Remove "${d.title}"?`, confirmLabel: 'Remove', body: c.repeat === 'NONE' ? 'That time opens for bookings again straight away.' : 'Every future occurrence opens for bookings again straight away.' }))) return
    try { await api.provider.removeBlock(locationId, c.id); toast('Closure removed'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  const row = (c: Closure, past = false) => {
    const d = describe(c, open, close, capacity)
    return (
      <div key={c.id} className="row spread" style={{ padding: '12px 0', borderBottom: '1px solid var(--c-border)', opacity: past ? 0.6 : 1 }}>
        <div><strong>{d.title}</strong><div className="muted small">{d.detail}</div></div>
        {!past && <Button small variant="ghost" icon="trash" onClick={() => remove(c)}>Remove</Button>}
      </div>
    )
  }

  return (
    <>
      <PageHeader title="Closures" subtitle={`When ${loc?.name ?? 'the outlet'} is closed beyond its usual ${label(open)} – ${label(close)}. Customers cannot book these times.`}
        actions={<>
          {provider.locations.length > 1 && <Select label="Outlet" value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
          <Button variant="primary" icon="plus" onClick={() => setAdding('break')}>Add closure</Button>
        </>} />
      <div className="grid cols-3" style={{ marginBottom: 20 }}>
        {KINDS.map((k) => (
          <button key={k.kind} type="button" className="card" style={{ textAlign: 'left', cursor: 'pointer', font: 'inherit', color: 'inherit' }} onClick={() => setAdding(k.kind)}>
            <div className="row tight"><Icon name={k.icon} /><strong>{k.title}</strong></div>
            <p className="muted small" style={{ marginTop: 6 }}>{k.example}</p>
          </button>
        ))}
      </div>
      <ErrorNote message={error} onRetry={reload} />
      <Card title="Your closures" flush>
        {loading && !data ? <SkeletonRows rows={3} cols={2} /> : !groups.daily.length && !groups.weekly.length && !groups.upcoming.length ? (
          <EmptyState icon="clock" title="No closures" action={<Button icon="plus" onClick={() => setAdding('break')}>Add a lunch break</Button>}>You are open for every slot in your hours. Add a break, a weekly day off or a holiday.</EmptyState>
        ) : (
          <div style={{ padding: '4px 18px 12px' }}>
            {groups.daily.length > 0 && <><div className="section-title" style={{ marginTop: 14 }}>Every day</div>{groups.daily.map((c) => row(c))}</>}
            {groups.weekly.length > 0 && <><div className="section-title" style={{ marginTop: 18 }}>Every week</div>{groups.weekly.map((c) => row(c))}</>}
            {groups.upcoming.length > 0 && <><div className="section-title" style={{ marginTop: 18 }}>Coming up</div>{groups.upcoming.map((c) => row(c))}</>}
          </div>
        )}
        {groups.past.length > 0 && (
          <div style={{ padding: '0 18px 14px' }}>
            <Button small variant="ghost" icon={showPast ? 'chevronUp' : 'chevronDown'} onClick={() => setShowPast(!showPast)}>{showPast ? 'Hide' : 'Show'} {groups.past.length} past closure{groups.past.length === 1 ? '' : 's'}</Button>
            {showPast && groups.past.slice(0, 20).map((c) => row(c, true))}
          </div>
        )}
      </Card>
      <p className="muted small" style={{ marginTop: 12 }}>To close just a slot or two today, click it on the <Link to="/calendar">Schedule</Link>.</p>
      {adding && loc && <AddClosure initial={adding} locationId={locationId} open={open} close={close} capacity={capacity} onClose={() => setAdding(null)} onDone={() => { setAdding(null); reload() }} />}
    </>
  )
}

type Kind = 'break' | 'weekly' | 'holiday'
const KINDS: { kind: Kind; title: string; example: string; icon: IconName }[] = [
  { kind: 'break', title: 'Daily break', example: 'Lunch from 1 to 2 pm, every day', icon: 'clock' },
  { kind: 'weekly', title: 'Weekly day off', example: 'Closed every Tuesday', icon: 'calendar' },
  { kind: 'holiday', title: 'Holiday or one-off', example: 'Diwali, or maintenance on one morning', icon: 'tag' },
]

function AddClosure({ initial, locationId, open, close, capacity, onClose, onDone }: { initial: Kind; locationId: string; open: number; close: number; capacity: number; onClose: () => void; onDone: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const [kind, setKind] = useState<Kind>(initial)
  const [from, setFrom] = useState(Math.min(13 * 60, close - 60))
  const [to, setTo] = useState(Math.min(14 * 60, close))
  const [weekday, setWeekday] = useState(2)
  const [startDate, setStartDate] = useState(addDays(isoDate(), 1))
  const [endDate, setEndDate] = useState(addDays(isoDate(), 1))
  const [wholeDay, setWholeDay] = useState(true)
  const [bays, setBays] = useState<number[]>([])
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const times: number[] = []
  for (let t = open; t <= close; t += 15) times.push(t)
  const defaultReason = kind === 'break' ? 'Lunch break' : kind === 'weekly' ? 'Weekly off' : 'Holiday'
  const timeText = wholeDay && kind !== 'break' ? 'all day' : `${label(from)} to ${label(to)}`
  const sentence = kind === 'break' ? `Closed every day from ${label(from)} to ${label(to)}, ${baysText(bays, capacity)}, starting tomorrow.`
    : kind === 'weekly' ? `Closed every ${WEEKDAYS[weekday]}, ${timeText}, ${baysText(bays, capacity)}, starting ${dayLabel(nextWeekday(weekday))}.`
      : startDate === endDate ? `Closed on ${dayLabel(startDate)}, ${timeText}, ${baysText(bays, capacity)}.` : `Closed from ${dayLabel(startDate)} to ${dayLabel(endDate)}, ${baysText(bays, capacity)}.`

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    const multiDay = kind === 'holiday' && startDate !== endDate
    const span = kind === 'break' || (!wholeDay && !multiDay) ? [from, to] : [open, close]
    if (span[1]! <= span[0]!) { setError('The end time must be after the start time'); return }
    if (kind === 'holiday' && endDate < startDate) { setError('The last day is before the first day'); return }
    const first = kind === 'break' ? addDays(isoDate(), 1) : kind === 'weekly' ? nextWeekday(weekday) : startDate
    const last = kind === 'holiday' ? endDate : first
    setBusy(true)
    try {
      await api.provider.addClosure(locationId, {
        startsAt: istInstant(first, hhmm(span[0]!)), endsAt: istInstant(last, hhmm(span[1]!)), reason: reason.trim() || defaultReason,
        bays: bays.length && bays.length < capacity ? bays : undefined, repeat: kind === 'break' ? 'DAILY' : kind === 'weekly' ? 'WEEKLY' : 'NONE',
      })
      toast('Closure added. Customers can no longer book those times.'); onDone()
    } catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }

  return (
    <Modal title="Add closure" onClose={onClose} wide
      footer={<><span className="muted small grow">{sentence}</span><Button onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="closure" busy={busy}>Add closure</Button></>}>
      <form id="closure" className="stack" onSubmit={submit}>
        <div className="tabs" role="tablist" style={{ alignSelf: 'flex-start' }}>
          {KINDS.map((k) => <button key={k.kind} type="button" role="tab" aria-selected={kind === k.kind} className={kind === k.kind ? 'on' : ''} onClick={() => setKind(k.kind)}>{k.title}</button>)}
        </div>
        {kind === 'weekly' && (
          <div className="stack tight"><span className="small strong">Which day</span>
            <div className="chips">{WEEKDAYS.map((d, i) => <button key={d} type="button" className={`chip ${weekday === i ? 'on' : ''}`} onClick={() => setWeekday(i)}>{d.slice(0, 3)}</button>)}</div></div>
        )}
        {kind === 'holiday' && (
          <div className="grid cols-2">
            <label className="field"><span>First day</span><input type="date" min={isoDate()} value={startDate} onChange={(e) => { setStartDate(e.target.value); if (endDate < e.target.value) setEndDate(e.target.value) }} /></label>
            <label className="field"><span>Last day</span><input type="date" min={startDate} value={endDate} onChange={(e) => setEndDate(e.target.value)} /></label>
          </div>
        )}
        {kind !== 'break' && <label className="check"><input type="checkbox" checked={wholeDay} onChange={(e) => setWholeDay(e.target.checked)} />The whole day</label>}
        {(kind === 'break' || !wholeDay) && (kind !== 'holiday' || startDate === endDate) && (
          <div className="grid cols-2">
            <Select label="From" value={from} onChange={(e) => { const f = Number(e.target.value); setFrom(f); if (to <= f) setTo(Math.min(f + 60, close)) }}>{times.slice(0, -1).map((t) => <option key={t} value={t}>{label(t)}</option>)}</Select>
            <Select label="To" value={to} onChange={(e) => setTo(Number(e.target.value))}>{times.filter((t) => t > from).map((t) => <option key={t} value={t}>{label(t)}</option>)}</Select>
          </div>
        )}
        {kind === 'holiday' && startDate !== endDate && !wholeDay && <Notice tone="info">A closure over several days covers whole days. Untick "The whole day" only for a single day.</Notice>}
        <div className="stack tight"><span className="small strong">Bays</span>
          <div className="chips">
            <button type="button" className={`chip ${bays.length === 0 ? 'on' : ''}`} onClick={() => setBays([])}>All bays</button>
            {Array.from({ length: capacity }, (_, i) => i + 1).map((b) => <button type="button" key={b} className={`chip ${bays.includes(b) ? 'on' : ''}`} onClick={() => setBays(bays.includes(b) ? bays.filter((x) => x !== b) : [...bays, b].sort())}>Bay {b}</button>)}
          </div>
          <span className="muted small">Close just some bays when, say, one is being repaired and the rest stay open.</span>
        </div>
        <label className="field"><span>Reason</span><input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={defaultReason} /><span className="hint">Shown to your staff on the schedule. Customers just see the time as unavailable.</span></label>
        <ErrorNote message={error} />
      </form>
    </Modal>
  )
}
