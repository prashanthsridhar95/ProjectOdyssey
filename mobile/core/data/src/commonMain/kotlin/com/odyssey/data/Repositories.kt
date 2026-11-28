package com.odyssey.data

import com.odyssey.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable internal data class OtpBody(val phone: String)
@Serializable internal data class VerifyBody(val phone: String, val code: String, val name: String? = null)
@Serializable internal data class PinLoginBody(val phone: String, val pin: String)
@Serializable internal data class AuthReply(val accessToken: String, val user: User)
@Serializable internal data class AssetBody(val attributes: Map<String, String>)
@Serializable internal data class PlaceBody(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val assetId: String? = null, val promoCode: String? = null)
@Serializable internal data class PayBody(val method: String, val simulate: String? = null)
@Serializable internal data class ActionBody(val action: String, val note: String? = null)
@Serializable internal data class ReviewBody(val rating: Int, val comment: String, val tags: List<String> = emptyList())
@Serializable internal data class QuoteBody(val locationId: String, val itemIds: List<String>, val variantKey: String, val promoCode: String? = null)
@Serializable internal data class RescheduleBody(val startsAt: String)
@Serializable internal data class ReportBody(val category: String, val description: String, val photos: List<String>)
@Serializable internal data class BlockBody(val startsAt: String, val endsAt: String, val reason: String, val bays: List<Int>? = null, val repeat: String = "NONE")
@Serializable internal data class WalkInBody(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String? = null, val customerName: String, val plate: String? = null, val bay: Int? = null)
@Serializable internal data class CheckInBody(val code: String)
@Serializable internal data class LocationPatch(val autoAccept: Boolean)
@Serializable internal data class ItemBody(val name: String, val description: String, val category: String, val durationMinutes: Int, val active: Boolean, val prices: Map<String, Long>, val baysUsed: Int = 1, val outOfStock: Boolean = false)
@Serializable internal data class CommissionBody(val bps: Int?)
@Serializable internal data class ReplyBody(val text: String)
@Serializable internal data class StaffBody(val name: String, val phone: String, val role: String, val pin: String)
@Serializable internal data class BankBody(val accountName: String, val accountNumber: String, val ifsc: String)
@Serializable internal data class OwnerPinBody(val newPin: String, val currentPin: String? = null)
@Serializable internal data class HoldBody(val hold: Boolean)
@Serializable internal data class DocBody(val state: String, val note: String? = null)
@Serializable internal data class ResolveBody(val uphold: Boolean, val refundMinor: Long, val note: String)

class AuthRepositoryImpl(private val api: ApiClient, private val store: SessionStore, private val onChange: (Session?) -> Unit) : AuthRepository {
    override suspend fun config(): PublicConfig = api.get("/config")
    override suspend fun requestOtp(phone: String) = api.postUnit("/auth/otp", OtpBody(phone))
    override suspend fun verifyOtp(phone: String, code: String, name: String?): Session = remember(api.post<VerifyBody, AuthReply>("/auth/verify", VerifyBody(phone, code, name)))
    override suspend fun staffLogin(phone: String, pin: String): Session = remember(api.post<PinLoginBody, AuthReply>("/auth/pin", PinLoginBody(phone, pin)))
    override fun currentSession(): Session? = store.load()
    override fun logout() { store.save(null); onChange(null) }

    private fun remember(r: AuthReply): Session = Session(r.accessToken, r.user).also { store.save(it); onChange(it) }
}

class CustomerRepositoryImpl(private val api: ApiClient) : CustomerRepository {
    override suspend fun search(filters: SearchFilters): List<LocationSummary> = api.get(
        "/locations", mapOf("lat" to filters.lat, "lng" to filters.lng, "sort" to filters.sort, "q" to filters.text, "variant" to filters.variantKey,
            "openNow" to filters.openNow.takeIf { it }, "maxPrice" to filters.maxPriceMinor, "minPrice" to filters.minPriceMinor, "radiusKm" to 30,
            "minRating" to filters.minRating.takeIf { it > 0 }, "services" to filters.services.takeIf { it.isNotEmpty() }?.joinToString(","), "amenities" to filters.amenities.takeIf { it.isNotEmpty() }?.joinToString(",")),
    )
    override suspend fun detail(locationId: String, variantKey: String?, lat: Double?, lng: Double?): LocationDetail = api.get("/locations/$locationId", mapOf("variant" to variantKey, "lat" to lat, "lng" to lng))
    override suspend fun slots(locationId: String, date: String, itemIds: List<String>): List<Slot> =
        api.get("/locations/$locationId/availability", mapOf("date" to date, "itemIds" to itemIds.joinToString(",")))
    override suspend fun assets(): List<Asset> = api.get("/assets")
    override suspend fun addAsset(attributes: Map<String, String>): Asset = api.post("/assets", AssetBody(attributes))
    override suspend fun deleteAsset(id: String) = api.delete("/assets/$id")
    override suspend fun placeOrder(input: PlaceOrderInput, idempotencyKey: String): Order =
        api.post("/orders", PlaceBody(input.locationId, input.itemIds, input.variantKey, input.startsAt, input.assetId, input.promoCode), idempotencyKey = idempotencyKey)
    override suspend fun pay(orderId: String, method: String, simulate: String?, idempotencyKey: String): Order =
        api.post("/orders/$orderId/pay", PayBody(method, simulate), idempotencyKey = idempotencyKey)
    override suspend fun review(orderId: String, rating: Int, comment: String, tags: List<String>) { api.post<ReviewBody, JsonElement>("/orders/$orderId/review", ReviewBody(rating, comment, tags)) }
    override suspend fun quote(locationId: String, itemIds: List<String>, variantKey: String, promoCode: String?): Quote = api.post("/quote", QuoteBody(locationId, itemIds, variantKey, promoCode))
    override suspend fun reschedule(orderId: String, startsAt: String): Order = api.post("/orders/$orderId/reschedule", RescheduleBody(startsAt))
    override suspend fun checkPayment(orderId: String): Order = api.post("/orders/$orderId/payment/check", RescheduleBody(""))
    override suspend fun report(orderId: String, category: String, description: String, photos: List<String>): Ticket = api.post("/orders/$orderId/report", ReportBody(category, description, photos))
    override suspend fun favorites(lat: Double, lng: Double): List<LocationSummary> = api.get("/favorites", mapOf("lat" to lat, "lng" to lng))
    override suspend fun setFavorite(locationId: String, on: Boolean) { if (on) api.putNoBody("/favorites/$locationId") else api.delete("/favorites/$locationId") }
    override suspend fun places(): List<SavedPlace> = api.get("/me/places")
    override suspend fun savePlace(place: SavedPlace): List<SavedPlace> = api.put("/me/places", place)
    override suspend fun deletePlace(label: String) = api.delete("/me/places/$label")
    override suspend fun notifications(): List<AppNotification> = api.get("/notifications")
    override suspend fun markNotificationsRead() { api.postNoBody("/notifications/read") }
}

class OrderRepositoryImpl(private val api: ApiClient) : OrderRepository {
    override suspend fun myOrders(): List<Order> = api.get("/orders")
    override suspend fun order(id: String): OrderDetail = api.get("/orders/$id")
    override suspend fun act(orderId: String, action: String, note: String?): Order = api.post("/orders/$orderId/actions", ActionBody(action, note))
}

class ProviderRepositoryImpl(private val api: ApiClient) : ProviderRepository {
    override suspend fun me(): Provider = api.get("/provider/me")
    override suspend fun orders(date: String, locationId: String?): List<Order> = api.get("/provider/orders", mapOf("date" to date, "locationId" to locationId))
    override suspend fun calendar(locationId: String, date: String): Calendar = api.get("/provider/locations/$locationId/calendar", mapOf("from" to date, "days" to 1))
    override suspend fun slots(locationId: String, date: String): List<Slot> = api.get("/locations/$locationId/availability", mapOf("date" to date))
    override suspend fun addBlock(locationId: String, startsAt: String, endsAt: String, reason: String, bays: List<Int>?, repeat: String): Block =
        api.post("/provider/locations/$locationId/blocks", BlockBody(startsAt, endsAt, reason, bays, repeat))
    override suspend fun removeBlock(locationId: String, blockId: String) = api.delete("/provider/locations/$locationId/blocks/$blockId")
    override suspend fun items(locationId: String): List<Item> = api.get("/provider/locations/$locationId/items")
    override suspend fun saveItem(locationId: String, item: Item, pin: String?): Item {
        val body = ItemBody(item.name, item.description, item.category, item.durationMinutes, item.active, item.prices, item.baysUsed, item.outOfStock)
        return if (item.id.isEmpty()) api.post("/provider/locations/$locationId/items", body, pin) else api.put("/provider/locations/$locationId/items/${item.id}", body, pin)
    }
    override suspend fun walkIn(locationId: String, itemIds: List<String>, variantKey: String, startsAt: String?, customerName: String, plate: String?, bay: Int?): Order =
        api.post("/provider/walk-ins", WalkInBody(locationId, itemIds, variantKey, startsAt, customerName, plate, bay))
    override suspend fun checkIn(code: String): Order = api.post("/provider/check-in", CheckInBody(code))
    override suspend fun earnings(range: String, pin: String): EarningsReport = api.get("/provider/earnings", mapOf("range" to range), pin = pin)
    override suspend fun statement(range: String, pin: String): String = api.getText("/provider/statement", mapOf("range" to range), pin = pin)
    override suspend fun payouts(pin: String): List<PayoutRow> = api.get("/provider/payouts", pin = pin)
    override suspend fun staff(): List<User> = api.get("/provider/staff")
    override suspend fun setAutoAccept(locationId: String, on: Boolean) { api.put<LocationPatch, JsonElement>("/provider/locations/$locationId", LocationPatch(on)) }
    override suspend fun updateLocation(locationId: String, update: LocationUpdate): ProviderLocation = api.put("/provider/locations/$locationId", update)
    override suspend fun reviews(): List<ProviderReview> = api.get("/provider/reviews")
    override suspend fun reply(reviewId: String, text: String): ProviderReview = api.post("/provider/reviews/$reviewId/reply", ReplyBody(text))
    override suspend fun addStaff(name: String, phone: String, role: Role, staffPin: String, ownerPin: String): User = api.post("/provider/staff", StaffBody(name, normalizePhone(phone), role.name, staffPin), ownerPin)
    override suspend fun removeStaff(id: String, ownerPin: String) = api.delete("/provider/staff/$id", ownerPin)
    override suspend fun saveBank(accountName: String, accountNumber: String, ifsc: String, ownerPin: String): Provider = api.put("/provider/bank", BankBody(accountName, accountNumber, ifsc), ownerPin)
    override suspend fun setOwnerPin(newPin: String, currentPin: String?) { api.putUnit("/provider/owner-pin", OwnerPinBody(newPin, currentPin)) }
}

class AdminRepositoryImpl(private val api: ApiClient) : AdminRepository {
    override suspend fun dashboard(range: String): Dashboard = api.get("/admin/dashboard", mapOf("range" to range))
    override suspend fun providers(status: String?): List<Provider> = api.get("/admin/providers", mapOf("status" to status))
    override suspend fun provider(id: String): Provider = api.get("/admin/providers/$id")
    override suspend fun providerAction(id: String, action: String, note: String?): Provider = api.post("/admin/providers/$id/actions", ActionBody(action, note))
    override suspend fun setCommission(id: String, bps: Int?): Provider = api.put("/admin/providers/$id/commission", CommissionBody(bps))
    override suspend fun orders(status: String?): List<Order> = api.get("/admin/orders", mapOf("status" to status, "limit" to 200))
    override suspend fun setHold(id: String, hold: Boolean): Provider = api.put("/admin/providers/$id/hold", HoldBody(hold))
    override suspend fun reviewDocument(id: String, type: String, state: String, note: String?): Provider = api.post("/admin/providers/$id/documents/$type", DocBody(state, note))
    override suspend fun tickets(status: String?): List<Ticket> = api.get("/admin/tickets", mapOf("status" to status))
    override suspend fun resolveTicket(id: String, uphold: Boolean, refundMinor: Long, note: String): Ticket = api.post("/admin/tickets/$id/resolve", ResolveBody(uphold, refundMinor, note))
}
