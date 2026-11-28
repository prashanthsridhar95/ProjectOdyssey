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

enum class DocState { PENDING, VERIFIED, NEEDS_FIX }

data class ProviderDocument(val type: String, val reference: String, val state: DocState = DocState.PENDING, val note: String? = null)
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
    val payoutHold: Boolean = false,
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
    /** How many bays the service occupies. Add-ons that run alongside another service use 0. */
    val baysUsed: Int = 1,
    val outOfStock: Boolean = false,
    /** Set once the back office has looked at an unusual price. */
    val priceReviewed: Boolean = false,
)

enum class Repeat { NONE, DAILY, WEEKLY }

/** Closed time. [bays] null closes every bay; otherwise just the listed bays. A repeating block recurs at the same local time. */
data class Block(val id: Id, val tenantId: Id, val locationId: Id, val startsAt: Instant, val endsAt: Instant, val reason: String, val createdBy: Id, val bays: List<Int>? = null, val repeat: Repeat = Repeat.NONE)

enum class OrderSource { APP, WALK_IN }

data class OrderLine(val itemId: Id, val name: String, val priceMinor: Long, val durationMinutes: Int, val baysUsed: Int = 1, val category: String = "")

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
    val bay: Int? = null,
    /** Not stored: true while a payment has been started but the bank has not confirmed yet. */
    val paymentPending: Boolean = false,
    /** Each discount on the booking and who pays for it. [discountMinor] is their total. */
    val discounts: List<DiscountLine> = emptyList(),
    /** The part of [discountMinor] the vendor funds. It lowers the service amount the vendor charges, so it also lowers tax and commission. */
    val vendorDiscountMinor: Long = 0,
    /** The automatic promotion (happy hour or date discount) priced into this booking, if any. */
    val promotionId: Id? = null,
) {
    /** What the vendor earns before the gateway fee: the service amount, less vendor-funded discounts and commission. */
    val providerNetMinor get() = subtotalMinor - vendorDiscountMinor - commissionMinor
    val platformDiscountMinor get() = discountMinor - vendorDiscountMinor
}

/** Who pays for a discount. A vendor-funded discount lowers the vendor's price; a platform-funded one comes out of commission. */
enum class Funder { VENDOR, PLATFORM }

/** One discount on a booking, kept with the booking so earnings stay correct if the offer later changes. [kind] is PROMOTION, VOUCHER or REFERRAL. */
data class DiscountLine(val kind: String, val label: String, val amountMinor: Long, val funder: Funder, val ref: String? = null)

data class OrderEvent(val orderId: Id, val from: String?, val to: String, val action: String, val actorId: Id?, val note: String?, val at: Instant)

enum class PaymentStatus { PENDING, CAPTURED, FAILED, REFUNDED, PARTIALLY_REFUNDED }

data class Payment(val id: Id, val tenantId: Id, val orderId: Id, val amountMinor: Long, val refundedMinor: Long, val status: PaymentStatus, val method: String, val gatewayRef: String?, val createdAt: Instant)

data class LedgerEntry(val id: Id, val tenantId: Id, val orderId: Id?, val txnId: Id, val type: String, val account: String, val amountMinor: Long, val createdAt: Instant)

data class Payout(val id: Id, val tenantId: Id, val providerId: Id, val amountMinor: Long, val orderCount: Int, val status: String, val createdAt: Instant, val reference: String? = null, val failureReason: String? = null, val periodFrom: Instant? = null, val periodTo: Instant? = null)

data class Review(val id: Id, val tenantId: Id, val orderId: Id, val locationId: Id, val customerName: String, val rating: Int, val comment: String, val createdAt: Instant, val tags: List<String> = emptyList(), val reply: String? = null, val replyAt: Instant? = null)

data class Asset(val id: Id, val tenantId: Id, val ownerUserId: Id, val kind: String, val label: String, val attributes: Map<String, String>)

data class AuditEntry(val id: Id, val tenantId: Id, val actorId: Id?, val actorRole: String, val action: String, val entity: String, val entityId: String?, val detail: String, val at: Instant)

data class Slot(val startsAt: Instant, val endsAt: Instant, val free: Int, val bookable: Boolean)

enum class RuleScope { OUTLET, VENDOR, CATEGORY }

/** A commission rate for an outlet, a vendor or a service category, effective from a date. The most specific active rule wins. */
data class CommissionRule(
    val id: Id, val tenantId: Id, val scope: RuleScope, val target: String, val targetLabel: String, val rateBps: Int,
    val effectiveFrom: Instant, val effectiveUntil: Instant?, val setBy: String, val createdAt: Instant,
)

/** PERCENT value is in basis points; FLAT and PRICE are minor units. PRICE (a set price per service) is for promotions only, not codes. */
enum class PromoKind { PERCENT, FLAT, PRICE }

/**
 * A voucher or coupon code. PERCENT value is in basis points; FLAT is minor units.
 * [providerId] limits it to one vendor's outlets; [fundedBy] says who pays. [ownerUserId] makes it a personal voucher
 * (a referral reward) that only that customer can use. [source] is ADMIN, REFERRAL_WELCOME or REFERRAL_REWARD.
 */
data class PromoCode(
    val tenantId: Id, val code: String, val kind: PromoKind, val value: Long, val maxDiscountMinor: Long? = null, val minSubtotalMinor: Long = 0,
    val startsAt: Instant? = null, val expiresAt: Instant? = null, val usageLimit: Int? = null, val perUserLimit: Int = 1, val active: Boolean = true, val description: String = "",
    val providerId: Id? = null, val fundedBy: Funder = Funder.PLATFORM, val ownerUserId: Id? = null, val source: String = "ADMIN", val createdAt: Instant? = null,
)

/** HAPPY_HOUR repeats on chosen weekdays and times; DATE_DISCOUNT runs on a date or date range. Both apply without a code. */
enum class PromotionType { HAPPY_HOUR, DATE_DISCOUNT }

/**
 * An automatic price cut. A vendor's promotion ([providerId] set) applies to its outlets and the vendor funds it;
 * a platform promotion ([providerId] null) applies everywhere and the platform funds it.
 * Empty lists mean "all": every outlet, service, vehicle type or weekday. Times and dates are the outlet's local time,
 * checked against the slot's start. The window is [startTime, endTime); the date range includes both ends.
 */
data class Promotion(
    val id: Id, val tenantId: Id, val providerId: Id?, val type: PromotionType, val name: String,
    val kind: PromoKind, val value: Long, val maxDiscountMinor: Long? = null,
    val locationIds: List<Id> = emptyList(), val services: List<String> = emptyList(), val variantKeys: List<String> = emptyList(),
    val days: List<java.time.DayOfWeek> = emptyList(), val startTime: LocalTime? = null, val endTime: LocalTime? = null,
    val startDate: java.time.LocalDate? = null, val endDate: java.time.LocalDate? = null,
    val active: Boolean = true, val createdBy: String = "", val createdAt: Instant, val updatedAt: Instant = createdAt,
) {
    val funder get() = if (providerId == null) Funder.PLATFORM else Funder.VENDOR
}

/** A customer's own code to share. */
data class ReferralCode(val tenantId: Id, val userId: Id, val code: String, val createdAt: Instant)

enum class ReferralStatus { PENDING, REWARDED }

/** A new customer signed up with a friend's code. The friend is rewarded once the new customer's first booking is completed. */
data class Referral(
    val id: Id, val tenantId: Id, val referrerId: Id, val refereeId: Id, val code: String, val status: ReferralStatus,
    val welcomeVoucher: String?, val rewardVoucher: String? = null, val createdAt: Instant, val rewardedAt: Instant? = null,
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
