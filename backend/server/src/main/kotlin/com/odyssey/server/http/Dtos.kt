package com.odyssey.server.http

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.service.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class ErrorBody(val code: String, val message: String)

@Serializable data class OtpRequest(val phone: String)
@Serializable data class VerifyRequest(val phone: String, val code: String, val name: String? = null)
@Serializable data class StaffLoginRequest(val phone: String, val pin: String)
@Serializable data class UserDto(val id: String, val name: String, val phone: String, val role: String, val providerId: String? = null)
@Serializable data class AuthResponse(val accessToken: String, val expiresAt: String, val user: UserDto)
@Serializable data class TenantInfo(val slug: String, val name: String, val vertical: String)
@Serializable data class PublicConfig(val tenant: TenantInfo, val config: TenantConfig)

@Serializable
data class LocationSummaryDto(
    val id: String, val providerId: String, val name: String, val providerName: String, val address: String, val lat: Double, val lng: Double,
    val distanceKm: Double, val priceFromMinor: Long? = null, val nextFreeAt: String? = null, val openNow: Boolean,
    val ratingAvg: Double, val ratingCount: Int, val amenities: List<String>, val photoUrl: String? = null,
    val openTime: String, val closeTime: String, val phone: String? = null,
)

@Serializable
data class ItemDto(val id: String, val name: String, val description: String, val category: String, val durationMinutes: Int, val active: Boolean, val prices: Map<String, Long>)

@Serializable data class ReviewDto(val id: String, val customerName: String, val rating: Int, val comment: String, val createdAt: String, val tags: List<String> = emptyList())
@Serializable data class LocationDetailDto(val location: LocationSummaryDto, val items: List<ItemDto>, val reviews: List<ReviewDto>)
@Serializable data class SlotDto(val startsAt: String, val endsAt: String, val free: Int, val bookable: Boolean)

@Serializable data class PlaceOrderRequest(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val assetId: String? = null, val promoCode: String? = null)
@Serializable data class PayRequest(val method: String = "UPI", val simulate: String? = null)
@Serializable data class ActionRequest(val action: String, val note: String? = null)
@Serializable data class ReviewRequest(val rating: Int, val comment: String = "", val tags: List<String> = emptyList())

@Serializable
data class OrderDto(
    val id: String, val code: String, val status: String, val statusLabel: String, val source: String,
    val customerName: String, val customerPhone: String, val providerId: String, val locationId: String, val locationName: String,
    val assetLabel: String? = null, val variantKey: String, val startsAt: String, val endsAt: String,
    val lines: List<OrderLineView>, val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val totalMinor: Long,
    val commissionBps: Int? = null, val commissionMinor: Long? = null,
    val holdExpiresAt: String? = null, val cancellationReason: String? = null, val createdAt: String,
    val actions: List<String>, val reviewed: Boolean = false,
    val discountMinor: Long = 0, val promoCode: String? = null, val paymentPending: Boolean = false, val reschedules: Int = 0,
    val locationPhone: String? = null, val locationAddress: String? = null, val locationLat: Double? = null, val locationLng: Double? = null,
)

@Serializable data class OrderLineView(val itemId: String, val name: String, val priceMinor: Long, val durationMinutes: Int)
@Serializable data class OrderEventDto(val from: String? = null, val to: String, val action: String, val note: String? = null, val at: String)
@Serializable data class OrderDetailDto(val order: OrderDto, val events: List<OrderEventDto>)

@Serializable data class AssetRequest(val label: String? = null, val attributes: Map<String, String>)
@Serializable data class AssetDto(val id: String, val kind: String, val label: String, val attributes: Map<String, String>)

@Serializable
data class LocationDraftDto(
    val name: String, val address: String, val lat: Double, val lng: Double,
    val openTime: String = "08:00", val closeTime: String = "20:00", val slotMinutes: Int = 30, val capacity: Int = 2, val amenities: List<String> = emptyList(), val phone: String? = null,
)

@Serializable
data class LocationDto(
    val id: String, val name: String, val address: String, val lat: Double, val lng: Double, val openTime: String, val closeTime: String,
    val slotMinutes: Int, val capacity: Int, val autoAccept: Boolean, val open: Boolean, val amenities: List<String>, val ratingAvg: Double, val ratingCount: Int, val phone: String? = null,
)

@Serializable
data class UpdateLocationRequest(
    val name: String? = null, val address: String? = null, val openTime: String? = null, val closeTime: String? = null,
    val slotMinutes: Int? = null, val capacity: Int? = null, val autoAccept: Boolean? = null, val open: Boolean? = null, val amenities: List<String>? = null, val phone: String? = null,
)

@Serializable data class DocumentDto(val type: String, val reference: String)
@Serializable data class BankDto(val accountName: String, val accountNumberMasked: String, val ifsc: String)
@Serializable data class BankRequest(val accountName: String, val accountNumber: String, val ifsc: String)

@Serializable
data class ProviderDto(
    val id: String, val name: String, val status: String, val commissionBps: Int? = null, val reviewNote: String? = null,
    val documents: List<DocumentDto>, val bank: BankDto? = null, val ownerName: String? = null, val ownerPhone: String? = null,
    val locations: List<LocationDto> = emptyList(), val createdAt: String,
)

@Serializable data class ApplyRequest(val name: String, val location: LocationDraftDto)
@Serializable data class AdminCreateProviderRequest(val name: String, val ownerName: String, val ownerPhone: String, val location: LocationDraftDto)
@Serializable data class ProviderActionRequest(val action: String, val note: String? = null)
@Serializable data class CommissionRequest(val bps: Int? = null)
@Serializable data class DocumentsRequest(val documents: List<DocumentDto>)
@Serializable data class ItemRequest(val name: String, val description: String = "", val category: String = "", val durationMinutes: Int, val active: Boolean = true, val prices: Map<String, Long>)
@Serializable data class BlockRequest(val startsAt: String, val endsAt: String, val reason: String = "")
@Serializable data class BlockDto(val id: String, val startsAt: String, val endsAt: String, val reason: String)
@Serializable data class WalkInRequest(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val customerName: String, val customerPhone: String = "")
@Serializable data class StaffRequest(val name: String, val phone: String, val role: String, val pin: String)
@Serializable data class PinRequest(val newPin: String, val currentPin: String? = null)
@Serializable data class PayoutDto(val id: String, val providerId: String, val providerName: String? = null, val amountMinor: Long, val orderCount: Int, val status: String, val createdAt: String)
@Serializable data class AuditDto(val id: String, val actorRole: String, val actorId: String? = null, val action: String, val entity: String, val entityId: String? = null, val detail: String, val at: String)
@Serializable data class PaymentWebhook(val event: String, val tenant: String, val orderId: String, val gatewayRef: String? = null, val method: String = "UPI")
@Serializable data class QuoteRequest(val locationId: String, val itemIds: List<String>, val variantKey: String, val promoCode: String? = null)
@Serializable data class QuoteDto(val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val discountMinor: Long, val totalMinor: Long, val promoCode: String? = null, val promoDescription: String? = null)
@Serializable data class RescheduleRequest(val startsAt: String)
@Serializable data class SavedPlaceDto(val label: String, val address: String, val lat: Double, val lng: Double)
@Serializable data class ReportRequest(val category: String, val description: String, val photos: List<String> = emptyList())
@Serializable data class TicketDto(val id: String, val orderId: String, val orderCode: String? = null, val locationName: String? = null, val customerName: String? = null, val category: String, val description: String, val photos: List<String>, val status: String, val resolutionNote: String? = null, val refundMinor: Long, val totalMinor: Long? = null, val createdAt: String)
@Serializable data class ResolveTicketRequest(val uphold: Boolean, val refundMinor: Long = 0, val note: String = "")
@Serializable data class NotificationDto(val id: String, val title: String, val body: String, val orderId: String? = null, val read: Boolean, val createdAt: String)
@Serializable data class DeviceRequest(val token: String, val platform: String = "android")
@Serializable data class CheckInRequest(val code: String)
@Serializable data class PromoDto(val code: String, val kind: String, val value: Long, val maxDiscountMinor: Long? = null, val minSubtotalMinor: Long = 0, val expiresAt: String? = null, val usageLimit: Int? = null, val perUserLimit: Int = 1, val active: Boolean = true, val description: String = "")
@Serializable data class ActiveRequest(val active: Boolean)
@Serializable data class CustomerDto(val id: String, val name: String, val phone: String, val active: Boolean)

// ---- mappers ------------------------------------------------------------------------------------------------

fun User.dto() = UserDto(id, name, phone, role.name, providerId)
fun Instant.iso(): String = toString()

fun LocationSummary.dto() = LocationSummaryDto(
    location.id, location.providerId, location.name, providerName, location.address, location.lat, location.lng, distanceKm, priceFromMinor,
    nextFreeAt?.iso(), openNow, location.ratingAvg, location.ratingCount, location.amenities, location.photoUrl, location.openTime.toString(), location.closeTime.toString(), location.phone,
)

fun CatalogItem.dto() = ItemDto(id, name, description, category, durationMinutes, active, prices)
fun Review.dto() = ReviewDto(id, customerName, rating, comment, createdAt.iso(), tags)
fun Location.dto() = LocationDto(id, name, address, lat, lng, openTime.toString(), closeTime.toString(), slotMinutes, capacity, autoAccept, open, amenities, ratingAvg, ratingCount, phone)
fun Slot.dto() = SlotDto(startsAt.iso(), endsAt.iso(), free, bookable)
fun Asset.dto() = AssetDto(id, kind, label, attributes)
fun Block.dto() = BlockDto(id, startsAt.iso(), endsAt.iso(), reason)

fun Provider.dto(owner: User? = null, locations: List<Location> = emptyList()) = ProviderDto(
    id, name, status.name, commissionBps, reviewNote, documents.map { DocumentDto(it.type, it.reference) },
    bank?.let { BankDto(it.accountName, it.accountNumberMasked, it.ifsc) }, owner?.name, owner?.phone, locations.map { it.dto() }, createdAt.iso(),
)

fun Order.dto(tenant: Tenant, actor: Actor, reviewed: Boolean = false, loc: Location? = null): OrderDto {
    val flow = tenant.config.flow
    val isCustomer = actor.role == Role.CUSTOMER
    return OrderDto(
        id, code, status, flow.states.firstOrNull { it.name == status }?.label ?: status, source.name,
        customerName, customerPhone, providerId, locationId, locationName, assetLabel, variantKey, startsAt.iso(), endsAt.iso(),
        lines.map { OrderLineView(it.itemId, it.name, it.priceMinor, it.durationMinutes) },
        subtotalMinor, taxMinor, feeMinor, totalMinor,
        if (isCustomer) null else commissionBps, if (isCustomer) null else commissionMinor,
        holdExpiresAt?.iso(), cancellationReason, createdAt.iso(),
        flow.actionsFor(status, actor.role).map { it.action }, reviewed,
        discountMinor, promoCode, paymentPending, reschedules, loc?.phone, loc?.address, loc?.lat, loc?.lng,
    )
}

fun Payout.dto(providerName: String? = null) = PayoutDto(id, providerId, providerName, amountMinor, orderCount, status, createdAt.iso())
fun AuditEntry.dto() = AuditDto(id, actorRole, actorId, action, entity, entityId, detail, at.iso())

fun LocationDraftDto.toDraft() = LocationDraft(name, address, lat, lng, java.time.LocalTime.parse(openTime), java.time.LocalTime.parse(closeTime), slotMinutes, capacity, amenities, phone)

/** Strips what only the tenant owner may see (commission defaults, our billing terms) before sending config to apps. */
fun Tenant.publicConfig() = PublicConfig(
    TenantInfo(slug, name, vertical),
    config.copy(billing = com.odyssey.core.config.BillingConfig(0, 0, 0), money = config.money.copy(defaultCommissionBps = 0)),
)

fun Ticket.dto(order: Order? = null) = TicketDto(id, orderId, order?.code, order?.locationName, order?.customerName, category, description, photos, status.name, resolutionNote, refundMinor, order?.totalMinor, createdAt.iso())
fun Notification.dto() = NotificationDto(id, title, body, orderId, read, createdAt.iso())
fun PromoCode.dto() = PromoDto(code, kind.name, value, maxDiscountMinor, minSubtotalMinor, expiresAt?.iso(), usageLimit, perUserLimit, active, description)
fun SavedPlace.dto() = SavedPlaceDto(label, address, lat, lng)
