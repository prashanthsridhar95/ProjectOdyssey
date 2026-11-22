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
    val variantKey: String? = null, val openNow: Boolean = false, val minPriceMinor: Long? = null, val maxPriceMinor: Long? = null,
    val minRating: Double = 0.0, val services: Set<String> = emptySet(), val amenities: Set<String> = emptySet(),
) {
    /** How many filters are switched on, for the "Filters · 2" badge. */
    val activeCount: Int get() = listOf(openNow, services.isNotEmpty(), minPriceMinor != null || maxPriceMinor != null, minRating > 0, amenities.isNotEmpty()).count { it }
}

data class PlaceOrderInput(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String, val assetId: String?, val promoCode: String? = null)

interface CustomerRepository {
    suspend fun search(filters: SearchFilters): List<LocationSummary>
    suspend fun detail(locationId: String, variantKey: String?, lat: Double? = null, lng: Double? = null): LocationDetail
    suspend fun slots(locationId: String, date: String, itemIds: List<String>): List<Slot>
    suspend fun assets(): List<Asset>
    suspend fun addAsset(attributes: Map<String, String>): Asset
    suspend fun deleteAsset(id: String)
    suspend fun placeOrder(input: PlaceOrderInput, idempotencyKey: String): Order
    suspend fun pay(orderId: String, method: String, simulate: String?, idempotencyKey: String): Order
    suspend fun review(orderId: String, rating: Int, comment: String, tags: List<String>)
    suspend fun quote(locationId: String, itemIds: List<String>, variantKey: String, promoCode: String?): Quote
    suspend fun reschedule(orderId: String, startsAt: String): Order
    suspend fun checkPayment(orderId: String): Order
    suspend fun report(orderId: String, category: String, description: String, photos: List<String>): Ticket
    suspend fun favorites(lat: Double, lng: Double): List<LocationSummary>
    suspend fun setFavorite(locationId: String, on: Boolean)
    suspend fun places(): List<SavedPlace>
    suspend fun savePlace(place: SavedPlace): List<SavedPlace>
    suspend fun deletePlace(label: String)
    suspend fun notifications(): List<AppNotification>
    suspend fun markNotificationsRead()
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
    suspend fun calendar(locationId: String, date: String): Calendar
    suspend fun slots(locationId: String, date: String): List<Slot>
    suspend fun addBlock(locationId: String, startsAt: String, endsAt: String, reason: String, bays: List<Int>?, repeat: String): Block
    suspend fun removeBlock(locationId: String, blockId: String)
    suspend fun items(locationId: String): List<Item>
    suspend fun saveItem(locationId: String, item: Item, pin: String?): Item
    suspend fun walkIn(locationId: String, itemIds: List<String>, variantKey: String, startsAt: String?, customerName: String, plate: String?, bay: Int?): Order
    suspend fun checkIn(code: String): Order
    suspend fun earnings(range: String, pin: String): EarningsReport
    suspend fun statement(range: String, pin: String): String
    suspend fun payouts(pin: String): List<PayoutRow>
    suspend fun staff(): List<User>
    suspend fun setAutoAccept(locationId: String, on: Boolean)
}

/** Small persistent key-value storage the apps provide (SharedPreferences, UserDefaults). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

interface AdminRepository {
    suspend fun dashboard(): Dashboard
    suspend fun providers(status: String?): List<Provider>
    suspend fun provider(id: String): Provider
    suspend fun providerAction(id: String, action: String, note: String?): Provider
    suspend fun setCommission(id: String, bps: Int?): Provider
    suspend fun orders(status: String?): List<Order>
}
