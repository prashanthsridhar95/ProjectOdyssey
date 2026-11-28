import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { bpsToPercent, dateOnly, messageOf, phone, relative, titleCase, useAsync, useLabels, useSession } from '@odyssey/api'
import type { Provider } from '@odyssey/api'
import { Badge, Button, Card, DataTable, DescriptionList, EmptyState, ErrorNote, Icon, Input, Notice, PageHeader, Skeleton, StatusBadge, Tabs, toE164, PhoneInput, isValidIndianMobile, useConfirm, useToast } from '@odyssey/ui'

const STATUS_TABS = [['', 'All'], ['SUBMITTED', 'Submitted'], ['UNDER_REVIEW', 'In review'], ['CHANGES_REQUESTED', 'Changes requested'], ['APPROVED', 'Approved'], ['LIVE', 'Live'], ['SUSPENDED', 'Suspended'], ['REJECTED', 'Rejected']] as const

/** Which verification steps are available from each status, and what each one means for the vendor. */
const STEPS: Record<string, { action: string; label: string; note?: boolean; tone?: 'primary' | 'danger'; explain: string }[]> = {
  SUBMITTED: [
    { action: 'REVIEW', label: 'Start review', tone: 'primary', explain: 'Marks the application as being reviewed, so others on the team know it is taken.' },
    { action: 'REJECT', label: 'Reject', note: true, tone: 'danger', explain: 'Closes the application. The vendor is told why and cannot go live.' },
  ],
  UNDER_REVIEW: [
    { action: 'APPROVE', label: 'Approve', tone: 'primary', explain: 'Documents and bank details check out. The vendor can then be taken live.' },
    { action: 'REQUEST_CHANGES', label: 'Request changes', note: true, explain: 'Sends the application back with your note so the vendor can fix it.' },
    { action: 'REJECT', label: 'Reject', note: true, tone: 'danger', explain: 'Closes the application. The vendor is told why and cannot go live.' },
  ],
  CHANGES_REQUESTED: [{ action: 'REVIEW', label: 'Back to review', explain: 'Use this once the vendor has sent the corrections.' }],
  APPROVED: [{ action: 'GO_LIVE', label: 'Go live', tone: 'primary', explain: 'Lists every open outlet in the customer app straight away.' }],
  LIVE: [{ action: 'SUSPEND', label: 'Suspend', note: true, tone: 'danger', explain: 'Hides the vendor from customers at once. Existing bookings stay; payouts continue unless you also hold them.' }],
  SUSPENDED: [{ action: 'REINSTATE', label: 'Reinstate', tone: 'primary', explain: 'Lists the vendor for customers again.' }],
}

export function Vendors() {
  const { api } = useSession()
  const labels = useLabels()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const { data, error, loading, reload } = useAsync(() => api.admin.providers(), [])
  const counts = useMemo(() => {
    const c: Record<string, number> = { '': data?.length ?? 0 }
    for (const p of data ?? []) c[p.status] = (c[p.status] ?? 0) + 1
    return c
  }, [data])
  const rows = useMemo(() => (status ? data?.filter((p) => p.status === status) : data), [data, status])
  const p = labels.provider.toLowerCase()

  return (
    <>
      <PageHeader title={`${labels.provider}s`} subtitle={`Onboard, verify and manage the ${p}s on the marketplace`} actions={<Link to="/vendors/new"><Button variant="primary" icon="plus">Add {p}</Button></Link>} />
      <Tabs value={status} onChange={(v) => setParams(v ? { status: v } : {})} tabs={STATUS_TABS.filter(([k]) => k === '' || counts[k] || k === status || ['SUBMITTED', 'LIVE'].includes(k)).map(([k, l]) => ({ value: k, label: l, count: counts[k] ?? 0 }))} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={rows} loading={loading} rowKey={(v) => v.id} onRowClick={(v) => navigate(`/vendors/${v.id}`)} exportName={`${p}s`}
          search={(v) => `${v.name} ${v.ownerName ?? ''} ${v.ownerPhone ?? ''} ${v.locations.map((l) => `${l.name} ${l.address}`).join(' ')}`}
          searchPlaceholder={`Search ${p}s, owners, phones or areas`}
          empty={status === 'SUBMITTED'
            ? <EmptyState icon="checkCircle" title="Nothing waiting for review">New applications show up here.</EmptyState>
            : <EmptyState icon="store" title={`No ${p}s here`} action={<Link to="/vendors/new"><Button icon="plus">Add {p}</Button></Link>}>Add one yourself, or wait for applications.</EmptyState>}
          columns={[
            { key: 'name', header: labels.provider, cell: (v) => <><strong>{v.name}</strong><div className="sub">{v.locations.length} {v.locations.length === 1 ? labels.location.toLowerCase() : `${labels.location.toLowerCase()}s`}{v.locations[0] ? ` · ${v.locations[0].address.split(',').slice(-2, -1)[0]?.trim() ?? ''}` : ''}</div></>, sort: (v) => v.name, csv: (v) => v.name },
            { key: 'owner', header: 'Owner', cell: (v) => <>{v.ownerName}<div className="sub">{phone(v.ownerPhone)}</div></>, sort: (v) => v.ownerName ?? '', csv: (v) => `${v.ownerName ?? ''} ${v.ownerPhone ?? ''}` },
            { key: 'rating', header: 'Rating', cell: (v) => { const r = rating(v); return r ? <span className="row tight"><span className="stars">★</span>{r.avg.toFixed(1)} <span className="faint small">({r.count})</span></span> : <span className="faint">New</span> }, sort: (v) => rating(v)?.avg ?? 0, align: 'right' },
            { key: 'commission', header: 'Commission', cell: (v) => (v.commissionBps === undefined ? <span className="muted">Default</span> : bpsToPercent(v.commissionBps)), sort: (v) => v.commissionBps ?? -1, csv: (v) => (v.commissionBps === undefined ? 'default' : v.commissionBps / 100), align: 'right' },
            { key: 'joined', header: 'Joined', cell: (v) => <span className="nowrap" title={dateOnly(v.createdAt)}>{relative(v.createdAt)}</span>, sort: (v) => v.createdAt, csv: (v) => v.createdAt },
            { key: 'status', header: 'Status', cell: (v) => <span className="row tight"><StatusBadge status={v.status} />{v.payoutHold && <Badge tone="bad">Payouts held</Badge>}</span>, sort: (v) => v.status, csv: (v) => v.status },
          ]} />
      </Card>
    </>
  )
}

const DOC_NAMES: Record<string, string> = { GST: 'GST certificate', PAN: 'PAN card', BANK: 'Bank proof', TRADE_LICENCE: 'Trade licence', TRADE_LICENSE: 'Trade licence', AADHAAR: 'Aadhaar', FSSAI: 'FSSAI licence' }
const docName = (t: string) => DOC_NAMES[t.toUpperCase()] ?? titleCase(t)

function rating(p: Provider) {
  const n = p.locations.reduce((s, l) => s + l.ratingCount, 0)
  return n ? { avg: p.locations.reduce((s, l) => s + l.ratingAvg * l.ratingCount, 0) / n, count: n } : null
}

export function VendorDetail() {
  const { id = '' } = useParams()
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const confirm = useConfirm()
  const adminConfig = useAsync(() => api.admin.config(), [])
  const { data: p, error, loading, setData, reload } = useAsync(() => api.admin.provider(id), [id])
  const [commission, setCommission] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const defaultBps = adminConfig.data?.money.defaultCommissionBps
  const vendor = labels.provider.toLowerCase()

  if (loading && !p) return <><Skeleton width={260} height={30} /><div className="spacer" /><Card><div className="stack">{[1, 2, 3, 4].map((i) => <Skeleton key={i} />)}</div></Card></>
  if (error || !p) return <Card><EmptyState icon="store" title={`We could not load this ${vendor}`} action={<div className="row"><Button onClick={reload} icon="refresh">Retry</Button><Link to="/vendors"><Button variant="ghost">Back to the list</Button></Link></div>}>{error ?? 'It may have been removed.'}</EmptyState></Card>

  async function step(s: (typeof STEPS)[string][number]) {
    const r = await confirm({
      title: `${s.label}: ${p!.name}?`, body: s.explain, tone: s.tone === 'danger' ? 'danger' : 'primary', confirmLabel: s.label,
      reason: s.note ? { label: `Note for the ${vendor}`, required: true, hint: `The ${vendor} sees this note.` } : undefined,
    })
    if (!r) return
    setBusy(s.action)
    try { setData(await api.admin.providerAction(p!.id, s.action, r.reason || undefined)); toast(`${s.label}: done`) } catch (e) { toast(messageOf(e), true) } finally { setBusy(null) }
  }
  async function reviewDoc(type: string, state: 'VERIFIED' | 'NEEDS_FIX') {
    let note: string | undefined
    if (state === 'NEEDS_FIX') {
      const r = await confirm({ title: `${docName(type)} needs a fix`, confirmLabel: 'Send to vendor', reason: { label: `What should the ${vendor} correct?`, required: true } })
      if (!r) return
      note = r.reason
    }
    try { setData(await api.admin.reviewDoc(p!.id, type, state, note)); toast(state === 'VERIFIED' ? `${docName(type)} verified` : 'Sent back for a fix') } catch (e) { toast(messageOf(e), true) }
  }
  async function toggleHold() {
    const holding = !p!.payoutHold
    const r = await confirm({
      title: holding ? `Hold payouts to ${p!.name}?` : `Release the payout hold on ${p!.name}?`, tone: holding ? 'danger' : 'primary', confirmLabel: holding ? 'Hold payouts' : 'Release hold',
      body: holding ? 'Their earnings keep building up but are left out of payout runs until you release the hold. Bookings are not affected.' : 'Their pending earnings go out with the next payout run.',
    })
    if (!r) return
    try { setData(await api.admin.setHold(p!.id, holding)); toast(holding ? 'Payouts held' : 'Payout hold released') } catch (e) { toast(messageOf(e), true) }
  }
  async function saveCommission() {
    const text = (commission ?? '').trim()
    const pct = text === '' ? null : Number(text)
    if (pct !== null && (Number.isNaN(pct) || pct < 0 || pct > 50)) { toast('Commission must be between 0% and 50%', true); return }
    try { setData(await api.admin.setCommission(p!.id, pct === null ? null : Math.round(pct * 100))); toast('Commission saved. It applies to new bookings.'); setCommission(null) } catch (e) { toast(messageOf(e), true) }
  }

  const docsDone = p.documents.length > 0 && p.documents.every((d) => d.state === 'VERIFIED')
  const checklist: [string, boolean][] = [
    ['Business and owner details', !!p.ownerName],
    [`At least one ${labels.location.toLowerCase()}`, p.locations.length > 0],
    ['Documents verified', docsDone],
    ['Bank account for payouts', !!p.bank],
  ]
  const r = rating(p)

  return (
    <>
      <PageHeader title={p.name} back={{ to: '/vendors', label: `${labels.provider}s` }}
        meta={<><StatusBadge status={p.status} />{p.payoutHold && <Badge tone="bad">Payouts held</Badge>}</>}
        subtitle={`Joined ${dateOnly(p.createdAt)}${r ? ` · ★ ${r.avg.toFixed(1)} from ${r.count} reviews` : ''}`}
        actions={(STEPS[p.status] ?? []).map((s) => <Button key={s.action} variant={s.tone} busy={busy === s.action} onClick={() => void step(s)}>{s.label}</Button>)} />
      {p.reviewNote && <><Notice tone="warn">Last note to the {vendor}: {p.reviewNote}</Notice><div className="spacer" /></>}
      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1.4fr) minmax(280px, 1fr)' }}>
        <div className="stack">
          <Card title="Verification" actions={<span className="muted small">{p.documents.filter((d) => d.state === 'VERIFIED').length} of {p.documents.length} verified</span>} flush>
            {!p.documents.length ? <EmptyState icon="file" title="No documents yet">The {vendor} adds these from their app.</EmptyState> : (
              <div className="scroll-x"><table className="table"><tbody>
                {p.documents.map((d) => (
                  <tr key={d.type + d.reference}>
                    <td><strong>{docName(d.type)}</strong><div className="sub mono">{d.reference}</div></td>
                    <td><Badge tone={d.state === 'VERIFIED' ? 'good' : d.state === 'NEEDS_FIX' ? 'bad' : 'warn'} dot>{d.state === 'VERIFIED' ? 'Verified' : d.state === 'NEEDS_FIX' ? 'Needs a fix' : 'Not checked'}</Badge>{d.note && <div className="sub">{d.note}</div>}</td>
                    <td className="right nowrap">
                      {d.state !== 'VERIFIED' && <Button small icon="check" onClick={() => void reviewDoc(d.type, 'VERIFIED')}>Verify</Button>}{' '}
                      {d.state !== 'NEEDS_FIX' && <Button small variant="ghost" onClick={() => void reviewDoc(d.type, 'NEEDS_FIX')}>Needs fix</Button>}
                    </td>
                  </tr>
                ))}
              </tbody></table></div>
            )}
          </Card>
          <Card title={`${labels.location}s`} flush>
            <div className="scroll-x"><table className="table">
              <thead><tr><th>Name</th><th>Hours</th><th>Capacity</th><th>Status</th><th className="right">Rating</th></tr></thead>
              <tbody>{p.locations.map((l) => (
                <tr key={l.id}>
                  <td><strong>{l.name}</strong><div className="sub">{l.address}</div></td>
                  <td className="nowrap">{l.openTime} – {l.closeTime}</td>
                  <td className="nowrap">{l.capacity} bays · {l.slotMinutes} min slots</td>
                  <td><Badge tone={l.open ? 'good' : 'neutral'} dot>{l.open ? 'Taking bookings' : 'Paused'}</Badge>{l.autoAccept && <div className="sub">Auto-accepts</div>}</td>
                  <td className="right">{l.ratingCount ? `★ ${l.ratingAvg.toFixed(1)} (${l.ratingCount})` : <span className="faint">New</span>}</td>
                </tr>
              ))}</tbody>
            </table></div>
          </Card>
        </div>
        <div className="stack">
          <Card title="Onboarding">
            <ul className="stack tight" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {checklist.map(([t, ok]) => <li key={t} className="row tight"><span style={{ color: ok ? 'var(--c-good)' : 'var(--c-faint)' }}><Icon name={ok ? 'checkCircle' : 'clock'} /></span><span className={ok ? '' : 'muted'}>{t}</span></li>)}
            </ul>
          </Card>
          <Card title="Owner">
            <DescriptionList items={[['Name', p.ownerName], ['Phone', p.ownerPhone ? <a href={`tel:${p.ownerPhone}`}>{phone(p.ownerPhone)}</a> : null], ['Staff accounts', p.staffCount ?? '-']]} />
          </Card>
          <Card title="Commission">
            <form className="stack tight" onSubmit={(e) => { e.preventDefault(); void saveCommission() }}>
              <div className="row end">
                <div className="grow"><Input label="Percent of the service amount" type="number" step="0.5" min="0" max="50" inputMode="decimal"
                  placeholder={defaultBps === undefined ? '' : `Default ${bpsToPercent(defaultBps)}`}
                  value={commission ?? (p.commissionBps === undefined ? '' : String(p.commissionBps / 100))} onChange={(e) => setCommission(e.target.value)} /></div>
                <Button variant="primary" disabled={commission === null}>Save</Button>
              </div>
              <p className="muted small">Leave empty to use the marketplace default{defaultBps !== undefined && ` (${bpsToPercent(defaultBps)})`}. Changes apply to new bookings only. <Link to="/rules">Time-limited rules</Link> take priority.</p>
            </form>
          </Card>
          <Card title="Payouts">
            <div className="stack tight">
              <DescriptionList items={[
                ['Bank account', p.bank ? <span className="mono">{p.bank.accountNumberMasked}</span> : <span className="muted">Not added yet</span>],
                ...(p.bank ? [['Holder', p.bank.accountName], ['IFSC', <span className="mono">{p.bank.ifsc}</span>]] as [string, React.ReactNode][] : []),
                ['Payouts', <Badge tone={p.payoutHold ? 'bad' : 'good'} dot>{p.payoutHold ? 'On hold' : 'Normal'}</Badge>],
              ]} />
              <div><Button small variant={p.payoutHold ? undefined : 'danger'} icon={p.payoutHold ? 'play' : 'pause'} onClick={() => void toggleHold()}>{p.payoutHold ? 'Release hold' : 'Hold payouts'}</Button></div>
            </div>
          </Card>
        </div>
      </div>
    </>
  )
}

export function NewVendor() {
  const { api } = useSession()
  const labels = useLabels()
  const nav = useNavigate()
  const toast = useToast()
  const [f, setF] = useState({ name: '', ownerName: '', ownerPhone: '', locName: '', address: '', lat: '12.9716', lng: '77.5946', open: '08:00', close: '20:00', capacity: '2', slot: '30' })
  const [error, setError] = useState<string | null>(null)
  const [tried, setTried] = useState(false)
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const errs = {
    name: !f.name.trim() ? 'Enter the business name' : null,
    ownerName: !f.ownerName.trim() ? 'Enter the owner\'s name' : null,
    ownerPhone: !isValidIndianMobile(f.ownerPhone) ? 'Enter a 10-digit mobile number' : null,
    address: !f.address.trim() ? 'Enter the address customers will see' : null,
    hours: f.open >= f.close ? 'Closing time must be after opening time' : null,
    capacity: !(parseInt(f.capacity) >= 1) ? 'At least 1' : null,
  }
  const show = (k: keyof typeof errs) => (tried ? errs[k] : null)

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null); setTried(true)
    if (Object.values(errs).some(Boolean)) return
    setBusy(true)
    try {
      const p = await api.admin.createProvider({
        name: f.name.trim(), ownerName: f.ownerName.trim(), ownerPhone: toE164(f.ownerPhone),
        location: { name: f.locName.trim() || f.name.trim(), address: f.address.trim(), lat: parseFloat(f.lat), lng: parseFloat(f.lng), openTime: f.open, closeTime: f.close, capacity: parseInt(f.capacity), slotMinutes: parseInt(f.slot) },
      })
      toast(`${p.name} created and sent to review`); nav(`/vendors/${p.id}`)
    } catch (err) { setError(messageOf(err)) } finally { setBusy(false) }
  }

  return (
    <>
      <PageHeader title={`Add a ${labels.provider.toLowerCase()}`} back={{ to: '/vendors', label: `${labels.provider}s` }} subtitle="Creates the owner's sign-in and the first outlet. They add their menu, documents and bank details from the vendor app." />
      <form onSubmit={submit} className="stack" style={{ maxWidth: 720 }} noValidate>
        <Card title="Business">
          <div className="grid cols-2">
            <Input label="Business name" required value={f.name} onChange={set('name')} error={show('name')} autoFocus />
            <Input label="Owner name" required value={f.ownerName} onChange={set('ownerName')} error={show('ownerName')} />
            <label className={`field ${show('ownerPhone') ? 'invalid' : ''}`}><span>Owner mobile<span className="req">*</span></span><PhoneInput value={f.ownerPhone} onChange={(v) => setF({ ...f, ownerPhone: v })} /><span className="hint">They sign in with this number</span>{show('ownerPhone') && <span className="err">{show('ownerPhone')}</span>}</label>
          </div>
        </Card>
        <Card title={`First ${labels.location.toLowerCase()}`}>
          <div className="grid cols-2">
            <Input label="Name" value={f.locName} onChange={set('locName')} placeholder={f.name || 'Same as the business'} hint="Leave empty to use the business name" />
            <Input label="Address" required value={f.address} onChange={set('address')} error={show('address')} placeholder="Street, area, city" />
            <Input label="Latitude" type="number" step="any" value={f.lat} onChange={set('lat')} hint="Used for distance search" />
            <Input label="Longitude" type="number" step="any" value={f.lng} onChange={set('lng')} />
            <Input label="Opens" type="time" value={f.open} onChange={set('open')} />
            <Input label="Closes" type="time" value={f.close} onChange={set('close')} error={show('hours')} />
            <Input label="Bays (cars at a time)" type="number" min="1" value={f.capacity} onChange={set('capacity')} error={show('capacity')} />
            <Input label="Slot length (minutes)" type="number" min="10" step="5" value={f.slot} onChange={set('slot')} />
          </div>
        </Card>
        <ErrorNote message={error} />
        {tried && Object.values(errs).some(Boolean) && <Notice tone="bad">Some details need attention. They are marked above.</Notice>}
        <div className="row"><Button variant="primary" busy={busy} icon="check">Create and send to review</Button><Link to="/vendors"><Button type="button" variant="ghost">Cancel</Button></Link></div>
      </form>
    </>
  )
}
