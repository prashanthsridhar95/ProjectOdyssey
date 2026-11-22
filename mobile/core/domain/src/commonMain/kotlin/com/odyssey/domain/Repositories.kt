package com.odyssey.domain

/** Ports. The data layer implements them over REST; tests implement them in memory. */

interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

interface AuthRepository {
    suspend fun config(): PublicConfig
    suspend fun requestOtp(phone: String)
    suspend fun verifyOtp(phone: String, code: String, name: String?): Session
    suspend fun staffLogin(phone: String, pin: String): Session
    fun currentSession(): Session?
    fun logout()
}

data class SearchFilters(
    val lat: Double, val lng: Double, val sort: String = "distance", val text: String? = null,
    val variantKey: String? = null, val openNow: Boolean = false, val maxPriceMinor: Long? = null,
)

data class PlaceOrderInput(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val assetId: String?)

interface CustomerRepository {
    suspend fun search(filters: SearchFilters): List<LocationSummary>
    suspend fun detail(locationId: String, variantKey: String?): LocationDetail
    suspend fun slots(locationId: String, date: String, itemIds: List<String>): List<Slot>
    suspend fun assets(): List<Asset>
    suspend fun addAsset(attributes: Map<String, String>): Asset
    suspend fun deleteAsset(id: String)
    suspend fun placeOrder(input: PlaceOrderInput, idempotencyKey: String): Order
    suspend fun pay(orderId: String, method: String, simulate: String?, idempotencyKey: String): Order
    suspend fun review(orderId: String, rating: Int, comment: String)
}

/** Order reads and state changes are shared by every role; the server decides who may do what. */
interface OrderRepository {
    suspend fun myOrders(): List<Order>
    suspend fun order(id: String): OrderDetail
    suspend fun act(orderId: String, action: String, note: String?): Order
}

interface ProviderRepository {
    suspend fun me(): Provider
    suspend fun orders(date: String, locationId: String?): List<Order>
    suspend fun slots(locationId: String, date: String): List<Slot>
    suspend fun blocks(locationId: String, date: String): List<Block>
    suspend fun addBlock(locationId: String, startsAt: String, endsAt: String, reason: String): Block
    suspend fun removeBlock(locationId: String, blockId: String)
    suspend fun items(locationId: String): List<Item>
    suspend fun saveItem(locationId: String, item: Item, pin: String?): Item
    suspend fun walkIn(locationId: String, itemId: String, variantKey: String, startsAt: String, customerName: String): Order
    suspend fun earnings(pin: String): Earnings
}

interface AdminRepository {
    suspend fun dashboard(): Dashboard
    suspend fun providers(status: String?): List<Provider>
    suspend fun provider(id: String): Provider
    suspend fun providerAction(id: String, action: String, note: String?): Provider
    suspend fun setCommission(id: String, bps: Int?): Provider
    suspend fun orders(status: String?): List<Order>
}
