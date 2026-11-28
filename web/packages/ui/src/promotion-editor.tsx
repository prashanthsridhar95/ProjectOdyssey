import type { ReactNode } from 'react'
import { ErrorNote, Field, Notice, Select } from './components'

type Day = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
const DAYS: Day[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']

/** The shape the promotions API takes. PERCENT value is basis points; FLAT and PRICE are paise. */
export interface PromotionDraft {
  type: 'HAPPY_HOUR' | 'DATE_DISCOUNT'
  name: string
  kind: 'PERCENT' | 'FLAT' | 'PRICE'
  value: number
  maxDiscountMinor?: number | null
  locationIds: string[]
  services: string[]
  variantKeys: string[]
  days: Day[]
  startTime?: string | null
  endTime?: string | null
  startDate?: string | null
  endDate?: string | null
  active: boolean
}

const TIMES: string[] = []
for (let m = 6 * 60; m <= 23 * 60 + 30; m += 30) TIMES.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`)
const timeLabel = (t: string) => { const h = Number(t.slice(0, 2)), m = Number(t.slice(3)); return `${h % 12 || 12}${m ? `:${String(m).padStart(2, '0')}` : ''} ${h < 12 ? 'am' : 'pm'}` }

function toggle<T>(list: T[], v: T): T[] { return list.includes(v) ? list.filter((x) => x !== v) : [...list, v] }

/** "All" chip plus one chip per option. An empty selection means all. */
function ChipPicker({ label, all, options, value, onChange, hint }: { label: string; all: string; options: { key: string; label: string }[]; value: string[]; onChange: (v: string[]) => void; hint?: ReactNode }) {
  return (
    <div className="stack tight">
      <span className="small strong">{label}</span>
      <div className="chips">
        <button type="button" className={`chip ${value.length === 0 ? 'on' : ''}`} onClick={() => onChange([])}>{all}</button>
        {options.map((o) => <button type="button" key={o.key} className={`chip ${value.includes(o.key) ? 'on' : ''}`} aria-pressed={value.includes(o.key)} onClick={() => onChange(toggle(value, o.key))}>{o.label}</button>)}
      </div>
      {hint && <span className="muted small">{hint}</span>}
    </div>
  )
}

/**
 * The create and edit form for a happy hour or a date discount. It only edits [value]; the caller saves it.
 * [sentence] is the plain preview ("20% off every weekday 2–4 pm on all services") the caller works out from the draft.
 */
export function PromotionEditor({ value: d, onChange, outlets, services, variants, variantName = 'Vehicle type', today, sentence, error, outletWord = 'Outlet', fundingNote }: {
  value: PromotionDraft
  onChange: (d: PromotionDraft) => void
  outlets?: { id: string; name: string }[]
  services: string[]
  variants: { key: string; label: string }[]
  variantName?: string
  today: string
  sentence: string
  error?: string | null
  outletWord?: string
  fundingNote?: ReactNode
}) {
  const set = (patch: Partial<PromotionDraft>) => onChange({ ...d, ...patch })
  const major = d.kind === 'PERCENT' ? d.value / 100 : d.value / 100
  const days = d.days
  const quick: [string, Day[]][] = [['Weekdays', DAYS.slice(0, 5)], ['Weekends', ['SATURDAY', 'SUNDAY']], ['Every day', DAYS]]

  return (
    <div className="stack">
      <div className="tabs" role="tablist" style={{ alignSelf: 'flex-start' }}>
        <button type="button" role="tab" aria-selected={d.type === 'HAPPY_HOUR'} className={d.type === 'HAPPY_HOUR' ? 'on' : ''}
          onClick={() => set({ type: 'HAPPY_HOUR', startTime: d.startTime || '14:00', endTime: d.endTime || '16:00', days: d.days.length ? d.days : DAYS.slice(0, 4) })}>Happy hour</button>
        <button type="button" role="tab" aria-selected={d.type === 'DATE_DISCOUNT'} className={d.type === 'DATE_DISCOUNT' ? 'on' : ''}
          onClick={() => set({ type: 'DATE_DISCOUNT', startDate: d.startDate || today, endDate: d.endDate || d.startDate || today, days: [], startTime: null, endTime: null })}>Date discount</button>
      </div>
      <p className="muted small" style={{ marginTop: -6 }}>
        {d.type === 'HAPPY_HOUR' ? 'Repeats every week at the times you choose. Good for filling quiet afternoons.' : 'Runs on one day or a few days in a row, such as a festival or your launch day.'}
      </p>

      <label className="field"><span>Name</span>
        <input value={d.name} maxLength={80} onChange={(e) => set({ name: e.target.value })} placeholder={d.type === 'HAPPY_HOUR' ? 'Afternoon happy hour' : 'Diwali special'} />
        <span className="hint">Customers see this name on their booking.</span>
      </label>

      <div className="grid cols-2">
        <Select label="Discount" value={d.kind} onChange={(e) => set({ kind: e.target.value as PromotionDraft['kind'], value: e.target.value === 'PERCENT' ? 2000 : 5000, maxDiscountMinor: null })}>
          <option value="PERCENT">Percentage off</option>
          <option value="FLAT">Amount off</option>
          <option value="PRICE">Special price per service</option>
        </Select>
        <Field label={d.kind === 'PERCENT' ? 'Percentage' : d.kind === 'FLAT' ? 'Amount off (₹)' : 'Special price (₹)'} required>
          {(id) => (
            <div className={d.kind === 'PERCENT' ? 'suffix' : 'prefix'}>
              {d.kind !== 'PERCENT' && <span>₹</span>}
              <input id={id} type="number" inputMode="decimal" min={d.kind === 'PERCENT' ? 1 : 1} max={d.kind === 'PERCENT' ? 100 : undefined} step={d.kind === 'PERCENT' ? 1 : 10}
                value={Number.isFinite(major) && major > 0 ? major : ''} onChange={(e) => set({ value: Math.round(Number(e.target.value) * 100) })} />
              {d.kind === 'PERCENT' && <span>%</span>}
            </div>
          )}
        </Field>
      </div>
      {d.kind === 'PERCENT' && (
        <label className="field"><span>Most it can take off one booking (optional)</span>
          <input type="number" inputMode="numeric" min={1} placeholder="No limit" value={d.maxDiscountMinor ? d.maxDiscountMinor / 100 : ''}
            onChange={(e) => set({ maxDiscountMinor: e.target.value ? Math.round(Number(e.target.value) * 100) : null })} />
        </label>
      )}
      {d.kind === 'PRICE' && <span className="muted small" style={{ marginTop: -8 }}>Each chosen service costs this much at those times. Services already cheaper keep their price.</span>}

      {d.type === 'HAPPY_HOUR' ? (
        <>
          <div className="stack tight">
            <div className="row spread"><span className="small strong">Days</span>
              <span className="row tight">{quick.map(([l, v]) => <button key={l} type="button" className="link small" onClick={() => set({ days: v })}>{l}</button>)}</span></div>
            <div className="chips">{DAYS.map((day) => <button key={day} type="button" className={`chip ${days.includes(day) ? 'on' : ''}`} aria-pressed={days.includes(day)} onClick={() => set({ days: DAYS.filter((x) => x === day ? !days.includes(day) : days.includes(x)) })}>{day.slice(0, 1) + day.slice(1, 3).toLowerCase()}</button>)}</div>
          </div>
          <div className="grid cols-2">
            <Select label="From" value={d.startTime ?? ''} onChange={(e) => { const s = e.target.value; set({ startTime: s, endTime: d.endTime && d.endTime > s ? d.endTime : TIMES[Math.min(TIMES.indexOf(s) + 4, TIMES.length - 1)] }) }}>
              {TIMES.slice(0, -1).map((t) => <option key={t} value={t}>{timeLabel(t)}</option>)}
            </Select>
            <Select label="Until" value={d.endTime ?? ''} onChange={(e) => set({ endTime: e.target.value })}>
              {TIMES.filter((t) => !d.startTime || t > d.startTime).map((t) => <option key={t} value={t}>{timeLabel(t)}</option>)}
            </Select>
          </div>
          <span className="muted small" style={{ marginTop: -8 }}>Counts by when the slot starts. A 3:30 pm slot is in a 2–4 pm happy hour; a 4 pm slot is not.</span>
          <div className="grid cols-2">
            <label className="field"><span>Starts (optional)</span><input type="date" min={today} value={d.startDate ?? ''} onChange={(e) => set({ startDate: e.target.value || null })} /></label>
            <label className="field"><span>Ends (optional)</span><input type="date" min={d.startDate || today} value={d.endDate ?? ''} onChange={(e) => set({ endDate: e.target.value || null })} /></label>
          </div>
        </>
      ) : (
        <div className="grid cols-2">
          <label className="field"><span>First day</span><input type="date" min={today} value={d.startDate ?? ''} onChange={(e) => set({ startDate: e.target.value, endDate: !d.endDate || d.endDate < e.target.value ? e.target.value : d.endDate })} /></label>
          <label className="field"><span>Last day</span><input type="date" min={d.startDate || today} value={d.endDate ?? ''} onChange={(e) => set({ endDate: e.target.value })} /></label>
        </div>
      )}

      {outlets && outlets.length > 1 && <ChipPicker label={`${outletWord}s`} all={`All ${outletWord.toLowerCase()}s`} options={outlets.map((o) => ({ key: o.id, label: o.name }))} value={d.locationIds} onChange={(v) => set({ locationIds: v })} />}
      {services.length > 0 && <ChipPicker label="Services" all="All services" options={services.map((s) => ({ key: s, label: s }))} value={d.services} onChange={(v) => set({ services: v })} />}
      {variants.length > 1 && <ChipPicker label={`${variantName}s`} all={`All ${variantName.toLowerCase()}s`} options={variants} value={d.variantKeys} onChange={(v) => set({ variantKeys: v })} />}

      <label className="check"><input type="checkbox" checked={d.active} onChange={(e) => set({ active: e.target.checked })} />Live as soon as it is saved</label>
      {fundingNote && <Notice tone="info">{fundingNote}</Notice>}
      <div className="promo-preview" aria-live="polite"><span className="section-title" style={{ marginBottom: 4 }}>Customers will see</span><strong>{d.name.trim() || 'Your offer'}</strong><span>{sentence}</span></div>
      <ErrorNote message={error} />
    </div>
  )
}
