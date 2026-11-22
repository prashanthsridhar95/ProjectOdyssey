import { useState } from 'react'
import { messageOf, titleCase, useAsync, useSession } from '@odyssey/api'
import { Button, Card, Empty, ErrorNote, Input, Modal, PageHeader, Select, Spinner, Table, useToast } from '@odyssey/ui'
import { useWithPin } from '../pin'

export function StaffPage() {
  const { api } = useSession()
  const toast = useToast()
  const withPin = useWithPin()
  const { data, error, loading, reload } = useAsync(() => api.provider.staff(), [])
  const [adding, setAdding] = useState(false)
  const [f, setF] = useState({ name: '', phone: '', role: 'PROVIDER_STAFF', pin: '' })
  const [formError, setFormError] = useState<string | null>(null)

  async function add(e: React.FormEvent) {
    e.preventDefault(); setFormError(null)
    try {
      const r = await withPin((pin) => api.provider.addStaff(f, pin), 'Owner PIN to add staff')
      if (r) { toast('Added'); setAdding(false); setF({ name: '', phone: '', role: 'PROVIDER_STAFF', pin: '' }); reload() }
    } catch (err) { setFormError(messageOf(err)) }
  }
  async function remove(id: string, name: string) {
    if (!window.confirm(`Remove ${name}?`)) return
    try { await withPin((pin) => api.provider.removeStaff(id, pin), 'Owner PIN to remove staff'); reload() } catch (e) { toast(messageOf(e), true) }
  }

  return (
    <>
      <PageHeader title="Staff" subtitle="Counter staff sign in with their phone and a 4-digit PIN, so every action is tied to a person" actions={<Button variant="primary" onClick={() => setAdding(true)}>Add staff</Button>} />
      <ErrorNote message={error} />
      <Card flush>
        {loading ? <Spinner /> : !data?.length ? <Empty>No staff yet.</Empty> : (
          <Table head={['Name', 'Phone', 'Role', '']}>
            {data.map((s) => (
              <tr key={s.id}><td><strong>{s.name}</strong></td><td>{s.phone}</td><td>{titleCase(s.role.replace('PROVIDER_', ''))}</td>
                <td className="right">{s.role !== 'PROVIDER_OWNER' && <Button small variant="danger" onClick={() => remove(s.id, s.name)}>Remove</Button>}</td></tr>
            ))}
          </Table>
        )}
      </Card>
      <p className="muted small" style={{ marginTop: 12 }}>Managers can manage slots, the menu and bookings. Staff can only check customers in and out. Neither sees earnings or changes prices.</p>
      {adding && (
        <Modal title="Add staff" onClose={() => setAdding(false)}>
          <form className="stack" onSubmit={add}>
            <Input label="Name" required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
            <Input label="Phone" required type="tel" placeholder="+91…" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} />
            <Select label="Role" value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}><option value="PROVIDER_STAFF">Staff</option><option value="PROVIDER_MANAGER">Manager</option></Select>
            <Input label="Their 4-digit PIN" type="password" inputMode="numeric" maxLength={4} required value={f.pin} onChange={(e) => setF({ ...f, pin: e.target.value.replace(/\D/g, '') })} />
            <ErrorNote message={formError} />
            <div className="row spread"><Button type="button" onClick={() => setAdding(false)}>Cancel</Button><Button variant="primary" disabled={f.pin.length !== 4}>Add</Button></div>
          </form>
        </Modal>
      )}
    </>
  )
}
