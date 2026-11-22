package com.odyssey.data

import com.odyssey.domain.*
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val providerDay = RunProviderDay(provider, orders)
    val adminOps = RunAdmin(admin, orders)

    private fun signedOut() { store.save(null); _session.value = null }
    fun logout() = auth.logout()
}
