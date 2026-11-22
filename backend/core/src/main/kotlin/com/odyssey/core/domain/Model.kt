package com.odyssey.core.domain

import com.odyssey.core.config.TenantConfig
import java.time.Instant
import java.time.LocalTime

typealias Id = String

enum class Role { CUSTOMER, PROVIDER_OWNER, PROVIDER_MANAGER, PROVIDER_STAFF, ADMIN, SUPPORT, FINANCE, SYSTEM;
    val isProvider get() = this == PROVIDER_OWNER || this == PROVIDER_MANAGER || this == PROVIDER_STAFF
    val isBackoffice get() = this == ADMIN || this == SUPPORT || this == FINANCE
}

data class Tenant(val id: Id, val slug: String, val name: String, val vertical: String, val config: TenantConfig)

data class User(
    val id: Id,
    val tenantId: Id,
    val phone: String,
    val name: String,
    val role: Role,
    val providerId: Id? = null,
    val pinHash: String? = null,
    val pinFailures: Int = 0,
    val lockedUntil: Instant? = null,
    val active: Boolean = true,
)

enum class ProviderStatus { SUBMITTED, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, LIVE, SUSPENDED, REJECTED }

data class ProviderDocument(val type: String, val reference: String)
data class BankDetails(val accountName: String, val accountNumberMasked: String, val ifsc: String)

data class Provider(
    val id: Id,
    val tenantId: Id,
    val name: String,
    val ownerUserId: Id,
    val status: ProviderStatus,
    val commissionBps: Int? = null,
    val documents: List<ProviderDocument> = emptyList(),
    val bank: BankDetails? = null,
    val reviewNote: String? = null,
    val createdAt: Instant,
)

data class Location(
    val id: Id,
    val tenantId: Id,
    val providerId: Id,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val timezone: String = "Asia/Kolkata",
    val openTime: LocalTime = LocalTime.of(8, 0),
    val closeTime: LocalTime = LocalTime.of(20, 0),
    val slotMinutes: Int = 30,
    val capacity: Int = 2,
    val autoAccept: Boolean = true,
    val open: Boolean = true,
    val amenities: List<String> = emptyList(),
    val photoUrl: String? = null,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val phone: String? = null,
)

data class CatalogItem(
    val id: Id,
    val tenantId: Id,
    val locationId: Id,
    val name: String,
    val description: String = "",
    val category: String = "",
    val durationMinutes: Int,
    val active: Boolean = true,
    /** Price per variant key, in minor units. */
    val prices: Map<String, Long>,
)

data class Block(val id: Id, val tenantId: Id, val locationId: Id, val startsAt: Instant, val endsAt: Instant, val reason: String, val createdBy: Id)

enum class OrderSource { APP, WALK_IN }

data class OrderLine(val itemId: Id, val name: String, val priceMinor: Long, val durationMinutes: Int)

data class Order(
    val id: Id,
    val tenantId: Id,
    val code: String,
    val customerId: Id?,
    val customerName: String,
    val customerPhone: String,
    val providerId: Id,
    val locationId: Id,
    val locationName: String,
    val assetLabel: String?,
    val variantKey: String,
    val status: String,
    val source: OrderSource,
    val startsAt: Instant,
    val endsAt: Instant,
    val lines: List<OrderLine>,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val feeMinor: Long,
    val totalMinor: Long,
    val commissionBps: Int,
    val commissionMinor: Long,
    val holdExpiresAt: Instant?,
    val cancellationReason: String? = null,
    val settled: Boolean = false,
    val payoutId: Id? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val discountMinor: Long = 0,
    val promoCode: String? = null,
    val reminded: Boolean = false,
    val reschedules: Int = 0,
    /** Not stored: true while a payment has been started but the bank has not confirmed yet. */
    val paymentPending: Boolean = false,
) {
    val providerNetMinor get() = subtotalMinor - commissionMinor
}

data class OrderEvent(val orderId: Id, val from: String?, val to: String, val action: String, val actorId: Id?, val note: String?, val at: Instant)

enum class PaymentStatus { PENDING, CAPTURED, FAILED, REFUNDED, PARTIALLY_REFUNDED }

data class Payment(val id: Id, val tenantId: Id, val orderId: Id, val amountMinor: Long, val refundedMinor: Long, val status: PaymentStatus, val method: String, val gatewayRef: String?, val createdAt: Instant)

data class LedgerEntry(val id: Id, val tenantId: Id, val orderId: Id?, val txnId: Id, val type: String, val account: String, val amountMinor: Long, val createdAt: Instant)

data class Payout(val id: Id, val tenantId: Id, val providerId: Id, val amountMinor: Long, val orderCount: Int, val status: String, val createdAt: Instant)

data class Review(val id: Id, val tenantId: Id, val orderId: Id, val locationId: Id, val customerName: String, val rating: Int, val comment: String, val createdAt: Instant, val tags: List<String> = emptyList())

data class Asset(val id: Id, val tenantId: Id, val ownerUserId: Id, val kind: String, val label: String, val attributes: Map<String, String>)

data class AuditEntry(val id: Id, val tenantId: Id, val actorId: Id?, val actorRole: String, val action: String, val entity: String, val entityId: String?, val detail: String, val at: Instant)

data class Slot(val startsAt: Instant, val endsAt: Instant, val free: Int, val bookable: Boolean)

enum class PromoKind { PERCENT, FLAT }

/** A discount the tenant funds. PERCENT value is in basis points; FLAT is minor units. */
data class PromoCode(
    val tenantId: Id, val code: String, val kind: PromoKind, val value: Long, val maxDiscountMinor: Long? = null, val minSubtotalMinor: Long = 0,
    val startsAt: Instant? = null, val expiresAt: Instant? = null, val usageLimit: Int? = null, val perUserLimit: Int = 1, val active: Boolean = true, val description: String = "",
)

data class SavedPlace(val id: Id, val userId: Id, val label: String, val address: String, val lat: Double, val lng: Double)

enum class TicketStatus { OPEN, UPHELD, REJECTED }

data class Ticket(
    val id: Id, val tenantId: Id, val orderId: Id, val userId: Id, val providerId: Id, val category: String, val description: String, val photos: List<String>,
    val status: TicketStatus, val resolutionNote: String?, val refundMinor: Long, val createdAt: Instant, val resolvedAt: Instant? = null,
)

data class Notification(val id: Id, val tenantId: Id, val userId: Id, val title: String, val body: String, val orderId: Id?, val read: Boolean, val createdAt: Instant)

class DomainException(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)

fun notFound(what: String) = DomainException("not_found", "$what not found", 404)
fun forbidden(msg: String = "Not allowed") = DomainException("forbidden", msg, 403)
fun conflict(code: String, msg: String) = DomainException(code, msg, 409)
fun invalid(msg: String) = DomainException("invalid", msg, 400)

/** Who is acting. Built by the HTTP layer from the verified token. */
data class Actor(val userId: Id?, val role: Role, val tenantId: Id, val providerId: Id? = null, val name: String = "") {
    companion object {
        fun system(tenantId: Id) = Actor(null, Role.SYSTEM, tenantId)
    }
}
