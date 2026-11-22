import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { bpsToPercent, dateOnly, messageOf, titleCase, useAsync, useLabels, useSession } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Select, Spinner, Table, statusTone, useToast } from '@odyssey/ui'

const STATUSES = ['SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'LIVE', 'SUSPENDED', 'REJECTED']

/** Which verification steps are available from each status, and whether they need a note. */
const STEPS: Record<string, { action: string; label: string; note?: boolean; tone?: 'primary' | 'danger' }[]> = {
  SUBMITTED: [{ action: 'REVIEW', label: 'Start review', tone: 'primary' }, { action: 'REJECT', label: 'Reject', note: true, tone: 'danger' }],
  UNDER_REVIEW: [{ action: 'APPROVE', label: 'Approve', tone: 'primary' }, { action: 'REQUEST_CHANGES', label: 'Request changes', note: true }, { action: 'REJECT', label: 'Reject', note: true, tone: 'danger' }],
  CHANGES_REQUESTED: [{ action: 'REVIEW', label: 'Back to review' }],
  APPROVED: [{ action: 'GO_LIVE', label: 'Go live', tone: 'primary' }],
  LIVE: [{ action: 'SUSPEND', label: 'Suspend', note: true, tone: 'danger' }],
  SUSPENDED: [{ action: 'REINSTATE', label: 'Reinstate', tone: 'primary' }],
}

export function Vendors() {
  const { api } = useSession()
  const labels = useLabels()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const { data, error, loading } = useAsync(() => api.admin.providers(status || undefined), [status])
  return (
    <>
      <PageHeader title={`${labels.provider}s`} subtitle={`Onboard and manage ${labels.provider.toLowerCase()}s`} actions={
        <>
          <Select label="Status" value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">All</option>{STATUSES.map((s) => <option key={s} value={s}>{titleCase(s)}</option>)}
          </Select>
          <Link to="/vendors/new"><Button variant="primary">Add {labels.provider.toLowerCase()}</Button></Link>
        </>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No {labels.provider.toLowerCase()}s here yet.</Empty> : (
          <Table head={[labels.provider, 'Owner', `${labels.location}s`, 'Commission', 'Joined', 'Status']}>
            {data.map((p) => (
              <tr key={p.id} className="click" onClick={() => navigate(`/vendors/${p.id}`)}>
                <td><Link to={`/vendors/${p.id}`} onClick={(e) => e.stopPropagation()}><strong>{p.name}</strong></Link></td>
                <td>{p.ownerName}<div className="muted small">{p.ownerPhone}</div></td>
                <td>{p.locations.map((l) => l.name).join(', ')}</td>
                <td>{p.commissionBps === undefined ? <span className="muted">default</span> : bpsToPercent(p.commissionBps)}</td>
                <td className="nowrap">{dateOnly(p.createdAt)}</td>
                <td><Badge tone={statusTone(p.status)}>{titleCase(p.status)}</Badge></td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}

export function VendorDetail() {
  const { id = '' } = useParams()
  const { api } = useSession()
  const labels = useLabels()
  const toast = useToast()
  const adminConfig = useAsync(() => api.admin.config(), [])
  const { data: p, error, loading, setData } = useAsync(() => api.admin.provider(id), [id])
  const [step, setStep] = useState<{ action: string; label: string; note?: boolean } | null>(null)
  const [note, setNote] = useState('')
  const [commission, setCommission] = useState<string | null>(null)
  const [docFix, setDocFix] = useState<string | null>(null)
  const defaultBps = adminConfig.data?.money.defaultCommissionBps

  if (loading) return <Spinner />
  if (error || !p) return <ErrorNote message={error ?? 'Not found'} />

  async function run(action: string, n?: string) {
    try { setData(await api.admin.providerAction(p!.id, action, n)); toast('Updated'); setStep(null); setNote('') }
    catch (e) { toast(messageOf(e), true) }
  }
  async function reviewDoc(type: string, state: string, n?: string) {
    try { setData(await api.admin.reviewDoc(p!.id, type, state, n)); setDocFix(null); setNote('') } catch (e) { toast(messageOf(e), true) }
  }
  async function toggleHold() {
    try { setData(await api.admin.setHold(p!.id, !p!.payoutHold)); toast(p!.payoutHold ? 'Payout hold released' : 'Payouts held') } catch (e) { toast(messageOf(e), true) }
  }
  async function saveCommission() {
    const text = commission ?? ''
    try {
      const bps = text.trim() === '' ? null : Math.round(parseFloat(text) * 100)
      setData(await api.admin.setCommission(p!.id, bps)); toast('Commission saved'); setCommission(null)
    } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title={p.name} subtitle={`${labels.provider} · joined ${dateOnly(p.createdAt)}`} actions={<>
        <Badge tone={statusTone(p.status)}>{titleCase(p.status)}</Badge>
        {(STEPS[p.status] ?? []).map((s) => (
          <Button key={s.action} variant={s.tone} onClick={() => (s.note ? setStep(s) : void run(s.action))}>{s.label}</Button>
        ))}
      </>} />
      {p.reviewNote && <div className="alert" style={{ marginBottom: 16 }}>Last note: {p.reviewNote}</div>}
      <div className="grid cols-2">
        <Card>
          <h2>Owner</h2>
          <p><strong>{p.ownerName}</strong></p><p className="muted">{p.ownerPhone}</p>
          <h2 style={{ marginTop: 18 }}>Commission</h2>
          <div className="row">
            <Input label="Percent of service amount" type="number" step="0.5" min="0" max="50" placeholder={defaultBps === undefined ? '' : `Default ${bpsToPercent(defaultBps)}`}
              value={commission ?? (p.commissionBps === undefined ? '' : String(p.commissionBps / 100))} onChange={(e) => setCommission(e.target.value)} hint="Leave empty to use the default" />
            <Button variant="primary" onClick={saveCommission} disabled={commission === null}>Save</Button>
          </div>
          <p className="muted small">Applies to new bookings only. Existing bookings keep their rate.</p>
          <h2 style={{ marginTop: 18 }}>Payouts</h2>
          <div className="row"><Badge tone={p.payoutHold ? 'bad' : 'good'}>{p.payoutHold ? 'On hold' : 'Normal'}</Badge><Button small onClick={toggleHold}>{p.payoutHold ? 'Release hold' : 'Hold payouts'}</Button></div>
        </Card>
        <Card>
          <h2>Verification</h2>
          <Table head={['Document', 'Reference', 'State', '']}>
            {p.documents.map((d) => (
              <tr key={d.type + d.reference}>
                <td>{titleCase(d.type)}</td><td>{d.reference}</td>
                <td><Badge tone={d.state === 'VERIFIED' ? 'good' : d.state === 'NEEDS_FIX' ? 'bad' : 'warn'}>{titleCase(d.state ?? 'PENDING')}</Badge>{d.note && <div className="muted small">{d.note}</div>}</td>
                <td className="nowrap"><Button small onClick={() => void reviewDoc(d.type, 'VERIFIED')}>Verify</Button> <Button small onClick={() => setDocFix(d.type)}>Needs fix</Button></td>
              </tr>
            ))}
            {!p.documents.length && <tr><td colSpan={4} className="muted">No documents yet</td></tr>}
          </Table>
          <h3 style={{ marginTop: 16 }}>Bank account</h3>
          <p className="muted">{p.bank ? `${p.bank.accountName} · ${p.bank.accountNumberMasked} · ${p.bank.ifsc}` : 'Not added yet'}</p>
        </Card>
      </div>
      <div style={{ height: 16 }} />
      <Card flush>
        <div style={{ padding: '16px 18px 4px' }}><h2>{labels.location}s</h2></div>
        <Table head={['Name', 'Address', 'Hours', 'Capacity', 'Rating']}>
          {p.locations.map((l) => (
            <tr key={l.id}><td><strong>{l.name}</strong></td><td>{l.address}</td><td className="nowrap">{l.openTime} – {l.closeTime}</td><td>{l.capacity} at a time, {l.slotMinutes} min slots</td><td>{l.ratingCount ? `${l.ratingAvg.toFixed(1)} (${l.ratingCount})` : '-'}</td></tr>
          ))}
        </Table>
      </Card>
      {docFix && (
        <Modal title={`${titleCase(docFix)} needs a fix`} onClose={() => setDocFix(null)}>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); void reviewDoc(docFix, 'NEEDS_FIX', note) }}>
            <label className="field">What should the vendor correct?<textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
            <div className="row spread"><Button type="button" onClick={() => setDocFix(null)}>Cancel</Button><Button variant="primary" disabled={!note.trim()}>Send</Button></div>
          </form>
        </Modal>
      )}
      {step && (
        <Modal title={step.label} onClose={() => setStep(null)}>
          <form className="stack" onSubmit={(e) => { e.preventDefault(); void run(step.action, note) }}>
            <label className="field">Note for the vendor (required)<textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
            <div className="row spread"><Button type="button" onClick={() => setStep(null)}>Cancel</Button><Button variant="primary" disabled={!note.trim()}>{step.label}</Button></div>
          </form>
        </Modal>
      )}
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
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError(null)
    try {
      const p = await api.admin.createProvider({
        name: f.name, ownerName: f.ownerName, ownerPhone: f.ownerPhone,
        location: { name: f.locName || f.name, address: f.address, lat: parseFloat(f.lat), lng: parseFloat(f.lng), openTime: f.open, closeTime: f.close, capacity: parseInt(f.capacity), slotMinutes: parseInt(f.slot) },
      })
      toast(`${labels.provider} created and sent to review`); nav(`/vendors/${p.id}`)
    } catch (err) { setError(messageOf(err)) }
  }

  return (
    <>
      <PageHeader title={`Add ${labels.provider.toLowerCase()}`} subtitle="Creates the owner's login and the first outlet. They can add menu and slots once approved." />
      <form onSubmit={submit} className="stack" style={{ maxWidth: 640 }}>
        <Card><h2>Business</h2><div className="grid cols-2" style={{ marginTop: 12 }}>
          <Input label="Business name" required value={f.name} onChange={set('name')} />
          <Input label="Owner name" required value={f.ownerName} onChange={set('ownerName')} />
          <Input label="Owner phone" required type="tel" placeholder="+91…" value={f.ownerPhone} onChange={set('ownerPhone')} hint="They sign in with this number" />
        </div></Card>
        <Card><h2>First {labels.location.toLowerCase()}</h2><div className="grid cols-2" style={{ marginTop: 12 }}>
          <Input label="Name" value={f.locName} onChange={set('locName')} placeholder={f.name} />
          <Input label="Address" required value={f.address} onChange={set('address')} />
          <Input label="Latitude" required type="number" step="any" value={f.lat} onChange={set('lat')} />
          <Input label="Longitude" required type="number" step="any" value={f.lng} onChange={set('lng')} />
          <Input label="Opens" type="time" value={f.open} onChange={set('open')} />
          <Input label="Closes" type="time" value={f.close} onChange={set('close')} />
          <Input label="Bays / capacity" type="number" min="1" value={f.capacity} onChange={set('capacity')} />
          <Input label="Slot length (minutes)" type="number" min="10" step="5" value={f.slot} onChange={set('slot')} />
        </div></Card>
        <ErrorNote message={error} />
        <div className="row"><Button variant="primary">Create</Button><Link to="/vendors"><Button type="button">Cancel</Button></Link></div>
      </form>
    </>
  )
}
