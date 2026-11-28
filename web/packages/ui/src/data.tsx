import { useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import { Button, EmptyState, SearchInput, SkeletonRows, downloadText, toCsv } from './components'
import { Icon, type IconName } from './icons'

export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  /** Makes the column sortable by this value. */
  sort?: (row: T) => string | number | null | undefined
  /** Value written to the CSV export; columns without it are left out. */
  csv?: (row: T) => string | number | null | undefined
  align?: 'right'
  width?: number | string
}

/**
 * The console's standard list: search, sortable columns, paging, CSV export of what is shown, row click.
 * Everything runs on the rows already loaded, so it suits lists of a few thousand.
 */
export function DataTable<T>({ rows, columns, rowKey, search, searchPlaceholder = 'Search', toolbar, onRowClick, selectedKey, pageSize = 25, loading, empty, exportName, initialSort }: {
  rows: T[] | undefined
  columns: Column<T>[]
  rowKey: (row: T) => string
  search?: (row: T) => string
  searchPlaceholder?: string
  toolbar?: ReactNode
  onRowClick?: (row: T) => void
  selectedKey?: string | null
  pageSize?: number
  loading?: boolean
  empty?: ReactNode
  exportName?: string
  initialSort?: { key: string; dir: 'asc' | 'desc' }
}) {
  const [q, setQ] = useState('')
  const [sort, setSort] = useState(initialSort ?? null)
  const [page, setPage] = useState(0)

  const filtered = useMemo(() => {
    let list = rows ?? []
    const needle = q.trim().toLowerCase()
    if (needle && search) list = list.filter((r) => search(r).toLowerCase().includes(needle))
    const col = sort && columns.find((c) => c.key === sort.key)
    if (col?.sort) {
      const get = col.sort
      list = [...list].sort((a, b) => {
        const x = get(a), y = get(b)
        if (x === y) return 0
        if (x === null || x === undefined) return 1
        if (y === null || y === undefined) return -1
        const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true })
        return sort!.dir === 'asc' ? c : -c
      })
    }
    return list
  }, [rows, q, search, sort, columns])

  const pages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const at = Math.min(page, pages - 1)
  const shown = filtered.slice(at * pageSize, at * pageSize + pageSize)

  function toggleSort(key: string) {
    setPage(0)
    setSort((s) => (s?.key !== key ? { key, dir: 'desc' } : s.dir === 'desc' ? { key, dir: 'asc' } : null))
  }
  function exportCsv() {
    const cols = columns.filter((c) => c.csv)
    const head = cols.map((c) => (typeof c.header === 'string' ? c.header : c.key))
    downloadText(`${exportName ?? 'export'}-${new Date().toISOString().slice(0, 10)}.csv`, toCsv([head, ...filtered.map((r) => cols.map((c) => c.csv!(r)))]))
  }

  const hasToolbar = search || toolbar || exportName
  return (
    <>
      {hasToolbar && (
        <div className="toolbar">
          {search && <SearchInput value={q} onChange={(v) => { setQ(v); setPage(0) }} placeholder={searchPlaceholder} />}
          {toolbar}
          <span className="grow" />
          {rows && <span className="muted small num">{filtered.length === (rows?.length ?? 0) ? `${filtered.length} total` : `${filtered.length} of ${rows.length}`}</span>}
          {exportName && <Button small icon="download" onClick={exportCsv} disabled={!filtered.length}>Export</Button>}
        </div>
      )}
      {loading && !rows ? <SkeletonRows cols={Math.min(columns.length, 6)} /> : !filtered.length ? (
        q ? <EmptyState icon="search" title={`No results for "${q}"`} action={<Button small onClick={() => setQ('')}>Clear search</Button>}>Try a name, a phone number or a code.</EmptyState> : (empty ?? <EmptyState title="Nothing here yet" />)
      ) : (
        <div className="scroll-x">
          <table className="table">
            <thead>
              <tr>{columns.map((c) => {
                const sorted = sort?.key === c.key
                return (
                  <th key={c.key} style={{ width: c.width }} className={[c.align, c.sort && 'sortable', sorted && 'sorted'].filter(Boolean).join(' ')}
                    onClick={c.sort ? () => toggleSort(c.key) : undefined} aria-sort={sorted ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                    tabIndex={c.sort ? 0 : undefined} onKeyDown={c.sort ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort(c.key) } } : undefined}>
                    {c.header}{c.sort && <Icon className="icon sorticon" name={sorted ? (sort!.dir === 'asc' ? 'arrowUp' : 'arrowDown') : 'sort'} size={12} />}
                  </th>
                )
              })}</tr>
            </thead>
            <tbody>
              {shown.map((r) => {
                const k = rowKey(r)
                return (
                  <tr key={k} className={[onRowClick && 'click', selectedKey === k && 'sel'].filter(Boolean).join(' ')} onClick={onRowClick ? () => onRowClick(r) : undefined}
                    tabIndex={onRowClick ? 0 : undefined} onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(r) } : undefined}>
                    {columns.map((c) => <td key={c.key} className={c.align}>{c.cell(r)}</td>)}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {pages > 1 && (
        <div className="tablefoot">
          <span className="num">Showing {at * pageSize + 1}–{Math.min(filtered.length, (at + 1) * pageSize)} of {filtered.length}</span>
          <div className="row tight">
            <Button small icon="chevronLeft" disabled={at === 0} onClick={() => setPage(at - 1)}>Previous</Button>
            <span className="num">Page {at + 1} of {pages}</span>
            <Button small disabled={at >= pages - 1} onClick={() => setPage(at + 1)}>Next<Icon name="chevronRight" size={14} /></Button>
          </div>
        </div>
      )}
    </>
  )
}

// ---- KPIs and charts ----

export function Delta({ pct, suffix = 'vs previous period' }: { pct?: number | null; suffix?: string }) {
  if (pct === null || pct === undefined) return null
  const dir = pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat'
  return <span className="row tight"><span className={`delta ${dir}`}>{dir !== 'flat' && <Icon name={dir === 'up' ? 'arrowUp' : 'arrowDown'} size={12} />}{Math.abs(pct)}%</span><span className="faint tiny">{suffix}</span></span>
}

export interface Kpi { label: string; value: ReactNode; foot?: ReactNode; icon?: IconName }
export function KpiStrip({ items }: { items: Kpi[] }) {
  return (
    <div className="kpis">
      {items.map((k) => <div key={k.label}><div className="label">{k.icon && <Icon name={k.icon} size={14} />}{k.label}</div><div className="value">{k.value}</div>{k.foot && <div className="foot">{k.foot}</div>}</div>)}
    </div>
  )
}

function niceMax(v: number): number {
  if (v <= 0) return 1
  const p = 10 ** Math.floor(Math.log10(v)); const n = v / p
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p
}

/** Columns with a value axis and a hover readout. [format] formats both the axis and the readout. */
export function BarChart({ data, format = String, height = 200, label }: { data: { key: string; label: string; value: number; detail?: string }[]; format?: (v: number) => string; height?: number; label: string }) {
  const [hover, setHover] = useState<number | null>(null)
  const W = 640, H = height, padL = 56, padB = 24, padT = 8
  const max = niceMax(Math.max(0, ...data.map((d) => d.value)))
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => t * max)
  const bw = (W - padL) / Math.max(1, data.length)
  const y = (v: number) => padT + (H - padT - padB) * (1 - v / max)
  const every = Math.ceil(data.length / 10)
  const total = data.reduce((s, d) => s + d.value, 0)
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${label}: total ${format(total)}`} onMouseLeave={() => setHover(null)}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={padL} x2={W} y1={y(t)} y2={y(t)} stroke="var(--c-border)" strokeDasharray={t === 0 ? undefined : '2 4'} />
            <text x={padL - 8} y={y(t)} dy="0.32em" textAnchor="end" fontSize="11" fill="var(--c-faint)">{format(t)}</text>
          </g>
        ))}
        {data.map((d, i) => {
          const x = padL + i * bw
          const h = Math.max(d.value > 0 ? 2 : 0, H - padB - y(d.value))
          return (
            <g key={d.key} onMouseEnter={() => setHover(i)}>
              <rect x={x} y={padT} width={bw} height={H - padT - padB} fill="transparent" />
              <rect className="bar" style={{ '--i': i } as CSSProperties} rx={Math.min(4, bw * 0.16)} x={x + bw * 0.18} y={H - padB - h} width={bw * 0.64} height={h} fill={hover === i ? 'var(--c-primary-dark)' : 'var(--c-primary)'} />
              {(i % every === 0) && <text x={x + bw / 2} y={H - 6} textAnchor="middle" fontSize="11" fill="var(--c-faint)">{d.label}</text>}
            </g>
          )
        })}
      </svg>
      {hover !== null && data[hover] && (
        <div className="tip" style={{ left: `${((padL + hover * bw + bw / 2) / W) * 100}%`, top: `${(y(data[hover]!.value) / H) * 100}%` }}>
          <strong>{format(data[hover]!.value)}</strong>{data[hover]!.detail ?? data[hover]!.label}
        </div>
      )}
    </div>
  )
}
