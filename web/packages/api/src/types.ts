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
export interface Document { type: string; reference: string; state?: 'PENDING' | 'VERIFIED' | 'NEEDS_FIX'; note?: string }
export interface Bank { accountName: string; accountNumberMasked: string; ifsc: string }
export type ProviderStatus = 'SUBMITTED' | 'UNDER_REVIEW' | 'CHANGES_REQUESTED' | 'APPROVED' | 'LIVE' | 'SUSPENDED' | 'REJECTED'
export interface Provider {
  id: string; name: string; status: ProviderStatus; commissionBps?: number; reviewNote?: string
  payoutHold?: boolean; staffCount?: number
  documents: Document[]; bank?: Bank; ownerName?: string; ownerPhone?: string; locations: Location[]; createdAt: string
}
export interface Block { id: string; startsAt: string; endsAt: string; reason: string }
export interface StaffMember { id: string; name: string; phone: string; role: Role }
export interface Payout { id: string; providerId: string; providerName?: string; amountMinor: number; orderCount: number; status: string; createdAt: string }
export interface DayPoint { date: string; gmvMinor: number }
export interface AlertItem { kind: string; title: string; meta: string }
export interface VendorRow { providerId: string; name: string; bookings: number; gmvMinor: number; utilisationPct: number; ratingAvg: number; cancelPct: number; noShowPct: number }
export interface Dashboard {
  range: string; gmvMinor: number; gmvDeltaPct?: number | null; bookings: number; bookingsPerDay: number; takeRateBps: number
  utilisationPct: number; cancellationPct: number; noShowPct: number; vendorsLive: number; vendorsInReview: number
  series: DayPoint[]; alerts: AlertItem[]; topVendors: VendorRow[]; customers: number
}
export interface Overview {
  range: string; netMinor: number; netDeltaPct?: number | null; bookings: number; bookingsDelta: number; utilisationPct: number
  ratingAvg: number; ratingCount: number; newReviews: number; days: string[]; hours: number[]; heatmap: number[][]; insight: string
}
export interface BatchRow {
  providerId: string; name: string; bookings: number; serviceMinor: number; commissionMinor: number; gatewayFeeMinor: number
  netMinor: number; account?: string | null; status: string; payoutId?: string | null; failureReason?: string | null
}
export interface Batch { from: string; to: string; vendors: number; netMinor: number; commissionMinor: number; rows: BatchRow[] }
export interface Billing {
  month: string; orders: number; includedOrders: number; overageOrders: number
  baseSubscriptionMinor: number; perOrderChargeMinor: number; totalMinor: number
}
export interface Earnings {
  from: string; to: string; orders: number; serviceMinor: number; commissionMinor: number; commissionBps: number
  gatewayFeeMinor: number; netMinor: number; pendingMinor: number; paidOutMinor: number; nextPayoutDate: string; bankMasked?: string | null
}
export interface AuditEntry { id: string; actorRole: string; actorId?: string; action: string; entity: string; entityId?: string; detail: string; at: string }
export interface Customer { id: string; name: string; phone: string; bookings: number; spendMinor: number; lastBooking?: string | null; active: boolean }

export interface Review { id: string; customerName: string; rating: number; comment: string; createdAt: string; tags: string[]; reply?: string | null; replyAt?: string | null; locationId?: string | null }
export interface Closure { id: string; startsAt: string; endsAt: string; reason: string; bays?: number[] | null; repeat: string }
export interface PaymentRow { id: string; orderId: string; amountMinor: number; refundedMinor: number; status: string; method: string; createdAt: string }
export interface LedgerRow { id: string; orderId?: string | null; type: string; account: string; amountMinor: number; createdAt: string }
export interface Rule { id: string; scope: 'OUTLET' | 'VENDOR' | 'CATEGORY'; target: string; targetLabel: string; rateBps: number; effectiveFrom: string; effectiveUntil?: string | null; setBy: string }
export interface RulePreview { serviceMinor: number; commissionMinor: number; gatewayFeeMinor: number; payoutMinor: number }
export interface Outlier { itemId: string; providerId: string; locationName: string; vendor: string; item: string; variant: string; priceMinor: number; medianMinor: number; reason: string }
export interface Ticket {
  id: string; orderId: string; orderCode?: string | null; locationName?: string | null; customerName?: string | null; category: string; description: string
  photos: string[]; status: string; resolutionNote?: string | null; refundMinor: number; orderTotalMinor?: number | null; createdAt: string
}
export interface Promo { code: string; kind: 'FLAT' | 'PERCENT'; value: number; maxDiscountMinor?: number | null; minSubtotalMinor: number; expiresAt?: string | null; usageLimit?: number | null; perUserLimit: number; active: boolean; description: string }
export interface BulkEdit { name: string; prices?: Record<string, number>; active?: boolean; outOfStock?: boolean }
