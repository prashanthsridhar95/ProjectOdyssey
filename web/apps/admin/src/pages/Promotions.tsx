import { useMemo, useState } from 'react'
import { STATUS_LABEL, amountText, dateOnly, isoDate, messageOf, money, promotionProblem, promotionSentence, relative, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Promotion, PromotionInput, PromotionStatus, ReferralSettings, RewardTerms, Voucher } from '@odyssey/api'
import {
  Badge, Button, Card, DataTable, EmptyState, ErrorNote, Input, KpiStrip, Modal, Notice, PageHeader, PromotionEditor, Select, SkeletonRows, SwitchRow, Tabs,
  useConfirm, useToast, type Tone,
} from '@odyssey/ui'

const TONE: Record<PromotionStatus, Tone> = { LIVE: 'good', SCHEDULED: 'info', PAUSED: 'warn', ENDED: 'neutral' }
type Tab = 'platform' | 'vendors' | 'vouchers' | 'referrals'

/** Discounts across the marketplace: the platform's own date offers, vendors' offers, voucher codes, and refer-a-friend. */
export function PromotionsPage() {
  const { api } = useSession()
  const [tab, setTab] = useState<Tab>('platform')
  const summary = useAsync(() => api.promotions.admin.summary(), [])
  const offers = useAsync(() => api.promotions.admin.list(), [])
  const platform = (offers.data ?? []).filter((p) => !p.providerId)
  const vendors = (offers.data ?? []).filter((p) => p.providerId)
  const s = summary.data

  return (
    <>
      <PageHeader title="Promotions" subtitle="Discounts on live bookings, who pays for them, and the codes and referral rewards customers can use." />
      <KpiStrip items={[
        { label: 'Discounts given', value: s ? money(s.totalMinor) : '–', foot: s ? `${s.bookings} booking${s.bookings === 1 ? '' : 's'}` : undefined, icon: 'zap' },
        { label: 'Paid by vendors', value: s ? money(s.vendorFundedMinor) : '–', foot: 'Lowers their price and our commission', icon: 'store' },
        { label: 'Paid by the platform', value: s ? money(s.platformFundedMinor) : '–', foot: 'Comes out of commission', icon: 'wallet' },
        { label: 'Vouchers and referrals', value: s ? money(s.vouchersMinor + s.referralsMinor) : '–', foot: s ? `${money(s.referralsMinor)} from referrals` : undefined, icon: 'tag' },
      ]} />
      <div style={{ margin: '18px 0' }}>
        <Tabs value={tab} onChange={setTab} tabs={[
          { value: 'platform', label: 'Platform offers', count: platform.filter((p) => p.status === 'LIVE').length },
          { value: 'vendors', label: 'Vendor offers', count: vendors.filter((p) => p.status === 'LIVE').length },
          { value: 'vouchers', label: 'Vouchers' },
          { value: 'referrals', label: 'Refer a friend' },
        ]} />
      </div>
      {tab === 'platform' && <PlatformOffers rows={platform} loading={offers.loading && !offers.data} error={offers.error} reload={() => { offers.reload(); summary.reload() }} />}
      {tab === 'vendors' && <VendorOffers rows={vendors} loading={offers.loading && !offers.data} error={offers.error} reload={offers.reload} />}
      {tab === 'vouchers' && <Vouchers onChange={summary.reload} />}
      {tab === 'referrals' && <Referrals />}
    </>
  )
}

const blankOffer = (): PromotionInput => ({
  type: 'DATE_DISCOUNT', name: '', kind: 'PERCENT', value: 1000, maxDiscountMinor: 15_000, locationIds: [], services: [], variantKeys: [], days: [],
  startTime: null, endTime: null, startDate: isoDate(1), endDate: isoDate(1), active: true,
})

const toInput = (p: Promotion): PromotionInput => ({
  type: p.type, name: p.name, kind: p.kind, value: p.value, maxDiscountMinor: p.maxDiscountMinor ?? null, locationIds: p.locationIds, services: p.services, variantKeys: p.variantKeys,
  days: p.days, startTime: p.startTime ?? null, endTime: p.endTime ?? null, startDate: p.startDate ?? null, endDate: p.endDate ?? null, active: p.active,
})

function OfferRow({ p, children, who }: { p: Promotion; children?: React.ReactNode; who?: string }) {
  return (
    <div className={`promo-row ${p.status === 'ENDED' ? 'ended' : ''}`}>
      <div className="what">
        <div className="row tight"><strong>{p.name}</strong><Badge tone={TONE[p.status]} dot>{STATUS_LABEL[p.status]}</Badge>{who && <span className="muted small">{who}</span>}</div>
        <span>{p.summary}</span>
        <span className="muted small numbers">{p.bookings} booking{p.bookings === 1 ? '' : 's'} · {money(p.discountGivenMinor)} off so far · paid by {p.fundedBy === 'VENDOR' ? 'the vendor' : 'the platform'}</span>
      </div>
      <div className="row tight">{children}</div>
    </div>
  )
}

function PlatformOffers({ rows, loading, error, reload }: { rows: Promotion[]; loading: boolean; error: string | null; reload: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const [editing, setEditing] = useState<{ id?: string; draft: PromotionInput } | null>(null)

  async function toggle(p: Promotion) {
    if (p.active && !(await confirm({ title: `Pause "${p.name}"?`, confirmLabel: 'Pause', body: 'Customers see the usual prices again straight away. Bookings already made keep their price.' }))) return
    try { await api.promotions.admin.setActive(p.id, !p.active); toast(p.active ? 'Paused' : 'Live again'); reload() } catch (e) { toast(messageOf(e), true) }
  }
  async function remove(p: Promotion) {
    if (!(await confirm({ title: `Delete "${p.name}"?`, confirmLabel: 'Delete', tone: 'danger', body: 'It stops for new bookings. Bookings already made keep their price.' }))) return
    try { await api.promotions.admin.remove(p.id); toast('Offer deleted'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      <Card flush title="Offers the platform pays for" actions={<Button variant="primary" icon="plus" onClick={() => setEditing({ draft: blankOffer() })}>New platform offer</Button>}>
        {loading ? <SkeletonRows rows={3} cols={2} /> : rows.length === 0 ? (
          <EmptyState icon="calendar" title="No platform offers" action={<Button icon="plus" onClick={() => setEditing({ draft: blankOffer() })}>Plan a festival discount</Button>}>
            A date discount runs at every outlet on the days you choose, for example 10% off on Diwali. The platform pays for it out of commission; vendors earn as usual.
          </EmptyState>
        ) : rows.map((p) => (
          <OfferRow key={p.id} p={p}>
            {p.status !== 'ENDED' && <Button small icon={p.active ? 'pause' : 'play'} onClick={() => toggle(p)}>{p.active ? 'Pause' : 'Resume'}</Button>}
            <Button small variant="ghost" icon="edit" onClick={() => setEditing({ id: p.id, draft: toInput(p) })}>Edit</Button>
            <Button small variant="ghost" icon="trash" aria-label={`Delete ${p.name}`} onClick={() => remove(p)} />
          </OfferRow>
        ))}
      </Card>
      {editing && <EditOffer id={editing.id} initial={editing.draft} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
    </>
  )
}

function EditOffer({ id, initial, onClose, onSaved }: { id?: string; initial: PromotionInput; onClose: () => void; onSaved: () => void }) {
  const { api, config } = useSession()
  const toast = useToast()
  const [draft, setDraft] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const variants = config?.config.variants.options ?? []
  const services = (config?.config.serviceTemplates ?? []).map((t) => t.name)
  const sentence = promotionSentence(draft, (k) => variants.find((v) => v.key === k)?.label ?? k, config?.config.money.currency)

  async function save() {
    const problem = promotionProblem(draft)
    if (problem) { setError(problem); return }
    setError(null); setBusy(true)
    try { await api.promotions.admin.save(draft, id); toast(id ? 'Offer updated' : 'Offer saved'); onSaved() } catch (e) { setError(messageOf(e)) } finally { setBusy(false) }
  }

  return (
    <Modal title={id ? 'Edit platform offer' : 'New platform offer'} onClose={onClose} wide
      footer={<><span className="muted small grow">Runs at every outlet. Only the best offer applies to a booking.</span><Button onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>{id ? 'Save changes' : 'Save offer'}</Button></>}>
      <PromotionEditor value={draft} onChange={setDraft} services={services} variants={variants} variantName={config?.config.variants.name} today={isoDate()} sentence={sentence} error={error}
        fundingNote="The platform pays for this discount. It comes out of commission, so vendors are paid as usual." />
    </Modal>
  )
}

function VendorOffers({ rows, loading, error, reload }: { rows: Promotion[]; loading: boolean; error: string | null; reload: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const labels = useLabels()
  async function toggle(p: Promotion) {
    if (p.active) {
      const ok = await confirm({ title: `Pause ${p.providerName ?? 'this vendor'}'s "${p.name}"?`, confirmLabel: 'Pause offer', body: 'Use this when an offer breaks the marketplace rules. Customers see the usual price straight away. The vendor sees it as paused and can resume it.' })
      if (!ok) return
    }
    try { await api.promotions.admin.setActive(p.id, !p.active); toast(p.active ? 'Paused' : 'Live again'); reload() } catch (e) { toast(messageOf(e), true) }
  }
  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      <Card flush title={`Offers ${labels.provider.toLowerCase()}s run and pay for`}>
        {loading ? <SkeletonRows rows={3} cols={2} /> : rows.length === 0
          ? <EmptyState icon="store" title={`No ${labels.provider.toLowerCase()} offers yet`}>{labels.provider}s set happy hours and date discounts under Promotions in their console.</EmptyState>
          : rows.map((p) => (
            <OfferRow key={p.id} p={p} who={p.providerName ?? undefined}>
              {p.status !== 'ENDED' && <Button small icon={p.active ? 'pause' : 'play'} onClick={() => toggle(p)}>{p.active ? 'Pause' : 'Resume'}</Button>}
            </OfferRow>
          ))}
      </Card>
    </>
  )
}

const valueText = (v: Pick<Voucher, 'kind' | 'value' | 'maxDiscountMinor'>) => amountText(v.kind, v.value) + (v.kind === 'PERCENT' && v.maxDiscountMinor ? ` up to ${money(v.maxDiscountMinor)}` : '')

function Vouchers({ onChange }: { onChange: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const confirm = useConfirm()
  const { data, error, loading, reload } = useAsync(() => api.promotions.admin.vouchers(), [])
  const [creating, setCreating] = useState(false)

  async function toggle(v: Voucher) {
    if (v.active && !(await confirm({ title: `Pause ${v.code}?`, confirmLabel: 'Pause', body: 'Customers who type it see that it is not valid. Bookings that already used it are not affected.' }))) return
    try { await api.promotions.admin.setVoucherActive(v.code, !v.active); toast(v.active ? `${v.code} paused` : `${v.code} is live again`); reload(); onChange() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      <Card flush title="Voucher and coupon codes" actions={<Button variant="primary" icon="plus" onClick={() => setCreating(true)}>New voucher</Button>}>
        <DataTable rows={data} loading={loading} rowKey={(v) => v.code} search={(v) => `${v.code} ${v.description} ${v.providerName ?? ''}`} searchPlaceholder="Search codes" exportName="vouchers"
          empty={<EmptyState icon="tag" title="No vouchers" action={<Button icon="plus" onClick={() => setCreating(true)}>Create one</Button>}>Codes customers type at checkout, such as FIRSTWASH for ₹100 off a first booking.</EmptyState>}
          columns={[
            { key: 'code', header: 'Code', cell: (v) => <div className="stack xtight"><strong className="mono">{v.code}</strong><span className="muted small">{v.description}</span></div>, sort: (v) => v.code, csv: (v) => v.code },
            { key: 'value', header: 'Discount', cell: (v) => <>{valueText(v)}{v.minSubtotalMinor > 0 && <div className="muted small">over {money(v.minSubtotalMinor)}</div>}</>, csv: (v) => valueText(v) },
            { key: 'who', header: 'Paid by', cell: (v) => (v.fundedBy === 'VENDOR' ? <span>{v.providerName ?? 'Vendor'}</span> : <span className="muted">Platform</span>), csv: (v) => (v.fundedBy === 'VENDOR' ? v.providerName ?? 'Vendor' : 'Platform') },
            { key: 'valid', header: 'Valid', cell: (v) => <span className="small">{v.startsAt ? dateOnly(v.startsAt) : 'Now'} – {v.expiresAt ? dateOnly(v.expiresAt) : 'no end'}</span> },
            { key: 'uses', header: 'Used', align: 'right', cell: (v) => <span className="numbers">{v.uses ?? 0}{v.usageLimit ? ` / ${v.usageLimit}` : ''}</span>, sort: (v) => v.uses ?? 0, csv: (v) => v.uses ?? 0 },
            { key: 'given', header: 'Given', align: 'right', cell: (v) => money(v.discountGivenMinor ?? 0), sort: (v) => v.discountGivenMinor ?? 0, csv: (v) => (v.discountGivenMinor ?? 0) / 100 },
            { key: 'status', header: 'Status', cell: (v) => <Badge tone={!v.active ? 'warn' : v.expiresAt && v.expiresAt < new Date().toISOString() ? 'neutral' : 'good'} dot>{!v.active ? 'Paused' : v.expiresAt && v.expiresAt < new Date().toISOString() ? 'Ended' : 'Live'}</Badge> },
            { key: 'act', header: '', cell: (v) => <Button small variant="ghost" icon={v.active ? 'pause' : 'play'} onClick={(e) => { e.stopPropagation(); toggle(v) }}>{v.active ? 'Pause' : 'Resume'}</Button> },
          ]} />
      </Card>
      <p className="muted small" style={{ marginTop: 12 }}>Personal vouchers from refer-a-friend are not listed here. See their numbers under Refer a friend.</p>
      {creating && <NewVoucher onClose={() => setCreating(false)} onSaved={() => { setCreating(false); reload(); onChange() }} />}
    </>
  )
}

function NewVoucher({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const labels = useLabels()
  const vendors = useAsync(() => api.admin.providers('LIVE'), [])
  const [v, setV] = useState<Voucher>({ code: '', kind: 'FLAT', value: 10_000, maxDiscountMinor: null, minSubtotalMinor: 0, expiresAt: null, startsAt: null, usageLimit: null, perUserLimit: 1, active: true, description: '', fundedBy: 'PLATFORM', providerId: null })
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (patch: Partial<Voucher>) => setV({ ...v, ...patch })
  const vendorName = vendors.data?.find((p) => p.id === v.providerId)?.name
  const sentence = useMemo(() => {
    const parts = [valueText(v)]
    if (v.minSubtotalMinor > 0) parts.push(`on bookings over ${money(v.minSubtotalMinor)}`)
    parts.push(v.providerId ? `at ${vendorName ?? 'one vendor'}` : 'at every outlet')
    parts.push(v.perUserLimit === 1 ? 'once per customer' : `up to ${v.perUserLimit} times per customer`)
    if (v.usageLimit) parts.push(`first ${v.usageLimit} bookings only`)
    return `${parts.join(', ')}. Paid by ${v.fundedBy === 'VENDOR' ? vendorName ?? 'the vendor' : 'the platform'}.`
  }, [v, vendorName])

  async function save() {
    setError(null)
    const code = v.code.trim().toUpperCase()
    if (!/^[A-Z0-9-]{3,30}$/.test(code)) { setError('Codes are 3 to 30 letters, digits or dashes'); return }
    if (v.value <= 0) { setError('Give the discount'); return }
    if (v.kind === 'PERCENT' && v.value > 10_000) { setError('A percentage cannot be over 100%'); return }
    if (v.fundedBy === 'VENDOR' && !v.providerId) { setError(`Pick the ${labels.provider.toLowerCase()} who pays for this code`); return }
    if (v.startsAt && v.expiresAt && v.expiresAt <= v.startsAt) { setError('The end date must be after the start date'); return }
    setBusy(true)
    try { await api.promotions.admin.saveVoucher({ ...v, code }); toast(`${code} is ready to use`); onSaved() } catch (e) { setError(messageOf(e)) } finally { setBusy(false) }
  }
  const dayStart = (d: string) => (d ? new Date(`${d}T00:00:00+05:30`).toISOString() : null)
  const dayEnd = (d: string) => (d ? new Date(`${d}T23:59:59+05:30`).toISOString() : null)
  const asDay = (iso?: string | null) => (iso ? new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date(iso)) : '')

  return (
    <Modal title="New voucher" onClose={onClose} wide footer={<><span className="muted small grow">{sentence}</span><Button onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Create voucher</Button></>}>
      <div className="stack">
        <div className="grid cols-2">
          <Input label="Code" value={v.code} onChange={(e) => set({ code: e.target.value.toUpperCase() })} placeholder="DIWALI100" hint="What customers type at checkout." />
          <Input label="Description" value={v.description} maxLength={120} onChange={(e) => set({ description: e.target.value })} placeholder="₹100 off for Diwali" hint="Shown next to the code." />
        </div>
        <div className="grid cols-2">
          <Select label="Discount" value={v.kind} onChange={(e) => set({ kind: e.target.value as Voucher['kind'], value: e.target.value === 'PERCENT' ? 1000 : 10_000 })}>
            <option value="FLAT">Amount off</option><option value="PERCENT">Percentage off</option>
          </Select>
          <Input label={v.kind === 'PERCENT' ? 'Percentage' : 'Amount off (₹)'} type="number" min={1} value={v.value / 100 || ''} onChange={(e) => set({ value: Math.round(Number(e.target.value) * 100) })} />
        </div>
        <div className="grid cols-2">
          {v.kind === 'PERCENT'
            ? <Input label="Most it takes off (₹, optional)" type="number" min={1} value={v.maxDiscountMinor ? v.maxDiscountMinor / 100 : ''} placeholder="No limit" onChange={(e) => set({ maxDiscountMinor: e.target.value ? Math.round(Number(e.target.value) * 100) : null })} />
            : <span />}
          <Input label="Minimum booking (₹)" type="number" min={0} value={v.minSubtotalMinor / 100 || ''} placeholder="None" onChange={(e) => set({ minSubtotalMinor: Math.round(Number(e.target.value || 0) * 100) })} hint="Before tax and fees." />
        </div>
        <div className="grid cols-2">
          <label className="field"><span>Valid from</span><input type="date" min={isoDate()} value={asDay(v.startsAt)} onChange={(e) => set({ startsAt: dayStart(e.target.value) })} /><span className="hint">Leave empty to start now.</span></label>
          <label className="field"><span>Valid until</span><input type="date" min={asDay(v.startsAt) || isoDate()} value={asDay(v.expiresAt)} onChange={(e) => set({ expiresAt: dayEnd(e.target.value) })} /><span className="hint">Leave empty for no end date.</span></label>
        </div>
        <div className="grid cols-2">
          <Input label="Total uses (optional)" type="number" min={1} value={v.usageLimit ?? ''} placeholder="No limit" onChange={(e) => set({ usageLimit: e.target.value ? Number(e.target.value) : null })} />
          <Input label="Uses per customer" type="number" min={1} value={v.perUserLimit} onChange={(e) => set({ perUserLimit: Math.max(1, Number(e.target.value) || 1) })} />
        </div>
        <div className="grid cols-2">
          <Select label="Who pays" value={v.fundedBy} onChange={(e) => set({ fundedBy: e.target.value as Voucher['fundedBy'] })}>
            <option value="PLATFORM">The platform (out of commission)</option>
            <option value="VENDOR">A {labels.provider.toLowerCase()} (lowers their price)</option>
          </Select>
          <Select label={`Works at`} value={v.providerId ?? ''} onChange={(e) => set({ providerId: e.target.value || null })}>
            <option value="">{v.fundedBy === 'VENDOR' ? `Choose a ${labels.provider.toLowerCase()}` : 'Every outlet'}</option>
            {(vendors.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </div>
        {v.fundedBy === 'VENDOR' && <Notice tone="warn">Agree this with the {labels.provider.toLowerCase()} first. Their payout for each booking that uses the code is lower by the discount.</Notice>}
        <ErrorNote message={error} />
      </div>
    </Modal>
  )
}

const termsText = (t: RewardTerms) => `${amountText(t.kind, t.value)}${t.kind === 'PERCENT' && t.maxDiscountMinor ? ` up to ${money(t.maxDiscountMinor)}` : ''}${t.minSubtotalMinor ? ` on bookings over ${money(t.minSubtotalMinor)}` : ''}, valid ${t.validDays} days`

function TermsEditor({ title, hint, value, onChange }: { title: string; hint: string; value: RewardTerms; onChange: (t: RewardTerms) => void }) {
  const set = (p: Partial<RewardTerms>) => onChange({ ...value, ...p })
  return (
    <div className="stack tight">
      <strong>{title}</strong><span className="muted small">{hint}</span>
      <div className="grid cols-2">
        <Select label="Reward" value={value.kind} onChange={(e) => set({ kind: e.target.value as RewardTerms['kind'], value: e.target.value === 'PERCENT' ? 1000 : 10_000, maxDiscountMinor: null })}>
          <option value="FLAT">Amount off</option><option value="PERCENT">Percentage off</option>
        </Select>
        <Input label={value.kind === 'PERCENT' ? 'Percentage' : 'Amount (₹)'} type="number" min={1} value={value.value / 100 || ''} onChange={(e) => set({ value: Math.round(Number(e.target.value) * 100) })} />
        {value.kind === 'PERCENT' && <Input label="Most it takes off (₹)" type="number" min={1} value={value.maxDiscountMinor ? value.maxDiscountMinor / 100 : ''} placeholder="No limit" onChange={(e) => set({ maxDiscountMinor: e.target.value ? Math.round(Number(e.target.value) * 100) : null })} />}
        <Input label="Minimum booking (₹)" type="number" min={0} value={value.minSubtotalMinor / 100 || ''} placeholder="None" onChange={(e) => set({ minSubtotalMinor: Math.round(Number(e.target.value || 0) * 100) })} />
        <Input label="Valid for (days)" type="number" min={1} max={365} value={value.validDays} onChange={(e) => set({ validDays: Number(e.target.value) || 1 })} />
      </div>
      <span className="small">{termsText(value)}</span>
    </div>
  )
}

function Referrals() {
  const { api, user } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.promotions.admin.referrals(), [])
  const [draft, setDraft] = useState<ReferralSettings | null>(null)
  const [busy, setBusy] = useState(false)
  const settings = draft ?? data?.settings
  const dirty = draft !== null && JSON.stringify(draft) !== JSON.stringify(data?.settings)
  const canEdit = user?.role === 'ADMIN'

  async function save() {
    if (!draft) return
    setBusy(true)
    try { await api.promotions.admin.saveReferralSettings(draft); toast('Referral rewards saved. New vouchers use these terms.'); setDraft(null); reload() } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }

  if (loading && !data) return <Card><SkeletonRows rows={4} cols={3} /></Card>
  return (
    <>
      <ErrorNote message={error} onRetry={reload} />
      {data && <KpiStrip items={[
        { label: 'Friends joined', value: data.referrals, foot: `${data.pending} waiting for a first booking`, icon: 'users' },
        { label: 'Friends rewarded', value: data.rewarded, foot: 'After the new customer\'s first completed booking', icon: 'checkCircle' },
        { label: 'Vouchers used', value: data.welcomeVouchersUsed + data.rewardVouchersUsed, foot: `${data.welcomeVouchersUsed} welcome, ${data.rewardVouchersUsed} thank-you`, icon: 'tag' },
        { label: 'Cost so far', value: money(data.discountGivenMinor), foot: 'Paid by the platform', icon: 'wallet' },
      ]} />}
      {settings && (
        <Card title="Rewards" className="promo-platform" actions={canEdit && <Button variant="primary" disabled={!dirty} busy={busy} onClick={save}>Save rewards</Button>}>
          <div className="stack">
            <SwitchRow title="Refer a friend is on" description="Customers see their code and a share button in the app. Turning it off stops new codes from being used; vouchers already given still work."
              checked={settings.enabled} disabled={!canEdit} onChange={(enabled) => setDraft({ ...settings, enabled })} />
            <div className="grid cols-2">
              <TermsEditor title="For the new customer" hint="Given when they use a friend's code on their first booking." value={settings.welcome} onChange={(welcome) => setDraft({ ...settings, welcome })} />
              <TermsEditor title="For the friend who shared" hint="Given once the new customer's first booking is completed." value={settings.reward} onChange={(reward) => setDraft({ ...settings, reward })} />
            </div>
          </div>
        </Card>
      )}
      <Card flush title="Recent referrals" className="promo-platform">
        <DataTable rows={data?.recent} rowKey={(r) => r.id} search={(r) => `${r.referrerName} ${r.refereeName} ${r.code}`} exportName="referrals"
          empty={<EmptyState icon="users" title="No referrals yet">When a new customer uses a friend's code, it shows here.</EmptyState>}
          columns={[
            { key: 'who', header: 'Shared by', cell: (r) => <strong>{r.referrerName}</strong>, sort: (r) => r.referrerName, csv: (r) => r.referrerName },
            { key: 'friend', header: 'New customer', cell: (r) => r.refereeName, csv: (r) => r.refereeName },
            { key: 'code', header: 'Code', cell: (r) => <span className="mono">{r.code}</span>, csv: (r) => r.code },
            { key: 'when', header: 'Joined', cell: (r) => relative(r.createdAt), sort: (r) => r.createdAt, csv: (r) => r.createdAt },
            { key: 'status', header: 'Status', cell: (r) => <Badge tone={r.status === 'REWARDED' ? 'good' : 'warn'} dot>{r.status === 'REWARDED' ? 'Rewarded' : 'Waiting for first booking'}</Badge>, csv: (r) => r.status },
          ]} />
      </Card>
    </>
  )
}
