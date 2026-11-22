package com.odyssey.core.ports

import com.odyssey.core.domain.*
import java.time.Instant

/** Everything the core needs from the outside world. Adapters live in the server module. */

interface Clock { fun now(): Instant }

object SystemClock : Clock { override fun now(): Instant = Instant.now() }

interface Ids {
    fun newId(): Id
    /** A short human-friendly order code, e.g. K7Q2XM. */
    fun newCode(): String
}

/** Runs a block atomically; when [lockLocationId] is set, concurrent bookings of that location are serialised. */
interface UnitOfWork { fun <T> run(lockLocationId: Id? = null, block: () -> T): T }

interface PinHasher {
    fun hash(pin: String): String
    fun verify(pin: String, hash: String): Boolean
}

data class ChargeRequest(val orderId: Id, val amountMinor: Long, val method: String, val idempotencyKey: String, val simulate: String?)
data class ChargeResult(val success: Boolean, val gatewayRef: String?, val failureReason: String? = null)
data class RefundResult(val success: Boolean, val gatewayRef: String?)

/** Payment gateway adapter (Razorpay Route, Cashfree Easy Split, ...). */
interface PaymentGateway {
    fun charge(req: ChargeRequest): ChargeResult
    fun refund(gatewayRef: String, amountMinor: Long): RefundResult
}

interface TenantRepo {
    fun bySlug(slug: String): Tenant?
    fun byId(id: Id): Tenant?
    fun all(): List<Tenant>
    fun insert(tenant: Tenant)
    fun update(tenant: Tenant)
}

interface UserRepo {
    fun byId(id: Id): User?
    fun byPhone(tenantId: Id, phone: String): User?
    fun insert(user: User)
    fun update(user: User)
    fun byProvider(providerId: Id): List<User>
    fun byRole(tenantId: Id, role: Role, limit: Int = 200): List<User>
}

interface ProviderRepo {
    fun byId(id: Id): Provider?
    fun byOwner(ownerUserId: Id): Provider?
    fun list(tenantId: Id, status: ProviderStatus? = null): List<Provider>
    fun insert(provider: Provider)
    fun update(provider: Provider)
}

interface LocationRepo {
    fun byId(id: Id): Location?
    fun byProvider(providerId: Id): List<Location>
    /** Locations of providers that are LIVE. */
    fun live(tenantId: Id): List<Location>
    fun insert(location: Location)
    fun update(location: Location)
}

interface CatalogRepo {
    fun byId(id: Id): CatalogItem?
    fun byIds(ids: List<Id>): List<CatalogItem>
    fun byLocation(locationId: Id, activeOnly: Boolean): List<CatalogItem>
    fun insert(item: CatalogItem)
    fun update(item: CatalogItem)
    fun delete(id: Id)
}

interface BlockRepo {
    fun overlapping(locationId: Id, from: Instant, to: Instant): List<Block>
    fun insert(block: Block)
    fun delete(id: Id, locationId: Id): Boolean
}

interface OrderRepo {
    fun byId(id: Id): Order?
    fun insert(order: Order)
    fun update(order: Order)
    fun addEvent(event: OrderEvent)
    fun events(orderId: Id): List<OrderEvent>
    /** Orders holding capacity at the location that overlap [from, to); expired holds are ignored. */
    fun occupying(locationId: Id, from: Instant, to: Instant, states: List<String>, now: Instant): List<Order>
    fun byCustomer(customerId: Id): List<Order>
    fun byProvider(providerId: Id, locationId: Id?, status: String?, from: Instant?, to: Instant?): List<Order>
    fun all(tenantId: Id, status: String?, from: Instant?, to: Instant?, limit: Int): List<Order>
    fun expiredHolds(now: Instant, state: String): List<Order>
    fun settledUnpaid(providerId: Id): List<Order>
    fun paidOrders(tenantId: Id, from: Instant, to: Instant, unpaidStates: List<String>): List<Order>
}

interface PaymentRepo {
    fun byOrder(orderId: Id): Payment?
    fun insert(payment: Payment)
    fun update(payment: Payment)
}

interface LedgerRepo {
    fun post(entries: List<LedgerEntry>)
    fun payableFor(orderId: Id): Long
    fun totals(tenantId: Id, from: Instant?, to: Instant?): Map<String, Long>
    fun forOrder(orderId: Id): List<LedgerEntry>
}

interface PayoutRepo {
    fun insert(payout: Payout)
    fun list(tenantId: Id, providerId: Id?): List<Payout>
}

interface ReviewRepo {
    fun insert(review: Review)
    fun byOrder(orderId: Id): Review?
    fun byLocation(locationId: Id, limit: Int): List<Review>
}

interface AssetRepo {
    fun byId(id: Id): Asset?
    fun byOwner(ownerUserId: Id): List<Asset>
    fun insert(asset: Asset)
    fun delete(id: Id, ownerUserId: Id): Boolean
}

interface AuditRepo {
    fun add(entry: AuditEntry)
    fun list(tenantId: Id, limit: Int): List<AuditEntry>
}

class Repos(
    val tenants: TenantRepo,
    val users: UserRepo,
    val providers: ProviderRepo,
    val locations: LocationRepo,
    val catalog: CatalogRepo,
    val blocks: BlockRepo,
    val orders: OrderRepo,
    val payments: PaymentRepo,
    val ledger: LedgerRepo,
    val payouts: PayoutRepo,
    val reviews: ReviewRepo,
    val assets: AssetRepo,
    val audit: AuditRepo,
)
