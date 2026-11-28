import { useMemo, useState } from 'react'
import { STATUS_LABEL, isoDate, messageOf, money, promotionProblem, promotionSentence, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Promotion, PromotionInput, PromotionStatus, Provider } from '@odyssey/api'
import { Badge, Button, Card, Chips, EmptyState, ErrorNote, Modal, Notice, PageHeader, PromotionEditor, SkeletonRows, useConfirm, useToast, type Tone } from '@odyssey/ui'
import { useWithPin } from '../pin'

const TONE: Record<PromotionStatus, Tone> = { LIVE: 'good', SCHEDULED: 'info', PAUSED: 'warn', ENDED: 'neutral' }
type Filter = 'current' | 'PAUSED' | 'ENDED'

const blank = (type: PromotionInput['type']): PromotionInput => type === 'HAPPY_HOUR'
  ? { type, name: 'Afternoon happy hour', kind: 'PERCENT', value: 2000, maxDiscountMinor: null, locationIds: [], services: [], variantKeys: [], days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY'], startTime: '14:00', endTime: '16:00', startDate: null, endDate: null, active: true }
  : { type, name: '', kind: 'PERCENT', value: 1500, maxDiscountMinor: null, locationIds: [], services: [], variantKeys: [], days: [], startTime: null, endTime: null, startDate: isoDate(1), endDate: isoDate(1), active: true }

const toInput = (p: Promotion): PromotionInput => ({
  type: p.type, name: p.name, kind: p.kind, value: p.value, maxDiscountMinor: p.maxDiscountMinor ?? null, locationIds: p.locationIds, services: p.services, variantKeys: p.variantKeys,
  days: p.days, startTime: p.startTime ?? null, endTime: p.endTime ?? null, startDate: p.startDate ?? null, endDate: p.endDate ?? null, active: p.active,
})

/**
 * Happy hours and date discounts. They apply on their own (no code), customers see the lower price on the slot picker,
 * and the vendor pays for them: the discount lowers the price, so commission is taken on what the customer actually pays.
 */
export function PromotionsPage({ provider, owner }: { provider: Provider; owner: boolean }) {
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const withPin = useWithPin()
  const { data, error, loading, reload } = useAsync(() => api.promotions.vendor.list(), [])
  const [filter, setFilter] = useState<Filter>('current')
  const [editing, setEditing] = useState<{ id?: string; draft: PromotionInput } | null>(null)

  const own = useMemo(() => (data ?? []).filter((p) => p.providerId === provider.id), [data, provider.id])
  const platform = useMemo(() => (data ?? []).filter((p) => !p.providerId), [data])
  const counts = { current: own.filter((p) => p.status === 'LIVE' || p.status === 'SCHEDULED').length, PAUSED: own.filter((p) => p.status === 'PAUSED').length, ENDED: own.filter((p) => p.status === 'ENDED').length }
  const shown = own.filter((p) => (filter === 'current' ? p.status === 'LIVE' || p.status === 'SCHEDULED' : p.status === filter))
  const outletName = (id: string) => provider.locations.find((l) => l.id === id)?.name ?? 'an outlet'
  const where = (p: Promotion) => (p.locationIds.length === 0 ? (provider.locations.length > 1 ? `All ${labels.location.toLowerCase()}s` : provider.locations[0]?.name ?? '') : p.locationIds.map(outletName).join(', '))

  async function toggle(p: Promotion) {
    if (p.active && !(await confirm({ title: `Pause "${p.name}"?`, confirmLabel: 'Pause', body: 'Customers see your usual prices again straight away. Bookings already made keep their price.' }))) return
    try { await api.promotions.vendor.setActive(p.id, !p.active); toast(p.active ? 'Paused. Usual prices are back.' : 'Live again.'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  async function remove(p: Promotion) {
    if (!(await confirm({ title: `Delete "${p.name}"?`, confirmLabel: 'Delete', tone: 'danger', body: p.bookings ? `It is on ${p.bookings} booking${p.bookings === 1 ? '' : 's'}. They keep their price; the offer stops for new bookings. To use it again later, pause it instead.` : 'It stops for new bookings straight away.' }))) return
    try { await withPin((pin) => api.promotions.vendor.remove(p.id, pin), 'Owner PIN to delete an offer'); toast('Offer deleted'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  const row = (p: Promotion, readOnly = false) => (
    <div key={p.id} className={`promo-row ${p.status === 'ENDED' ? 'ended' : ''}`}>
      <div className="what">
        <div className="row tight"><strong>{p.name}</strong><Badge tone={TONE[p.status]} dot>{STATUS_LABEL[p.status]}</Badge>{readOnly && <Badge tone="info">Paid by {p.providerName ?? 'the platform'}</Badge>}</div>
        <span>{p.summary}</span>
        <span className="muted small numbers">
          {readOnly ? 'Runs at your outlets. The platform pays for it, so your earnings are not reduced.' : <>{where(p)} · {p.bookings} booking{p.bookings === 1 ? '' : 's'} · {money(p.discountGivenMinor)} off so far</>}
        </span>
      </div>
      {!readOnly && p.editable && (
        <div className="row tight">
          {p.status !== 'ENDED' && <Button small icon={p.active ? 'pause' : 'play'} onClick={() => toggle(p)}>{p.active ? 'Pause' : 'Resume'}</Button>}
          {owner && <Button small variant="ghost" icon="edit" onClick={() => setEditing({ id: p.id, draft: toInput(p) })}>Edit</Button>}
          {owner && <Button small variant="ghost" icon="trash" aria-label={`Delete ${p.name}`} onClick={() => remove(p)} />}
        </div>
      )}
    </div>
  )

  return (
    <>
      <PageHeader title="Promotions" subtitle="Lower your prices at quiet times or on special days. Customers see the deal on the time picker, no code needed."
        actions={owner && <>
          <Button icon="calendar" onClick={() => setEditing({ draft: blank('DATE_DISCOUNT') })}>New date discount</Button>
          <Button variant="primary" icon="plus" onClick={() => setEditing({ draft: blank('HAPPY_HOUR') })}>New happy hour</Button>
        </>} />
      {!owner && <Notice tone="info">Only the owner can create or change offers. You can pause and resume them.</Notice>}
      <ErrorNote message={error} onRetry={reload} />
      <Card flush title={<Chips label="Show" value={filter} onChange={setFilter} options={[
        { value: 'current', label: 'Live and scheduled', count: counts.current }, { value: 'PAUSED', label: 'Paused', count: counts.PAUSED }, { value: 'ENDED', label: 'Ended', count: counts.ENDED },
      ]} />}>
        {loading && !data ? <SkeletonRows rows={3} cols={2} /> : shown.length === 0 ? (
          filter === 'current'
            ? <EmptyState icon="zap" title="No offers running" action={owner && <Button icon="plus" onClick={() => setEditing({ draft: blank('HAPPY_HOUR') })}>Set up a happy hour</Button>}>
                A happy hour such as 20% off Monday to Thursday, 2–4 pm, fills slots that would otherwise sit empty.
              </EmptyState>
            : <EmptyState icon="zap" title={filter === 'PAUSED' ? 'Nothing paused' : 'Nothing has ended yet'} />
        ) : shown.map((p) => row(p))}
      </Card>
      {platform.length > 0 && (
        <Card flush title="Platform offers at your outlets" className="promo-platform">
          {platform.map((p) => row(p, true))}
        </Card>
      )}
      <p className="muted small" style={{ marginTop: 12 }}>
        Only one offer applies to a booking: the one that saves the customer the most. A voucher code can still be used on top. Offers do not apply to walk-ins.
      </p>
      {editing && <EditPromotion provider={provider} id={editing.id} initial={editing.draft} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
    </>
  )
}

function EditPromotion({ provider, id, initial, onClose, onSaved }: { provider: Provider; id?: string; initial: PromotionInput; onClose: () => void; onSaved: () => void }) {
  const { api, config } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const withPin = useWithPin()
  const [draft, setDraft] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const variants = config?.config.variants.options ?? []
  const variantLabel = (k: string) => variants.find((v) => v.key === k)?.label ?? k
  // Service names across every outlet, so one offer can cover the same service everywhere.
  const items = useAsync(() => Promise.all(provider.locations.map((l) => api.provider.items(l.id))), [provider.id])
  const services = useMemo(() => [...new Set((items.data ?? []).flat().filter((i) => i.active).map((i) => i.name))].sort(), [items.data])
  const sentence = promotionSentence(draft, variantLabel, config?.config.money.currency)

  async function save() {
    const problem = promotionProblem(draft)
    if (problem) { setError(problem); return }
    setError(null); setBusy(true)
    try {
      const saved = await withPin((pin) => api.promotions.vendor.save(draft, pin, id), 'Owner PIN to change prices')
      if (saved) { toast(id ? 'Offer updated' : saved.status === 'LIVE' ? 'Offer is live. Customers see it now.' : 'Offer saved'); onSaved() }
    } catch (e) { setError(messageOf(e)) } finally { setBusy(false) }
  }

  return (
    <Modal title={id ? 'Edit offer' : draft.type === 'HAPPY_HOUR' ? 'New happy hour' : 'New date discount'} onClose={onClose} wide
      footer={<><span className="muted small grow">You pay for this discount. Commission is taken on the lower price.</span><Button onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>{id ? 'Save changes' : 'Save offer'}</Button></>}>
      <PromotionEditor value={draft} onChange={setDraft} outlets={provider.locations} services={services} variants={variants} variantName={config?.config.variants.name}
        today={isoDate()} sentence={sentence} error={error} outletWord={labels.location} />
    </Modal>
  )
}
