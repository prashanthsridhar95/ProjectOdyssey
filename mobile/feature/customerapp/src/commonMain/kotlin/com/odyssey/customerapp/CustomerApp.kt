package com.odyssey.customerapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.backhandler.BackHandler
import com.composables.icons.lucide.*
import com.odyssey.auth.LoginScreen
import com.odyssey.booking.*
import com.odyssey.carwash.CarWashUi
import com.odyssey.data.AppContainer
import com.odyssey.discovery.*
import com.odyssey.domain.Order
import com.odyssey.domain.PublicConfig
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the customer can be. Each route carries plain values only. */
sealed interface Route {
    data object Tabs : Route
    data class Place(val id: String) : Route
    data class Slots(val locationId: String, val itemIds: List<String>, val variantKey: String) : Route
    data class Checkout(val locationId: String, val itemIds: List<String>, val variantKey: String, val startsAt: String) : Route
    data class Confirmed(val order: Order) : Route
    data class Reschedule(val order: Order) : Route
    data class Booking(val id: String) : Route
    data class Rate(val id: String) : Route
    data class Report(val id: String) : Route
    data class AddVehicle(val step: String?) : Route
    data object Notifications : Route
    data object Favorites : Route
}


/**
 * The customer app, start to finish: loads the tenant config, then shows sign-in or the tabs. Hosts (Android activity, web page)
 * supply the container, the platform actions and the location provider; everything else is shared.
 */
@Composable
fun CustomerEntry(container: AppContainer, platform: PlatformActions, location: LocationProvider, allowSimulation: Boolean, onReady: () -> Unit = {}) {
    IndustryTheme {
        var config by remember { mutableStateOf<PublicConfig?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { try { config = container.auth.config() } catch (e: Exception) { failure = e.friendly() }; if (failure != null) onReady() }
        val loaded = config
        when {
            loaded != null -> CompositionLocalProvider(
                LocalContainer provides container, LocalTenant provides loaded, LocalVertical provides CarWashUi, LocalLocation provides location,
                LocalPlatform provides platform, LocalCustomer provides remember { CustomerState() },
            ) { ProvideWindowClass { CustomerApp(loaded.tenant.name, allowSimulation, onReady) } }
            failure != null -> Box(Modifier.fillMaxSize().background(Ind.bg).padding(24.dp), Alignment.Center) { ErrorBox(failure!!) }
            else -> Box(Modifier.fillMaxSize().background(Ind.bg), Alignment.Center) { CircularProgressIndicator(color = Ind.accent) }
        }
    }
}

private val TABS = listOf(TabItem("Explore", Lucide.Compass), TabItem("Bookings", Lucide.Calendar), TabItem("Garage", Lucide.Car), TabItem("Account", Lucide.User))

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CustomerApp(tenantName: String, allowSimulation: Boolean, onReady: () -> Unit) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val platform = LocalPlatform.current
    val where = LocalLocation.current
    val session = app.session.collectAsStateValue()
    val nav = remember { Navigator<Route>(Route.Tabs) }
    val wide = LocalWindow.current.isWide
    var tab by remember { mutableStateOf(0) }
    var choosingPlace by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = nav.canPop) { nav.pop() }

    if (session == null) {
        LaunchedEffect(Unit) { onReady() }
        val login: @Composable () -> Unit = { LoginScreen("Book a car wash near you.", "See price and wait time before you leave home.", askName = true, onSignedIn = { nav.reset(Route.Tabs); tab = 0 }) }
        if (LocalWindow.current.isWide) CenteredContent(520.dp, login) else login()
        return
    }

    // Load what the customer keeps: vehicles, saved places, favourites. First time with no vehicle: onboarding step.
    var ready by remember(session.user.id) { mutableStateOf(false) }
    LaunchedEffect(session.user.id) {
        runCatching {
            customer.garage = app.customer.assets(); customer.vehicle = customer.garage.firstOrNull()
            customer.savedPlaces = app.customer.places()
            val (la, ln) = where.current(); customer.place = PlaceSel("Current location", "Near you", la, ln)
            customer.favorites = app.customer.favorites(la, ln).map { it.id }.toSet()
        }
        ready = true
        if (customer.garage.isEmpty()) nav.reset(Route.AddVehicle("Step 2 of 3"))
    }

    // Poll for booking updates and show them as notifications (a push service replaces this).
    LaunchedEffect(session.user.id) {
        var known: Set<String>? = null
        while (true) {
            runCatching {
                val items = app.customer.notifications()
                customer.unread = items.count { !it.read }
                val fresh = items.filter { !it.read }.map { it.id }.toSet()
                known?.let { old -> items.filter { !it.read && it.id !in old }.reversed().forEach { platform.notify(it.title, it.body) } }
                known = (known ?: emptySet()) + fresh
                if (known == null) known = fresh
            }
            delay(20_000)
        }
    }
    if (!ready) { Box(Modifier.fillMaxSize().background(Ind.bg), Alignment.Center) { CircularProgressIndicator(color = Ind.accent) }; return }
    LaunchedEffect(Unit) { onReady() }

    val routeContent: @Composable () -> Unit = { when (val r = nav.current) {
        Route.Tabs -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(onOpen = { nav.push(Route.Place(it)) }, onChangePlace = { choosingPlace = true }, onOpenNotifications = { nav.push(Route.Notifications) }, onNeedVehicle = { nav.push(Route.AddVehicle(null)) })
                    1 -> BookingsScreen(onOpen = { nav.push(Route.Booking(it)) }, onRebook = { nav.push(Route.Place(it)) }, onReschedule = { nav.push(Route.Reschedule(it)) }, bottomBar = {})
                    2 -> GarageScreen(onAdd = { nav.push(Route.AddVehicle(null)) }, bottomBar = {})
                    else -> AccountScreen(onNotifications = { nav.push(Route.Notifications) }, onFavorites = { nav.push(Route.Favorites) }, onSignOut = app::logout, bottomBar = {})
                }
            }
            if (!wide) BottomTabs(TABS, tab) { tab = it }
        }
        is Route.Place -> PlaceScreen(r.id, onBack = { nav.pop() }, onPickSlot = { items, variant -> nav.push(Route.Slots(r.id, items, variant)) })
        is Route.Slots -> SlotPickerScreen(r.locationId, r.itemIds, r.variantKey, onBack = { nav.pop() }, onContinue = { nav.push(Route.Checkout(r.locationId, r.itemIds, r.variantKey, it)) })
        is Route.Checkout -> CheckoutScreen(
            r.locationId, r.itemIds, r.variantKey, r.startsAt, customer.vehicle?.id, allowSimulation = allowSimulation,
            onBack = { nav.pop() }, onPaid = { nav.reset(Route.Confirmed(it)) },
        )
        is Route.Confirmed -> ConfirmedScreen(r.order, onClose = { tab = 1; nav.reset(Route.Tabs) })
        is Route.Reschedule -> SlotPickerScreen(
            r.order.locationId, r.order.lines.map { it.itemId }, r.order.variantKey, onBack = { nav.pop() }, heading = "Move your ${LocalTenant.current.config.labels.order.lowercase()}", confirmText = "Move here",
            onContinue = { start ->
                scope.launch {
                    try { app.customer.reschedule(r.order.id, start); nav.reset(Route.Tabs); tab = 1 } catch (e: Exception) { problem = e.friendly() }
                }
            },
        )
        is Route.Booking -> BookingDetailScreen(r.id, onBack = { nav.pop() }, onRate = { nav.push(Route.Rate(it)) }, onReport = { nav.push(Route.Report(it)) })
        is Route.Rate -> RateScreen(r.id, onClose = { nav.pop() }, onReport = { nav.push(Route.Report(r.id)) })
        is Route.Report -> ReportScreen(r.id, onBack = { nav.pop() }, onSent = { nav.pop() })
        is Route.AddVehicle -> LocalVertical.current!!.AssetForm(r.step, onBack = if (r.step == null) ({ nav.pop() }) else null, onSaved = { if (r.step != null) nav.reset(Route.Tabs) else nav.pop() })
        Route.Notifications -> NotificationsScreen(onBack = { nav.pop() }, onOpenOrder = { nav.push(Route.Booking(it)) })
        Route.Favorites -> FavoritesScreen(onBack = { nav.pop() }, onOpen = { nav.push(Route.Place(it)) })
    } }
    if (wide) {
        // Desktop browsers and tablets: a side rail instead of the bottom tabs, and each screen kept to a readable width.
        val onTabs = nav.current == Route.Tabs
        val maxWidth = if (onTabs && tab == 0) 1160.dp else if (onTabs) 860.dp else 760.dp
        Row(Modifier.fillMaxSize().background(Ind.bg)) {
            SideRail(tenantName, TABS, tab, { tab = it; nav.reset(Route.Tabs) })
            Box(Modifier.width(1.dp).fillMaxHeight().background(Ind.divider))
            Box(Modifier.weight(1f).fillMaxHeight()) { CenteredContent(maxWidth, routeContent) }
        }
    } else routeContent()
    if (choosingPlace) PlaceChooser { choosingPlace = false }

    problem?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { problem = null }, containerColor = Ind.bg, title = { Heading("That didn't work", 22) }, text = { Body(msg) },
            confirmButton = { GhostButton("OK", { problem = null }) },
        )
    }
}
