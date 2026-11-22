import { useEffect, useRef, useState } from 'react'
import { isoDate, messageOf, money, timeOf, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Order, Provider } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Select, Spinner, statusTone, useToast } from '@odyssey/ui'

const LABEL: Record<string, string> = {
  ACCEPT: 'Accept', REJECT: 'Reject', START: 'Start wash', COMPLETE: 'Mark complete', NO_SHOW: 'No-show', CANCEL_BY_PROVIDER: 'Cancel',
}
const NEEDS_REASON = new Set(['REJECT', 'CANCEL_BY_PROVIDER'])
const PRIMARY = new Set(['ACCEPT', 'START', 'COMPLETE'])

/** A short two-tone beep, so a counter with a noisy bay still hears a new booking. */
function beep() {
  try {
    const ctx = new AudioContext()
    ;[880, 1175].forEach((f, i) => {
      const o = ctx.createOscillator(); const g = ctx.createGain()
      o.frequency.value = f; g.gain.value = 0.15; o.connect(g); g.connect(ctx.destination)
      o.start(ctx.currentTime + i * 0.18); o.stop(ctx.currentTime + i * 0.18 + 0.15)
    })
  } catch { /* audio not allowed yet */ }
}

export function BookingsPage({ provider }: { provider: Provider }) {
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const [date, setDate] = useState(isoDate())
  const [locationId, setLocationId] = useState(provider.locations[0]?.id ?? '')
  const [reasonFor, setReasonFor] = useState<{ order: Order; action: string } | null>(null)
  const [reason, setReason] = useState('')
  const { data, error, loading, reload } = useAsync(() => api.provider.orders({ date, locationId }), [date, locationId])
  const known = useRef<Set<string> | null>(null)

  // Poll for new bookings; real-time push replaces this once the socket endpoint exists.
  useEffect(() => { const t = setInterval(reload, 15_000); return () => clearInterval(t) }, [reload])
  useEffect(() => {
    if (!data) return
    const ids = new Set(data.map((o) => o.id))
    if (known.current && data.some((o) => !known.current!.has(o.id) && o.source === 'APP')) { beep(); toast(`New ${labels.order.toLowerCase()}`) }
    known.current = ids
  }, [data, labels.order, toast])

  async function act(o: Order, action: string, note?: string) {
    try { await api.orders.act(o.id, action, note); toast(`${LABEL[action] ?? action} done`); setReasonFor(null); setReason(''); reload() }
    catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title={`${labels.order}s`} subtitle="Updates every few seconds" actions={<>
        {provider.locations.length > 1 && <Select label={labels.location} value={locationId} onChange={(e) => setLocationId(e.target.value)}>{provider.locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}</Select>}
        <Input label="Day" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </>} />
      <ErrorNote message={error} />
      {loading && !data ? <Spinner /> : !data?.length ? <Card><Empty>No {labels.order.toLowerCase()}s for this day.</Empty></Card> : (
        <div className="stack">
          {data.map((o) => (
            <Card key={o.id} className="row wrap spread">
              <div className="stack tight grow">
                <div className="row wrap">
                  <strong style={{ fontSize: 17 }}>{timeOf(o.startsAt)} – {timeOf(o.endsAt)}</strong>
                  <Badge tone={statusTone(o.status)}>{o.statusLabel}</Badge>
                  {o.source === 'WALK_IN' && <Badge tone="info">walk-in</Badge>}
                  <span className="muted small">#{o.code}</span>
                </div>
                <div>{o.customerName}{o.assetLabel ? ` · ${o.assetLabel}` : ''} <span className="muted">· {o.variantKey.toLowerCase()}</span></div>
                <div className="muted small">{o.lines.map((l) => l.name).join(', ')}</div>
                {o.cancellationReason && <div className="small" style={{ color: 'var(--c-bad)' }}>Reason: {o.cancellationReason}</div>}
              </div>
              <div className="stack tight right">
                <strong>{money(o.subtotalMinor)}</strong>
                {o.commissionMinor ? <span className="muted small">you earn {money(o.subtotalMinor - (o.commissionMinor ?? 0))}</span> : null}
              </div>
              <div className="row wrap">
                {o.actions.filter((a) => LABEL[a]).map((a) => (
                  <Button key={a} small variant={PRIMARY.has(a) ? 'primary' : a === 'NO_SHOW' ? undefined : 'danger'}
                    onClick={() => (NEEDS_REASON.has(a) ? setReasonFor({ order: o, action: a }) : void act(o, a))}>{LABEL[a]}</Button>
                ))}
              </div>
            </Card>
          ))}
        </div>
      )}
      {reasonFor && (
        <Modal title={`${LABEL[reasonFor.action]} ${reasonFor.order.code}`} onClose={() => setReasonFor(null)}>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); void act(reasonFor.order, reasonFor.action, reason) }}>
            <p>The customer is refunded in full. This counts against your cancellation score.</p>
            <label className="field">Reason (shown to the customer)<textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
            <div className="row spread"><Button type="button" onClick={() => setReasonFor(null)}>Back</Button><Button variant="danger" disabled={!reason.trim()}>Confirm</Button></div>
          </form>
        </Modal>
      )}
    </>
  )
}
