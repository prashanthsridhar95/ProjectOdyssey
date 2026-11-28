// Pricing promotions: happy hours and date discounts (no code needed), vouchers, and refer-a-friend.
// Money is minor units (paise); PERCENT values are basis points (2000 = 20%).

import type { Promo } from './types'

export type PromotionType = 'HAPPY_HOUR' | 'DATE_DISCOUNT'
export type PromotionKind = 'PERCENT' | 'FLAT' | 'PRICE'
export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
export const WEEKDAYS: Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
export const SHORT_DAY: Record<Weekday, string> = { MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu', FRIDAY: 'Fri', SATURDAY: 'Sat', SUNDAY: 'Sun' }

/** What a vendor or admin fills in. Empty lists mean "all". Times are HH:mm, dates yyyy-MM-dd, both in the outlet's local time. */
export interface PromotionInput {
  type: PromotionType
  name: string
  kind: PromotionKind
  value: number
  maxDiscountMinor?: number | null
  locationIds: string[]
  services: string[]
  variantKeys: string[]
  days: Weekday[]
  startTime?: string | null
  endTime?: string | null
  startDate?: string | null
  endDate?: string | null
  active: boolean
}

export type PromotionStatus = 'LIVE' | 'SCHEDULED' | 'PAUSED' | 'ENDED'

export interface Promotion extends PromotionInput {
  id: string
  providerId?: string | null
  providerName?: string | null
  summary: string
  badge: string
  status: PromotionStatus
  fundedBy: 'VENDOR' | 'PLATFORM'
  /** False for platform offers shown to a vendor, and for staff. */
  editable: boolean
  bookings: number
  discountGivenMinor: number
  createdAt: string
  updatedAt: string
}

export interface DiscountLine { kind: 'PROMOTION' | 'VOUCHER' | 'REFERRAL'; label: string; amountMinor: number; funder?: 'VENDOR' | 'PLATFORM' | null }

export interface Voucher extends Promo {
  startsAt?: string | null
  providerId?: string | null
  providerName?: string | null
  fundedBy: 'VENDOR' | 'PLATFORM'
  source?: string
  uses?: number
  discountGivenMinor?: number
}

export interface RewardTerms { kind: 'FLAT' | 'PERCENT'; value: number; maxDiscountMinor?: number | null; minSubtotalMinor: number; validDays: number }
export interface ReferralSettings { enabled: boolean; welcome: RewardTerms; reward: RewardTerms }
export interface ReferralRow { id: string; referrerName: string; refereeName: string; code: string; status: 'PENDING' | 'REWARDED'; createdAt: string; rewardedAt?: string | null }
export interface ReferralOverview {
  settings: ReferralSettings; referrals: number; rewarded: number; pending: number
  welcomeVouchersUsed: number; rewardVouchersUsed: number; discountGivenMinor: number; recent: ReferralRow[]
}
export interface DiscountSummary {
  bookings: number; totalMinor: number; vendorFundedMinor: number; platformFundedMinor: number
  promotionsMinor: number; vouchersMinor: number; referralsMinor: number
}

type Http = {
  get: <T>(path: string, req?: { pin?: string }) => Promise<T>
  post: <T>(path: string, body?: unknown, req?: { pin?: string }) => Promise<T>
  put: <T>(path: string, body: unknown, req?: { pin?: string }) => Promise<T>
  del: <T>(path: string, req?: { pin?: string }) => Promise<T>
}

/** The promotions part of the API client. Vendor changes to prices need the Owner PIN; pausing does not. */
export function promotionCalls({ get, post, put, del }: Http) {
  return {
    vendor: {
      list: () => get<Promotion[]>('/provider/promotions'),
      save: (p: PromotionInput, pin: string, id?: string) => (id ? put<Promotion>(`/provider/promotions/${id}`, p, { pin }) : post<Promotion>('/provider/promotions', p, { pin })),
      setActive: (id: string, active: boolean) => put<Promotion>(`/provider/promotions/${id}/active`, { active }),
      remove: (id: string, pin: string) => del<void>(`/provider/promotions/${id}`, { pin }),
    },
    admin: {
      list: () => get<Promotion[]>('/admin/promotions'),
      save: (p: PromotionInput, id?: string) => (id ? put<Promotion>(`/admin/promotions/${id}`, p) : post<Promotion>('/admin/promotions', p)),
      setActive: (id: string, active: boolean) => put<Promotion>(`/admin/promotions/${id}/active`, { active }),
      remove: (id: string) => del<void>(`/admin/promotions/${id}`),
      vouchers: () => get<Voucher[]>('/admin/promos'),
      saveVoucher: (v: Voucher) => post<Voucher>('/admin/promos', v),
      setVoucherActive: (code: string, active: boolean) => put<void>(`/admin/promos/${encodeURIComponent(code)}/active`, { active }),
      referrals: () => get<ReferralOverview>('/admin/referrals'),
      saveReferralSettings: (s: ReferralSettings) => put<ReferralSettings>('/admin/referrals/settings', s),
      summary: () => get<DiscountSummary>('/admin/discounts/summary'),
    },
  }
}

// ---- words: the same rules as the server's PromotionEngine, so the form can preview the sentence as you type ----

function rupees(minor: number, currency = 'INR'): string {
  const symbol = currency === 'INR' ? '₹' : `${currency} `
  const whole = Math.trunc(minor / 100)
  return symbol + (minor % 100 === 0 ? whole.toLocaleString('en-US') : (minor / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
}

function percentText(bps: number): string {
  return bps % 100 === 0 ? `${bps / 100}%` : `${(bps / 100).toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}%`
}

export function amountText(kind: PromotionKind, value: number, currency = 'INR'): string {
  if (kind === 'PERCENT') return `${percentText(value)} off`
  if (kind === 'FLAT') return `${rupees(value, currency)} off`
  return `${rupees(value, currency)} special price`
}

export function listText(items: string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export function daysText(days: Weekday[]): string {
  const set = WEEKDAYS.filter((d) => days.includes(d))
  if (set.length === 0 || set.length === 7) return 'every day'
  if (set.join() === WEEKDAYS.slice(0, 5).join()) return 'every weekday'
  if (set.join() === 'SATURDAY,SUNDAY') return 'every weekend'
  const idx = set.map((d) => WEEKDAYS.indexOf(d))
  if (set.length >= 3 && idx[idx.length - 1]! - idx[0]! === set.length - 1) return `every ${SHORT_DAY[set[0]!]}–${SHORT_DAY[set[set.length - 1]!]}`
  return `every ${listText(set.map((d) => SHORT_DAY[d]))}`
}

function clock(hhmm: string, suffix: boolean): string {
  const h = Number(hhmm.slice(0, 2)), m = Number(hhmm.slice(3, 5))
  return `${h % 12 || 12}${m ? `:${String(m).padStart(2, '0')}` : ''}${suffix ? (h < 12 ? ' am' : ' pm') : ''}`
}

/** "2–4 pm", "11 am–1 pm". */
export function timeText(start: string, end: string): string {
  const sameHalf = (Number(start.slice(0, 2)) < 12) === (Number(end.slice(0, 2)) < 12 || end === '00:00')
  return sameHalf ? `${clock(start, false)}–${clock(end, true)}` : `${clock(start, true)}–${clock(end, true)}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const dayMonth = (iso: string) => `${Number(iso.slice(8, 10))} ${MONTHS[Number(iso.slice(5, 7)) - 1]}`

export function datesText(start?: string | null, end?: string | null): string {
  if (start && end && start === end) return `on ${dayMonth(start)}`
  if (start && end) return `from ${dayMonth(start)} to ${dayMonth(end)}`
  if (start) return `from ${dayMonth(start)}`
  if (end) return `until ${dayMonth(end)}`
  return ''
}

/** "20% off every weekday 2–4 pm on all services". [variantLabel] turns a vehicle type key into its name. */
export function promotionSentence(p: Pick<PromotionInput, 'type' | 'kind' | 'value' | 'maxDiscountMinor' | 'days' | 'startTime' | 'endTime' | 'startDate' | 'endDate' | 'services' | 'variantKeys'>, variantLabel: (key: string) => string = (k) => k, currency = 'INR'): string {
  const parts = [amountText(p.kind, p.value || 0, currency)]
  if (p.days.length > 0 || p.type === 'HAPPY_HOUR') parts.push(daysText(p.days))
  if (p.startTime && p.endTime) parts.push(timeText(p.startTime, p.endTime))
  const dates = datesText(p.startDate, p.type === 'DATE_DISCOUNT' ? (p.endDate || p.startDate) : p.endDate)
  if (dates) parts.push(dates)
  parts.push(p.services.length ? `on ${listText(p.services)}` : 'on all services')
  if (p.variantKeys.length) parts.push(`for ${listText(p.variantKeys.map(variantLabel))}`)
  let text = parts.join(' ')
  if (p.maxDiscountMinor && p.kind === 'PERCENT') text += ` (up to ${rupees(p.maxDiscountMinor, currency)})`
  return text
}

/** Plain checks before the form submits. Returns the first problem, or null. */
export function promotionProblem(p: PromotionInput): string | null {
  if (!p.name.trim()) return 'Give the offer a name'
  if (p.kind === 'PERCENT' && (p.value < 100 || p.value > 10_000)) return 'The percentage must be between 1% and 100%'
  if (p.kind !== 'PERCENT' && p.value <= 0) return p.kind === 'FLAT' ? 'Enter the amount off' : 'Enter the special price'
  if (p.type === 'HAPPY_HOUR') {
    if (!p.startTime || !p.endTime) return 'Choose the start and end time'
    if (p.endTime <= p.startTime) return 'The end time must be after the start time'
    if (p.days.length === 0) return 'Pick at least one day'
  }
  if (p.type === 'DATE_DISCOUNT' && !p.startDate) return 'Choose the date'
  if (p.startDate && p.endDate && p.endDate < p.startDate) return 'The end date must be on or after the start date'
  return null
}

export const STATUS_LABEL: Record<PromotionStatus, string> = { LIVE: 'Live', SCHEDULED: 'Scheduled', PAUSED: 'Paused', ENDED: 'Ended' }
