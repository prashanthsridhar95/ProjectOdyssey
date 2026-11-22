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

export function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit', timeZone: ZONE })
}

export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', timeZone: ZONE })
}

export function dateOnly(iso: string): string {
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
