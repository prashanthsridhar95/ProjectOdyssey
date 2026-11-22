package com.odyssey.data

import com.odyssey.domain.*
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** In-memory session store; apps pass a persistent one (SharedPreferences, Keychain). */
class MemorySessionStore : SessionStore {
    private var session: Session? = null
    override fun load() = session
    override fun save(session: Session?) { this.session = session }
}

/**
 * The composition root for the mobile apps: wires the data layer to the domain use cases.
 * Plain constructor injection, so a DI library (Koin, Kotlin-inject) can replace it later without touching screens.
 */
class AppContainer(
    baseUrl: String,
    tenant: String,
    private val store: SessionStore = MemorySessionStore(),
    engine: HttpClientEngine = defaultEngine(),
    val kv: KeyValueStore = MemoryKeyValueStore(),
) {
    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<Session?> = _session

    val api = ApiClient(baseUrl, tenant, token = { _session.value?.accessToken }, engine = engine, onUnauthorized = { signedOut() })

    val auth: AuthRepository = AuthRepositoryImpl(api, store) { _session.value = it }
    val customer: CustomerRepository = CustomerRepositoryImpl(api)
    val orders: OrderRepository = OrderRepositoryImpl(api)
    val provider: ProviderRepository = ProviderRepositoryImpl(api)
    val admin: AdminRepository = AdminRepositoryImpl(api)

    val signInWithOtp = SignInWithOtp(auth)
    val signInWithPin = SignInWithPin(auth)
    val searchPlaces = SearchPlaces(customer)
    val loadPlace = LoadPlace(customer)
    val loadSlots = LoadSlots(customer)
    val buildCheckout = BuildCheckout()
    val placeAndPay = PlaceAndPayOrder(customer)
    val bookings = ManageBookings(orders, customer)
    val actionQueue = ActionQueue(kv)
    val providerDay = RunProviderDay(provider, orders, actionQueue)

    /** People who can unlock the counter device, remembered from the last owner sign-in. */
    private val rosterJson = kotlinx.serialization.builtins.ListSerializer(RosterEntry.serializer())
    fun roster(): List<RosterEntry> = kv.get("roster")?.let { runCatching { apiJson.decodeFromString(rosterJson, it) }.getOrNull() } ?: emptyList()
    fun saveRoster(list: List<RosterEntry>) = kv.put("roster", apiJson.encodeToString(rosterJson, list))
    fun lockoutName(): String? = kv.get("outlet")
    fun saveOutlet(name: String) = kv.put("outlet", name)
    val adminOps = RunAdmin(admin, orders)

    /** True while the counter device shows the lock screen. The vendor app locks on a cold start and after time in the background. */
    val locked = MutableStateFlow(true)

    private fun signedOut() { store.save(null); _session.value = null }
    fun logout() = auth.logout()
}
