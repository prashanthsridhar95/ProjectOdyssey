// Types mirror the REST contract in api/openapi.yaml. Money is always in minor units (paise).

export type Role = 'CUSTOMER' | 'PROVIDER_OWNER' | 'PROVIDER_MANAGER' | 'PROVIDER_STAFF' | 'ADMIN' | 'SUPPORT' | 'FINANCE'

export interface User { id: string; name: string; phone: string; role: Role; providerId?: string }
export interface AuthResponse { accessToken: string; expiresAt: string; user: User }
export interface ApiErrorBody { code: string; message: string }

export interface Labels { provider: string; location: string; order: string; item: string; customer: string }
export interface VariantOption { key: string; label: string }
export interface StateDef { name: string; label: string; occupiesCapacity: boolean; terminal: boolean }
export interface TenantConfig {
  labels: Labels
  variants: { name: string; options: VariantOption[] }
  flow: { initial: string; paid: string; accepted: string; completed: string; states: StateDef[] }
  availability: { leadMinutes: number; windowDays: number; holdMinutes: number }
  money: { currency: string; taxBps: number; customerFeeMinor: number; defaultCommissionBps: number }
  cancellation: { fullRefundHoursBefore: number; lateRetainBps: number }
  billing: { baseSubscriptionMinor: number; includedOrders: number; perOrderBps: number }
  serviceTemplates: { name: string; category: string; durationMinutes: number; description: string }[]
  amenities: string[]
  [k: string]: unknown
}
export interface PublicConfig { tenant: { slug: string; name: string; vertical: string }; config: TenantConfig }

export interface OrderLine { itemId: string; name: string; priceMinor: number; durationMinutes: number }
export interface Order {
  id: string; code: string; status: string; statusLabel: string; source: 'APP' | 'WALK_IN'
  customerName: string; customerPhone: string; providerId: string; locationId: string; locationName: string
  assetLabel?: string; variantKey: string; startsAt: string; endsAt: string; lines: OrderLine[]
  subtotalMinor: number; taxMinor: number; feeMinor: number; totalMinor: number
  commissionBps?: number; commissionMinor?: number; cancellationReason?: string; createdAt: string
  actions: string[]; reviewed: boolean
}

export interface Slot { startsAt: string; endsAt: string; free: number; bookable: boolean }
export interface Item { id: string; name: string; description: string; category: string; durationMinutes: number; active: boolean; prices: Record<string, number> }
export interface Location {
  id: string; name: string; address: string; lat: number; lng: number; openTime: string; closeTime: string
  slotMinutes: number; capacity: number; autoAccept: boolean; open: boolean; amenities: string[]; ratingAvg: number; ratingCount: number
}
export interface LocationDraft { name: string; address: string; lat: number; lng: number; openTime?: string; closeTime?: string; slotMinutes?: number; capacity?: number; amenities?: string[] }
export interface Document { type: string; reference: string }
export interface Bank { accountName: string; accountNumberMasked: string; ifsc: string }
export type ProviderStatus = 'SUBMITTED' | 'UNDER_REVIEW' | 'CHANGES_REQUESTED' | 'APPROVED' | 'LIVE' | 'SUSPENDED' | 'REJECTED'
export interface Provider {
  id: string; name: string; status: ProviderStatus; commissionBps?: number; reviewNote?: string
  documents: Document[]; bank?: Bank; ownerName?: string; ownerPhone?: string; locations: Location[]; createdAt: string
}
export interface Block { id: string; startsAt: string; endsAt: string; reason: string }
export interface StaffMember { id: string; name: string; phone: string; role: Role }
export interface Payout { id: string; providerId: string; providerName?: string; amountMinor: number; orderCount: number; status: string; createdAt: string }
export interface Dashboard {
  ordersTotal: number; ordersToday: number; gmvMinor: number; commissionMinor: number
  cancelled: number; completed: number; providersByStatus: Record<string, number>; customers: number
}
export interface Billing {
  month: string; orders: number; includedOrders: number; overageOrders: number
  baseSubscriptionMinor: number; perOrderChargeMinor: number; totalMinor: number
}
export interface Earnings { orders: number; grossMinor: number; commissionMinor: number; earnedMinor: number; paidOutMinor: number; pendingMinor: number }
export interface AuditEntry { id: string; actorRole: string; actorId?: string; action: string; entity: string; entityId?: string; detail: string; at: string }
export interface Customer { id: string; name: string; phone: string; active: boolean }
