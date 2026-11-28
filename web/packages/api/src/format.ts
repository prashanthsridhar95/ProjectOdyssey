const ZONE = 'Asia/Kolkata'

/** 29900 -> "₹299" ; 29950 -> "₹299.50". Amounts are minor units (paise). */
export function money(minor: number | undefined | null, currency = 'INR'): string {
  if (minor === undefined || minor === null) return '-'
  const major = minor / 100
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, minimumFractionDigits: Number.isInteger(major) ? 0 : 2, maximumFractionDigits: 2 }).format(major)
}

export function bpsToPercent(bps: number | undefined | null): string {
  if (bps === undefined || bps === null) return '-'
  return `${(bps / 100).toFixed(bps % 100 === 0 ? 0 : 2)}%`
}

const valid = (iso: string | null | undefined) => !!iso && !Number.isNaN(new Date(iso).getTime())

export function timeOf(iso: string): string {
  if (!valid(iso)) return '-'
  return new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', timeZone: ZONE })
}

export function dateTime(iso: string): string {
  if (!valid(iso)) return '-'
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', timeZone: ZONE })
}

export function dateOnly(iso: string): string {
  if (!valid(iso)) return '-'
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: ZONE })
}

/** Today in India as YYYY-MM-DD, offset by [days]. */
export function isoDate(days = 0): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(d)
}

export function titleCase(s: string): string {
  return s.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
}

/** "10:30" on a given IST date -> ISO instant. */
export function istInstant(date: string, hhmm: string): string {
  return new Date(`${date}T${hhmm}:00+05:30`).toISOString()
}

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", then a date. Future times read "in 20 min". */
export function relative(iso: string, now = Date.now()): string {
  const diff = new Date(iso).getTime() - now
  const abs = Math.abs(diff), min = 60_000, hour = 60 * min, day = 24 * hour
  const fmt = (n: number, unit: string) => (diff < 0 ? `${n} ${unit} ago` : `in ${n} ${unit}`)
  if (abs < min) return diff < 0 ? 'just now' : 'in under a minute'
  if (abs < hour) return fmt(Math.round(abs / min), 'min')
  if (abs < day) return fmt(Math.round(abs / hour), 'h')
  if (abs < 2 * day) return diff < 0 ? 'yesterday' : 'tomorrow'
  if (abs < 7 * day) return fmt(Math.round(abs / day), 'days')
  return dateOnly(iso)
}

/** "+919845021734" -> "+91 98450 21734". */
export function phone(p: string | null | undefined): string {
  if (!p) return '-'
  const m = /^\+91(\d{5})(\d{5})$/.exec(p)
  return m ? `+91 ${m[1]} ${m[2]}` : p
}

/** "Mon 1 Dec" for a YYYY-MM-DD date, in India time. */
export function dayLabel(date: string): string {
  return new Date(`${date}T12:00:00+05:30`).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: ZONE })
}

/** YYYY-MM-DD shifted by [days]. */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00+05:30`)
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(new Date(d.getTime() + days * 86_400_000))
}

/** Compact money for chart axes: ₹1.2L, ₹45k. */
export function moneyShort(minor: number): string {
  const r = minor / 100
  if (r >= 1e7) return `₹${(r / 1e7).toFixed(1).replace(/\.0$/, '')}Cr`
  if (r >= 1e5) return `₹${(r / 1e5).toFixed(1).replace(/\.0$/, '')}L`
  if (r >= 1e3) return `₹${(r / 1e3).toFixed(1).replace(/\.0$/, '')}k`
  return `₹${Math.round(r)}`
}

/** The relative time only when it adds something (within a week); otherwise null, since the date is already shown. */
export function recent(iso: string, now = Date.now()): string | null {
  return Math.abs(new Date(iso).getTime() - now) < 7 * 86_400_000 ? relative(iso, now) : null
}
