import { useState } from 'react'
import { messageOf, titleCase, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, DataTable, EmptyState, ErrorNote, Input, Modal, PageHeader, PhoneInput, Select, isValidIndianMobile, toE164, useConfirm, useToast } from '@odyssey/ui'
import { phone } from '@odyssey/api'
import { useWithPin } from '../pin'

export function StaffPage() {
  const { api } = useSession()
  const toast = useToast()
  const withPin = useWithPin()
  const confirm = useConfirm()
  const { data, error, loading, reload } = useAsync(() => api.provider.staff(), [])
  const [adding, setAdding] = useState(false)
  const [f, setF] = useState({ name: '', phone: '', role: 'PROVIDER_STAFF', pin: '' })
  const [formError, setFormError] = useState<string | null>(null)

  async function add(e: React.FormEvent) {
    e.preventDefault(); setFormError(null)
    if (!f.name.trim()) { setFormError('Enter their name'); return }
    if (!isValidIndianMobile(f.phone)) { setFormError('Enter a 10-digit mobile number'); return }
    if (/^(\d)\1{3}$|^1234$|^0000$/.test(f.pin)) { setFormError('Pick a PIN that is harder to guess than 1234 or 0000'); return }
    try {
      const r = await withPin((pin) => api.provider.addStaff({ ...f, name: f.name.trim(), phone: toE164(f.phone) }, pin), 'Owner PIN to add staff')
      if (r) { toast(`${f.name} added. They can sign in now.`); setAdding(false); setF({ name: '', phone: '', role: 'PROVIDER_STAFF', pin: '' }); reload() }
    } catch (err) { setFormError(messageOf(err)) }
  }
  async function remove(id: string, name: string) {
    if (!(await confirm({ title: `Remove ${name}?`, tone: 'danger', confirmLabel: 'Remove', body: 'They are signed out everywhere and can no longer sign in. Their past actions stay in the log.' }))) return
    try { const r = await withPin((pin) => api.provider.removeStaff(id, pin).then(() => true), 'Owner PIN to remove staff'); if (r) { toast(`${name} removed`); reload() } } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Staff" subtitle="Counter staff sign in with their phone and a 4-digit PIN, so every action is tied to a person" actions={<Button variant="primary" icon="plus" onClick={() => setAdding(true)}>Add staff</Button>} />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={data} loading={loading} rowKey={(x) => x.id}
          empty={<EmptyState icon="users" title="No staff yet" action={<Button icon="plus" onClick={() => setAdding(true)}>Add staff</Button>}>Give each person their own sign-in, so every action is tied to someone.</EmptyState>}
          columns={[
            { key: 'name', header: 'Name', cell: (x) => <strong>{x.name}</strong>, sort: (x) => x.name },
            { key: 'phone', header: 'Phone', cell: (x) => phone(x.phone) },
            { key: 'role', header: 'Role', cell: (x) => <Badge tone={x.role === 'PROVIDER_OWNER' ? 'info' : 'neutral'}>{titleCase(x.role.replace('PROVIDER_', ''))}</Badge>, sort: (x) => x.role },
            { key: 'act', header: '', align: 'right', cell: (x) => x.role !== 'PROVIDER_OWNER' && <Button small variant="ghost" icon="trash" onClick={() => remove(x.id, x.name)}>Remove</Button> },
          ]} />
      </Card>
      <p className="muted small" style={{ marginTop: 12 }}>Managers can manage slots, the menu and bookings. Staff can only check customers in and out. Neither sees earnings or changes prices.</p>
      {adding && (
        <Modal title="Add staff" description="They sign in on the counter device with their phone number and this PIN." onClose={() => setAdding(false)}
          footer={<><Button type="button" onClick={() => setAdding(false)}>Cancel</Button><Button variant="primary" type="submit" form="staff-form" disabled={f.pin.length !== 4}>Add</Button></>}>
          <form id="staff-form" className="stack" onSubmit={add}>
            <Input label="Name" required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
            <label className="field"><span>Mobile number<span className="req">*</span></span><PhoneInput value={f.phone} onChange={(v) => setF({ ...f, phone: v })} /></label>
            <Select label="Role" value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })} hint={f.role === 'PROVIDER_MANAGER' ? 'Can manage slots, the menu (not prices) and bookings.' : 'Can check customers in and out, and nothing else.'}><option value="PROVIDER_STAFF">Counter staff</option><option value="PROVIDER_MANAGER">Manager</option></Select>
            <Input label="Their 4-digit PIN" type="password" inputMode="numeric" maxLength={4} required value={f.pin} onChange={(e) => setF({ ...f, pin: e.target.value.replace(/\D/g, '') })} />
            <ErrorNote message={formError} />
          </form>
        </Modal>
      )}
    </>
  )
}
