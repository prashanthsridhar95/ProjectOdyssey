package com.odyssey.domain

import kotlinx.serialization.Serializable

// Domain models. They are annotated @Serializable so the data layer can parse the REST contract directly;
// the annotation is metadata only, the domain layer has no dependency on Ktor, Android or iOS.

@Serializable
enum class Role { CUSTOMER, PROVIDER_OWNER, PROVIDER_MANAGER, PROVIDER_STAFF, ADMIN, SUPPORT, FINANCE }

@Serializable
data class User(val id: String, val name: String, val phone: String, val role: Role, val providerId: String? = null)

@Serializable
data class Session(val accessToken: String, val user: User)

// ---- tenant configuration (the vertical's words and rules) ----

@Serializable data class Labels(val provider: String, val location: String, val order: String, val item: String, val customer: String = "Customer")
@Serializable data class VariantOption(val key: String, val label: String)
@Serializable data class VariantDimension(val name: String, val options: List<VariantOption>)
@Serializable data class AssetField(val key: String, val label: String, val required: Boolean = true, val options: List<String> = emptyList())
@Serializable data class AssetKind(val kind: String, val label: String, val labelPlural: String, val fields: List<AssetField> = emptyList())
@Serializable data class StateDef(val name: String, val label: String = name, val occupiesCapacity: Boolean = false, val terminal: Boolean = false)
@Serializable data class FlowDef(val initial: String, val paid: String, val accepted: String, val completed: String, val states: List<StateDef> = emptyList())
@Serializable data class MoneyConfig(val currency: String = "INR", val taxBps: Int = 0, val customerFeeMinor: Long = 0)
@Serializable data class AvailabilityConfig(val leadMinutes: Int = 30, val windowDays: Int = 7, val holdMinutes: Int = 5)
@Serializable data class CancellationPolicy(val fullRefundHoursBefore: Int = 2, val lateRetainBps: Int = 5000)
@Serializable data class ServiceTemplate(val name: String, val category: String, val durationMinutes: Int, val description: String = "")

@Serializable
data class TenantConfig(
    val labels: Labels,
    val variants: VariantDimension,
    val asset: AssetKind? = null,
    val flow: FlowDef,
    val availability: AvailabilityConfig = AvailabilityConfig(),
    val money: MoneyConfig = MoneyConfig(),
    val cancellation: CancellationPolicy = CancellationPolicy(),
    val serviceTemplates: List<ServiceTemplate> = emptyList(),
    val amenities: List<String> = emptyList(),
    val serviceAreas: List<ServiceArea> = emptyList(),
)

@Serializable data class ServiceArea(val name: String, val lat: Double, val lng: Double)

@Serializable data class TenantInfo(val slug: String, val name: String, val vertical: String)
@Serializable data class PublicConfig(val tenant: TenantInfo, val config: TenantConfig)

// ---- marketplace ----

@Serializable
data class LocationSummary(
    val id: String, val providerId: String, val name: String, val providerName: String, val address: String,
    val lat: Double, val lng: Double, val distanceKm: Double, val priceFromMinor: Long? = null, val nextFreeAt: String? = null,
    val openNow: Boolean = false, val ratingAvg: Double = 0.0, val ratingCount: Int = 0, val amenities: List<String> = emptyList(),
    val photoUrl: String? = null, val openTime: String = "", val closeTime: String = "", val phone: String? = null,
)

@Serializable
data class Item(val id: String, val name: String, val description: String = "", val category: String = "", val durationMinutes: Int, val active: Boolean = true, val prices: Map<String, Long> = emptyMap(), val baysUsed: Int = 1, val outOfStock: Boolean = false)

@Serializable data class Review(val id: String, val customerName: String, val rating: Int, val comment: String = "", val createdAt: String, val tags: List<String> = emptyList())
@Serializable data class LocationDetail(val location: LocationSummary, val items: List<Item> = emptyList(), val reviews: List<Review> = emptyList())
@Serializable data class Slot(val startsAt: String, val endsAt: String, val free: Int, val bookable: Boolean)

@Serializable data class OrderLine(val itemId: String, val name: String, val priceMinor: Long, val durationMinutes: Int)

@Serializable
data class Order(
    val id: String, val code: String, val status: String, val statusLabel: String, val source: String = "APP",
    val customerName: String = "", val customerPhone: String = "", val providerId: String, val locationId: String, val locationName: String,
    val assetLabel: String? = null, val variantKey: String, val startsAt: String, val endsAt: String, val lines: List<OrderLine> = emptyList(),
    val subtotalMinor: Long, val taxMinor: Long = 0, val feeMinor: Long = 0, val totalMinor: Long,
    val commissionBps: Int? = null, val commissionMinor: Long? = null, val holdExpiresAt: String? = null,
    val cancellationReason: String? = null, val createdAt: String = "", val actions: List<String> = emptyList(), val reviewed: Boolean = false,
    val discountMinor: Long = 0, val promoCode: String? = null, val paymentPending: Boolean = false, val reschedules: Int = 0,
    val bay: Int? = null, val acceptBy: String? = null,
    val locationPhone: String? = null, val locationAddress: String? = null, val locationLat: Double? = null, val locationLng: Double? = null,
)

@Serializable data class OrderEvent(val from: String? = null, val to: String, val action: String, val note: String? = null, val at: String, val by: String? = null)
@Serializable data class OrderDetail(val order: Order, val events: List<OrderEvent> = emptyList())

@Serializable data class Asset(val id: String, val kind: String, val label: String, val attributes: Map<String, String> = emptyMap())

@Serializable
data class Quote(val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val discountMinor: Long, val totalMinor: Long, val promoCode: String? = null, val promoDescription: String? = null)

@Serializable data class SavedPlace(val label: String, val address: String, val lat: Double, val lng: Double)
@Serializable data class AppNotification(val id: String, val title: String, val body: String, val orderId: String? = null, val read: Boolean = false, val createdAt: String)

@Serializable
data class Ticket(
    val id: String, val orderId: String, val orderCode: String? = null, val locationName: String? = null, val customerName: String? = null, val category: String,
    val description: String, val photos: List<String> = emptyList(), val status: String, val resolutionNote: String? = null, val refundMinor: Long = 0, val totalMinor: Long? = null, val createdAt: String,
)

// ---- provider and admin ----

@Serializable
data class ProviderLocation(
    val id: String, val name: String, val address: String, val lat: Double = 0.0, val lng: Double = 0.0, val openTime: String, val closeTime: String,
    val slotMinutes: Int, val capacity: Int, val autoAccept: Boolean, val open: Boolean, val amenities: List<String> = emptyList(), val ratingAvg: Double = 0.0, val ratingCount: Int = 0,
)

@Serializable data class DocumentRef(val type: String, val reference: String, val state: String = "PENDING", val note: String? = null)
@Serializable data class BankInfo(val accountName: String, val accountNumberMasked: String, val ifsc: String)

@Serializable
data class Provider(
    val id: String, val name: String, val status: String, val commissionBps: Int? = null, val reviewNote: String? = null,
    val documents: List<DocumentRef> = emptyList(), val bank: BankInfo? = null, val ownerName: String? = null, val ownerPhone: String? = null,
    val locations: List<ProviderLocation> = emptyList(), val createdAt: String = "", val payoutHold: Boolean = false, val staffCount: Int = 0,
)

@Serializable data class Block(val id: String, val startsAt: String, val endsAt: String, val reason: String = "", val bays: List<Int>? = null, val repeat: String = "NONE")
@Serializable data class Window(val startsAt: String, val endsAt: String, val bays: List<Int>? = null, val reason: String = "", val repeat: String = "NONE")
@Serializable data class Calendar(val capacity: Int, val slotMinutes: Int, val openTime: String, val closeTime: String, val orders: List<Order> = emptyList(), val windows: List<Window> = emptyList())
@Serializable
data class EarningsReport(
    val from: String = "", val to: String = "", val orders: Int = 0, val serviceMinor: Long = 0, val commissionMinor: Long = 0, val commissionBps: Int = 0, val gatewayFeeMinor: Long = 0,
    val netMinor: Long = 0, val pendingMinor: Long = 0, val paidOutMinor: Long = 0, val nextPayoutDate: String = "", val bankMasked: String? = null,
)
@Serializable data class PayoutRow(val id: String, val amountMinor: Long, val orderCount: Int, val status: String, val createdAt: String, val reference: String? = null)
/** A review as the vendor sees it, with their public reply. */
@Serializable data class ProviderReview(val id: String, val customerName: String, val rating: Int, val comment: String = "", val createdAt: String, val tags: List<String> = emptyList(), val reply: String? = null, val replyAt: String? = null)
/** What a vendor can change about an outlet; null leaves a field as it is. */
@Serializable data class LocationUpdate(val openTime: String? = null, val closeTime: String? = null, val slotMinutes: Int? = null, val capacity: Int? = null, val autoAccept: Boolean? = null, val open: Boolean? = null)
@Serializable data class RosterEntry(val id: String, val name: String, val phone: String, val role: Role)

@Serializable data class DayPoint(val date: String, val gmvMinor: Long)
@Serializable data class AlertItem(val kind: String, val title: String, val meta: String)
@Serializable data class VendorRow(val providerId: String, val name: String, val bookings: Int, val gmvMinor: Long, val utilisationPct: Int, val ratingAvg: Double, val cancelPct: Double, val noShowPct: Double)

@Serializable
data class Dashboard(
    val range: String = "", val gmvMinor: Long = 0, val gmvDeltaPct: Int? = null, val bookings: Int = 0, val bookingsPerDay: Int = 0, val takeRateBps: Int = 0, val utilisationPct: Int = 0,
    val cancellationPct: Double = 0.0, val noShowPct: Double = 0.0, val vendorsLive: Int = 0, val vendorsInReview: Int = 0,
    val series: List<DayPoint> = emptyList(), val alerts: List<AlertItem> = emptyList(), val topVendors: List<VendorRow> = emptyList(), val customers: Int = 0,
)

/** Every failure the apps show to a person. The server's error code and message come through unchanged. */
class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message) {
    val isPinProblem get() = code == "pin_invalid" || code == "pin_locked"
    val isUnauthorized get() = status == 401
}
