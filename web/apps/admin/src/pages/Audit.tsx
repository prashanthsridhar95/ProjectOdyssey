import { useMemo, useState } from 'react'
import { dateTime, recent, titleCase, useAsync, useSession } from '@odyssey/api'
import { Badge, Card, Chips, DataTable, EmptyState, ErrorNote, PageHeader } from '@odyssey/ui'

export function Audit() {
  const { api } = useSession()
  const { data, error, loading, reload } = useAsync(() => api.admin.audit(), [])
  const [who, setWho] = useState('all')
  const roles = useMemo(() => Array.from(new Set((data ?? []).map((a) => a.actorRole))).sort(), [data])
  const rows = useMemo(() => (who === 'all' ? data : data?.filter((a) => a.actorRole === who)), [data, who])
  return (
    <>
      <PageHeader title="Audit log" subtitle="Every change to prices, slots, commission, payouts and settings, with who made it. Entries cannot be edited or deleted." />
      <ErrorNote message={error} onRetry={reload} />
      <Card flush>
        <DataTable rows={rows} loading={loading} rowKey={(a) => a.id} exportName="audit-log" pageSize={50} initialSort={{ key: 'when', dir: 'desc' }}
          search={(a) => `${a.action} ${a.detail} ${a.entity} ${a.actorRole}`} searchPlaceholder="Search actions and details"
          toolbar={roles.length > 1 && <Chips label="Who" value={who} onChange={setWho} options={[{ value: 'all', label: 'Everyone' }, ...roles.map((r) => ({ value: r, label: titleCase(r.replace('PROVIDER_', 'vendor ')), count: data?.filter((a) => a.actorRole === r).length }))]} />}
          empty={<EmptyState icon="history" title="Nothing logged yet">Changes are recorded here as they happen.</EmptyState>}
          columns={[
            { key: 'when', header: 'When', cell: (a) => <><span className="nowrap">{dateTime(a.at)}</span>{recent(a.at) && <div className="sub">{recent(a.at)}</div>}</>, sort: (a) => a.at, csv: (a) => a.at },
            { key: 'who', header: 'Who', cell: (a) => <Badge>{titleCase(a.actorRole.replace('PROVIDER_', 'vendor '))}</Badge>, sort: (a) => a.actorRole, csv: (a) => `${a.actorRole} ${a.actorId ?? ''}` },
            { key: 'action', header: 'Action', cell: (a) => <strong>{titleCase(a.action)}</strong>, sort: (a) => a.action, csv: (a) => a.action },
            { key: 'entity', header: 'On', cell: (a) => <span className="muted">{titleCase(a.entity)}</span>, sort: (a) => a.entity, csv: (a) => `${a.entity} ${a.entityId ?? ''}` },
            { key: 'detail', header: 'Details', cell: (a) => <span className="muted">{a.detail}</span>, csv: (a) => a.detail },
          ]} />
      </Card>
    </>
  )
}
