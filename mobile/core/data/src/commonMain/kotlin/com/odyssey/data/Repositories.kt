package com.odyssey.data

import com.odyssey.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable internal data class OtpBody(val phone: String)
@Serializable internal data class VerifyBody(val phone: String, val code: String, val name: String? = null)
@Serializable internal data class PinLoginBody(val phone: String, val pin: String)
@Serializable internal data class AuthReply(val accessToken: String, val user: User)
@Serializable internal data class AssetBody(val attributes: Map<String, String>)
@Serializable internal data class PlaceBody(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val assetId: String? = null)
@Serializable internal data class PayBody(val method: String, val simulate: String? = null)
@Serializable internal data class ActionBody(val action: String, val note: String? = null)
@Serializable internal data class ReviewBody(val rating: Int, val comment: String)
@Serializable internal data class BlockBody(val startsAt: String, val endsAt: String, val reason: String)
@Serializable internal data class WalkInBody(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val customerName: String)
@Serializable internal data class ItemBody(val name: String, val description: String, val category: String, val durationMinutes: Int, val active: Boolean, val prices: Map<String, Long>)
@Serializable internal data class CommissionBody(val bps: Int?)

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
            "openNow" to filters.openNow.takeIf { it }, "maxPrice" to filters.maxPriceMinor, "radiusKm" to 30),
    )
    override suspend fun detail(locationId: String, variantKey: String?): LocationDetail = api.get("/locations/$locationId", mapOf("variant" to variantKey))
    override suspend fun slots(locationId: String, date: String, itemIds: List<String>): List<Slot> =
        api.get("/locations/$locationId/availability", mapOf("date" to date, "itemIds" to itemIds.joinToString(",")))
    override suspend fun assets(): List<Asset> = api.get("/assets")
    override suspend fun addAsset(attributes: Map<String, String>): Asset = api.post("/assets", AssetBody(attributes))
    override suspend fun deleteAsset(id: String) = api.delete("/assets/$id")
    override suspend fun placeOrder(input: PlaceOrderInput, idempotencyKey: String): Order =
        api.post("/orders", PlaceBody(input.locationId, input.itemIds, input.variantKey, input.startsAt, input.assetId), idempotencyKey = idempotencyKey)
    override suspend fun pay(orderId: String, method: String, simulate: String?, idempotencyKey: String): Order =
        api.post("/orders/$orderId/pay", PayBody(method, simulate), idempotencyKey = idempotencyKey)
    override suspend fun review(orderId: String, rating: Int, comment: String) { api.post<ReviewBody, JsonElement>("/orders/$orderId/review", ReviewBody(rating, comment)) }
}

class OrderRepositoryImpl(private val api: ApiClient) : OrderRepository {
    override suspend fun myOrders(): List<Order> = api.get("/orders")
    override suspend fun order(id: String): OrderDetail = api.get("/orders/$id")
    override suspend fun act(orderId: String, action: String, note: String?): Order = api.post("/orders/$orderId/actions", ActionBody(action, note))
}

class ProviderRepositoryImpl(private val api: ApiClient) : ProviderRepository {
    override suspend fun me(): Provider = api.get("/provider/me")
    override suspend fun orders(date: String, locationId: String?): List<Order> = api.get("/provider/orders", mapOf("date" to date, "locationId" to locationId))
    override suspend fun slots(locationId: String, date: String): List<Slot> = api.get("/locations/$locationId/availability", mapOf("date" to date))
    override suspend fun blocks(locationId: String, date: String): List<Block> = api.get("/provider/locations/$locationId/blocks", mapOf("date" to date))
    override suspend fun addBlock(locationId: String, startsAt: String, endsAt: String, reason: String): Block =
        api.post("/provider/locations/$locationId/blocks", BlockBody(startsAt, endsAt, reason))
    override suspend fun removeBlock(locationId: String, blockId: String) = api.delete("/provider/locations/$locationId/blocks/$blockId")
    override suspend fun items(locationId: String): List<Item> = api.get("/provider/locations/$locationId/items")
    override suspend fun saveItem(locationId: String, item: Item, pin: String?): Item {
        val body = ItemBody(item.name, item.description, item.category, item.durationMinutes, item.active, item.prices)
        return if (item.id.isEmpty()) api.post("/provider/locations/$locationId/items", body, pin) else api.put("/provider/locations/$locationId/items/${item.id}", body, pin)
    }
    override suspend fun walkIn(locationId: String, itemId: String, variantKey: String, startsAt: String, customerName: String): Order =
        api.post("/provider/walk-ins", WalkInBody(locationId, listOf(itemId), variantKey, startsAt, customerName))
    override suspend fun earnings(pin: String): Earnings = api.get("/provider/earnings", pin = pin)
}

class AdminRepositoryImpl(private val api: ApiClient) : AdminRepository {
    override suspend fun dashboard(): Dashboard = api.get("/admin/dashboard")
    override suspend fun providers(status: String?): List<Provider> = api.get("/admin/providers", mapOf("status" to status))
    override suspend fun provider(id: String): Provider = api.get("/admin/providers/$id")
    override suspend fun providerAction(id: String, action: String, note: String?): Provider = api.post("/admin/providers/$id/actions", ActionBody(action, note))
    override suspend fun setCommission(id: String, bps: Int?): Provider = api.put("/admin/providers/$id/commission", CommissionBody(bps))
    override suspend fun orders(status: String?): List<Order> = api.get("/admin/orders", mapOf("status" to status, "limit" to 200))
}
