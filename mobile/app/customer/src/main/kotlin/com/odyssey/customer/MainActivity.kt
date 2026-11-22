package com.odyssey.customer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.odyssey.auth.LoginScreen
import com.odyssey.booking.*
import com.odyssey.carwash.CarWashUi
import com.odyssey.data.AndroidSessionStore
import com.odyssey.data.AppContainer
import com.odyssey.discovery.HomeScreen
import com.odyssey.discovery.PlaceScreen
import com.odyssey.domain.PublicConfig
import androidx.compose.foundation.layout.fillMaxSize
import com.odyssey.ui.*

/** Where the customer can be. Each route carries only plain values, so state survives process death easily. */
sealed interface Route {
    data object Home : Route
    data class Place(val id: String) : Route
    data class Slots(val locationId: String, val itemIds: List<String>, val variantKey: String, val assetId: String?) : Route
    data class Checkout(val locationId: String, val itemIds: List<String>, val variantKey: String, val assetId: String?, val startsAt: String) : Route
    data object Bookings : Route
    data class Booking(val id: String) : Route
}

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(BuildConfig.API_URL, BuildConfig.TENANT, AndroidSessionStore(this, "customer")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        setContent {
            OdysseyTheme {
                var config by remember { mutableStateOf<PublicConfig?>(null) }
                var failure by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() } }
                val loaded = config
                when {
                    loaded != null -> CompositionLocalProvider(
                        LocalContainer provides container, LocalTenant provides loaded, LocalVertical provides CarWashUi, LocalLocation provides AndroidLocationProvider(this),
                    ) { CustomerApp(loaded.tenant.name) }
                    failure != null -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) { ErrorBox(failure!!) }
                    else -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                }
            }
        }
    }
}


@Composable
private fun CustomerApp(tenantName: String) {
    val app = LocalContainer.current
    val session = app.session.collectAsStateValue()
    val nav = remember { Navigator<Route>(Route.Home) }
    BackHandler(enabled = nav.canPop) { nav.pop() }

    if (session == null) {
        LoginScreen(title = tenantName, subtitle = "Book a car wash near you", askName = true, onSignedIn = { nav.reset(Route.Home) })
        return
    }
    when (val r = nav.current) {
        Route.Home -> HomeScreen(tenantName, onOpen = { nav.push(Route.Place(it)) }, onBookings = { nav.push(Route.Bookings) }, onSignOut = app::logout)
        is Route.Place -> PlaceScreen(r.id, onBack = { nav.pop() }, onPickSlot = { items, variant, asset -> nav.push(Route.Slots(r.id, items, variant, asset)) })
        is Route.Slots -> SlotPickerScreen(r.locationId, r.itemIds, onBack = { nav.pop() }, onContinue = { nav.push(Route.Checkout(r.locationId, r.itemIds, r.variantKey, r.assetId, it)) })
        is Route.Checkout -> CheckoutScreen(
            r.locationId, r.itemIds, r.variantKey, r.startsAt, r.assetId, allowSimulation = BuildConfig.DEBUG,
            onBack = { nav.pop() }, onPaid = { nav.reset(Route.Home); nav.push(Route.Booking(it.id)) },
        )
        Route.Bookings -> BookingsScreen(onBack = { nav.pop() }, onOpen = { nav.push(Route.Booking(it)) })
        is Route.Booking -> BookingDetailScreen(r.id, onBack = { nav.pop() })
    }
}
