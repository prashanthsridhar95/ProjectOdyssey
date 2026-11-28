import type {
  ApiErrorBody, AuditEntry, BulkEdit, Closure, LedgerRow, Outlier, PaymentRow, Promo, Review, Rule, RulePreview, Ticket, AuthResponse, Batch, Billing, Block, Customer, Dashboard, Earnings, Item, Location, LocationDraft,
  Order, Overview, DayCalendar, Payout, Provider, PublicConfig, Slot, StaffMember, TenantConfig, User,
} from './types'
import { promotionCalls } from './promotions'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
  get isPinProblem() { return this.code === 'pin_invalid' || this.code === 'pin_locked' || this.code === 'pin_not_set' }
}

export interface ClientOptions {
  baseUrl: string
  tenant?: string
  getToken: () => string | null
  onUnauthorized?: () => void
}

/** What to tell a person when the server gave no message of its own. */
export function statusMessage(status: number): string {
  if (status === 401) return 'Your session has ended. Please sign in again.'
  if (status === 403) return 'You do not have access to this. Ask an administrator if you need it.'
  if (status === 404) return 'We could not find that. It may have been removed.'
  if (status === 409) return 'Someone else changed this at the same time. Reload and try again.'
  if (status === 413) return 'That file or request is too large.'
  if (status === 429) return 'Too many attempts. Wait a minute and try again.'
  if (status >= 500) return 'The server had a problem. Try again in a moment.'
  return `Something went wrong (${status}). Please try again.`
}

interface Req { pin?: string; query?: Record<string, string | number | boolean | undefined> }

/** A small typed wrapper over the REST API. Every request carries the tenant and bearer token. */
export function createApi(opts: ClientOptions) {
  async function call<T>(method: string, path: string, body?: unknown, req: Req = {}): Promise<T> {
    const url = new URL(opts.baseUrl.replace(/\/$/, '') + '/v1' + path, window.location.origin)
    Object.entries(req.query ?? {}).forEach(([k, v]) => v !== undefined && url.searchParams.set(k, String(v)))
    const headers: Record<string, string> = { 'X-Tenant': opts.tenant ?? 'carwash' }
    const token = opts.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    if (req.pin) headers['X-Owner-Pin'] = req.pin
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    let res: Response
    try {
      res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    } catch {
      throw new ApiError(0, 'network', 'Cannot reach the server. Check your connection.')
    }
    if (res.status === 204) return undefined as T
    const text = await res.text()
    let data: unknown
    try { data = text ? JSON.parse(text) : undefined } catch { data = undefined }
    if (!res.ok) {
      const err = data as ApiErrorBody | undefined
      if (res.status === 401 && token && err?.code === 'unauthorized') opts.onUnauthorized?.()
      throw new ApiError(res.status, err?.code ?? 'error', err?.message ?? statusMessage(res.status))
    }
    return data as T
  }

  // Identical GETs made at the same moment (two components wanting the same data) share one request.
  const inflight = new Map<string, Promise<unknown>>()
  const get = <T,>(path: string, req?: Req): Promise<T> => {
    if (req?.pin) return call<T>('GET', path, undefined, req)
    const k = path + JSON.stringify(req?.query ?? {})
    const existing = inflight.get(k)
    if (existing) return existing as Promise<T>
    const p = call<T>('GET', path, undefined, req).finally(() => inflight.delete(k))
    inflight.set(k, p)
    return p
  }
  const post = <T,>(path: string, body?: unknown, req?: Req) => call<T>('POST', path, body ?? {}, req)
  const put = <T,>(path: string, body: unknown, req?: Req) => call<T>('PUT', path, body, req)
  const del = <T,>(path: string, req?: Req) => call<T>('DELETE', path, undefined, req)

  return {
    promotions: promotionCalls({ get, post, put, del }),
    config: () => get<PublicConfig>('/config'),
    auth: {
      requestOtp: (phone: string) => post<void>('/auth/otp', { phone }),
      verifyOtp: (phone: string, code: string) => post<AuthResponse>('/auth/verify', { phone, code }),
      staffPin: (phone: string, pin: string) => post<AuthResponse>('/auth/pin', { phone, pin }),
      me: () => get<User>('/me'),
    },
    orders: {
      act: (id: string, action: string, note?: string) => post<Order>(`/orders/${id}/actions`, { action, note }),
    },
    provider: {
      me: () => get<Provider>('/provider/me'),
      /** Counts for the menu badges only: much lighter than loading the lists. */
      badges: () => get<{ bookingsWaiting: number; reviewsUnreplied: number }>('/provider/badges'),
      orders: (q: { date?: string; locationId?: string; status?: string }) => get<Order[]>('/provider/orders', { query: q }),
      slots: (locationId: string, date: string, itemIds?: string[]) =>
        get<Slot[]>(`/locations/${locationId}/availability`, { query: { date, itemIds: itemIds?.join(',') } }),
      calendar: (locationId: string, date: string) => get<DayCalendar>(`/provider/locations/${locationId}/calendar`, { query: { from: date, days: 1 } }),
      blocks: (locationId: string, date: string) => get<Block[]>(`/provider/locations/${locationId}/blocks`, { query: { date } }),
      addBlock: (locationId: string, startsAt: string, endsAt: string, reason: string) =>
        post<Block>(`/provider/locations/${locationId}/blocks`, { startsAt, endsAt, reason }),
      removeBlock: (locationId: string, blockId: string) => del<void>(`/provider/locations/${locationId}/blocks/${blockId}`),
      walkIn: (b: { locationId: string; itemIds: string[]; variantKey: string; startsAt: string; customerName: string; customerPhone?: string; plate?: string; bay?: number }) =>
        post<Order>('/provider/walk-ins', b),
      items: (locationId: string) => get<Item[]>(`/provider/locations/${locationId}/items`),
      saveItem: (locationId: string, item: Omit<Item, 'id'> & { id?: string }, pin?: string) =>
        item.id
          ? put<Item>(`/provider/locations/${locationId}/items/${item.id}`, item, { pin })
          : post<Item>(`/provider/locations/${locationId}/items`, item, { pin }),
      deleteItem: (locationId: string, itemId: string, pin: string) => del<void>(`/provider/locations/${locationId}/items/${itemId}`, { pin }),
      updateLocation: (id: string, patch: Partial<Location>) => put<Location>(`/provider/locations/${id}`, patch),
      staff: () => get<StaffMember[]>('/provider/staff'),
      addStaff: (s: { name: string; phone: string; role: string; pin: string }, pin: string) => post<StaffMember>('/provider/staff', s, { pin }),
      removeStaff: (id: string, pin: string) => del<void>(`/provider/staff/${id}`, { pin }),
      earnings: (pin: string, range?: string) => get<Earnings>('/provider/earnings', { pin, query: { range } }),
      reviews: () => get<Review[]>('/provider/reviews'),
      reply: (id: string, text: string) => post<Review>(`/provider/reviews/${id}/reply`, { text }),
      closures: (locationId: string) => get<Closure[]>(`/provider/locations/${locationId}/closures`),
      addClosure: (locationId: string, b: { startsAt: string; endsAt: string; reason: string; bays?: number[]; repeat: string }) => post<Closure>(`/provider/locations/${locationId}/blocks`, b),
      bulkMenu: (locationIds: string[], edits: BulkEdit[], pin: string) => put<{ changed: number }>('/provider/menu/bulk', { locationIds, edits }, { pin }),
      statement: async (pin: string, range: string) => {
        const res = await fetch(`${opts.baseUrl.replace(/\/$/, '')}/v1/provider/statement?range=${range}`, { headers: { 'X-Tenant': opts.tenant ?? 'carwash', Authorization: `Bearer ${opts.getToken()}`, 'X-Owner-Pin': pin } })
        if (!res.ok) throw new ApiError(res.status, res.status === 403 ? 'pin_invalid' : 'error', res.status === 403 ? 'That PIN is not right.' : 'Could not download the statement. Try again.')
        return res.text()
      },
      overview: (range = 'week', locationId?: string) => get<Overview>('/provider/overview', { query: { range, locationId } }),
      payouts: (pin: string) => get<Payout[]>('/provider/payouts', { pin }),
      setOwnerPin: (newPin: string, currentPin?: string) => put<void>('/provider/owner-pin', { newPin, currentPin }),
      verifyPin: (pin: string) => post<void>('/provider/verify-pin', {}, { pin }),
      saveBank: (b: { accountName: string; accountNumber: string; ifsc: string }, pin: string) => put<Provider>('/provider/bank', b, { pin }),
    },
    admin: {
      /** Counts for the menu badges only. */
      badges: () => get<{ vendorsToReview: number; ticketsOpen: number; menuOutliers: number }>('/admin/badges'),
      dashboard: (range = '7d') => get<Dashboard>('/admin/dashboard', { query: { range } }),
      batch: () => get<Batch>('/admin/payments/batch'),
      collections: () => get<PaymentRow[]>('/admin/payments/collections'),
      refunds: () => get<PaymentRow[]>('/admin/payments/refunds'),
      ledger: () => get<LedgerRow[]>('/admin/payments/ledger'),
      setHold: (id: string, hold: boolean) => put<Provider>(`/admin/providers/${id}/hold`, { hold }),
      reviewDoc: (id: string, type: string, state: string, note?: string) => post<Provider>(`/admin/providers/${id}/documents/${type}`, { state, note }),
      setCustomerActive: (id: string, active: boolean) => put<void>(`/admin/customers/${id}/active`, { active }),
      outliers: () => get<Outlier[]>('/admin/moderation/menu'),
      reviewOutlier: (itemId: string, hide: boolean) => post<void>(`/admin/moderation/menu/${itemId}`, { hide }),
      rules: () => get<Rule[]>('/admin/commission-rules'),
      addRule: (r: { scope: string; target: string; rateBps: number; effectiveFrom: string; effectiveUntil?: string }) => post<Rule>('/admin/commission-rules', r),
      deleteRule: (id: string) => del<void>(`/admin/commission-rules/${id}`),
      preview: (serviceMinor: number, rateBps: number) => get<RulePreview>('/admin/commission-preview', { query: { serviceMinor, rateBps } }),
      tickets: (status?: string) => get<Ticket[]>('/admin/tickets', { query: { status } }),
      resolveTicket: (id: string, b: { uphold: boolean; refundMinor: number; note: string }) => post<Ticket>(`/admin/tickets/${id}/resolve`, b),
      promos: () => get<Promo[]>('/admin/promos'),
      savePromo: (p: Promo) => post<Promo>('/admin/promos', p),
      setPromoActive: (code: string, active: boolean) => put<void>(`/admin/promos/${code}/active`, { active }),
      exportLedgerUrl: () => `${opts.baseUrl.replace(/\/$/, '')}/v1/admin/payments/export`,
      ledgerCsv: async () => {
        const res = await fetch(`${opts.baseUrl.replace(/\/$/, '')}/v1/admin/payments/export`, { headers: { 'X-Tenant': opts.tenant ?? 'carwash', Authorization: `Bearer ${opts.getToken()}` } })
        if (!res.ok) throw new ApiError(res.status, 'error', statusMessage(res.status))
        return res.text()
      },
      retryPayout: (id: string) => post<Payout>(`/admin/payouts/${id}/retry`),
      providers: (status?: string) => get<Provider[]>('/admin/providers', { query: { status } }),
      provider: (id: string) => get<Provider>(`/admin/providers/${id}`),
      createProvider: (b: { name: string; ownerName: string; ownerPhone: string; location: LocationDraft }) => post<Provider>('/admin/providers', b),
      providerAction: (id: string, action: string, note?: string) => post<Provider>(`/admin/providers/${id}/actions`, { action, note }),
      setCommission: (id: string, bps: number | null) => put<Provider>(`/admin/providers/${id}/commission`, { bps }),
      orders: (status?: string) => get<Order[]>('/admin/orders', { query: { status, limit: 200 } }),
      customers: (q?: string) => get<Customer[]>('/admin/customers', { query: { q } }),
      payouts: () => get<Payout[]>('/admin/payouts'),
      runPayouts: () => post<Payout[]>('/admin/payouts/run'),
      billing: (month?: string) => get<Billing>('/admin/billing', { query: { month } }),
      config: () => get<TenantConfig>('/admin/config'),
      saveConfig: (c: TenantConfig) => put<TenantConfig>('/admin/config', c),
      audit: () => get<AuditEntry[]>('/admin/audit'),
    },
  }
}

export type Api = ReturnType<typeof createApi>
