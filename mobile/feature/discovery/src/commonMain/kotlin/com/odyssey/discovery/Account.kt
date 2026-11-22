package com.odyssey.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ---- Garage: the customer's saved vehicles ----

@Composable
fun GarageScreen(onAdd: () -> Unit, bottomBar: @Composable () -> Unit) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val vertical = LocalVertical.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Screen(
        "Your garage", bottomBar = bottomBar,
        footer = { if (vertical != null) PrimaryButton("Add a ${vertical.assetNoun.lowercase()}", onAdd) },
    ) {
        if (customer.garage.isEmpty()) EmptyNote("No ${vertical?.assetNoun?.lowercase() ?: "item"}s saved yet.")
        customer.garage.forEach { a ->
            val on = customer.vehicle?.id == a.id
            Row(Modifier.fillMaxWidth().then(if (on) Modifier.background(Ind.a100).hairline(Ind.accent) else Modifier.hairline()).clickable { customer.vehicle = a }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(vertical?.iconFor(a) ?: Lucide.Car, null, Modifier.size(24.dp), tint = if (on) Ind.a800 else Ind.text)
                Column(Modifier.weight(1f)) { Body(a.label, weight = FontWeight.Medium); if (on) Body("Used for prices", size = 12, color = Ind.a800) }
                GhostButton("Remove", { scope.launch { try { app.customer.deleteAsset(a.id); customer.garage = customer.garage - a; if (customer.vehicle?.id == a.id) customer.vehicle = customer.garage.firstOrNull() } catch (e: Exception) { error = e.friendly() } } })
            }
        }
        error?.let { ErrorBox(it) }
    }
}

// ---- Account ----

@Composable
fun AccountScreen(onNotifications: () -> Unit, onFavorites: () -> Unit, onSignOut: () -> Unit, bottomBar: @Composable () -> Unit) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val user = app.session.collectAsStateValue()?.user
    val scope = rememberCoroutineScope()
    var addPlace by remember { mutableStateOf<String?>(null) }
    Screen("Account", bottomBar = bottomBar) {
        Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Heading(user?.name ?: "", 22); Body(user?.phone ?: "", color = Ind.muted) }
        Section("Saved places") {
            if (customer.savedPlaces.isEmpty()) Body("Save Home and Work to search from them quickly.", size = 13, color = Ind.muted)
            customer.savedPlaces.forEach { p ->
                Row(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (p.label == "Work") Lucide.Briefcase else Lucide.House, null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) { Body(p.label, weight = FontWeight.Medium); Body(p.address, size = 12, color = Ind.muted) }
                    GhostButton("Remove", { scope.launch { runCatching { app.customer.deletePlace(p.label) }; customer.savedPlaces = customer.savedPlaces - p } })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Home", "Work").filter { l -> customer.savedPlaces.none { it.label == l } }.forEach { l -> Box(Modifier.clickable { addPlace = l }) { Tag("Add $l", TagKind.OUTLINE, if (l == "Work") Lucide.Briefcase else Lucide.House) } }
            }
        }
        LinkRow("Notifications", Lucide.Bell, badge = customer.unread.takeIf { it > 0 }?.toString(), onClick = onNotifications)
        LinkRow("Favourites", Lucide.Heart, onClick = onFavorites)
        SecondaryButton("Sign out", onSignOut, Modifier.fillMaxWidth(), danger = true)
    }
    addPlace?.let { label ->
        var address by remember(label) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addPlace = null }, containerColor = Ind.bg, title = { Heading("Save $label", 22) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("Address", address, { address = it }); Body("Uses your current search location as the point.", size = 12, color = Ind.muted) } },
            confirmButton = { PrimaryButton("Save", { scope.launch { runCatching { customer.savedPlaces = app.customer.savePlace(SavedPlace(label, address.trim(), customer.place.lat, customer.place.lng)) }; addPlace = null } }, enabled = address.isNotBlank(), height = 44) },
            dismissButton = { GhostButton("Cancel", { addPlace = null }) },
        )
    }
}

@Composable
private fun LinkRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().hairline().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = Ind.a700); Body(label, size = 15, modifier = Modifier.weight(1f))
        if (badge != null) Tag(badge, TagKind.SOLID)
        Icon(Lucide.ChevronRight, null, Modifier.size(18.dp))
    }
}

// ---- Notifications ----

class NotificationsViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<AppNotification>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.customer.notifications() } }
    fun markRead(done: () -> Unit) = run { app.customer.markNotificationsRead(); done() }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenOrder: (String) -> Unit) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val vm = screenModel("notifications") { NotificationsViewModel(app) }
    val s = vm.list.collectAsStateValue()
    LaunchedEffect(Unit) { vm.markRead { customer.unread = 0 } }
    Screen("Notifications", onBack = onBack) {
        Remote(s, vm::reload) { items ->
            if (items.isEmpty()) EmptyNote("Nothing yet. Updates about your bookings show up here.")
            items.forEach { n ->
                Row(Modifier.fillMaxWidth().hairline().clickable { n.orderId?.let(onOpenOrder) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(Ind.accent), Alignment.Center) { Heading("R", 16, color = Ind.bg) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row { Body(n.title, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Body(formatTime(n.createdAt), size = 12, color = Ind.muted) }
                        Body(n.body, size = 13, color = Ind.muted)
                    }
                }
            }
        }
    }
}

// ---- Favourites ----

class FavoritesViewModel(private val app: AppContainer, private val where: PlaceSel) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<LocationSummary>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.customer.favorites(where.lat, where.lng) } }
}

@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val vm = screenModel("favorites") { FavoritesViewModel(app, customer.place) }
    val s = vm.list.collectAsStateValue()
    Screen("Favourites", onBack = onBack) {
        Remote(s, vm::reload) { places ->
            if (places.isEmpty()) EmptyNote("Tap the heart on a place to keep it here.")
            places.forEach { PlaceCard(it) { onOpen(it.id) } }
        }
    }
}

// ---- Where to look ----

@Composable
fun PlaceChooser(onDismiss: () -> Unit) {
    val customer = LocalCustomer.current
    val where = LocalLocation.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ind.bg, title = { Heading("Where should we look?", 22) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp).hairline(Ind.accent).clickable {
                    scope.launch { val (la, ln) = where.current(); customer.place = PlaceSel("Current location", "Near you", la, ln); onDismiss() }
                }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Lucide.LocateFixed, null, Modifier.size(18.dp), tint = Ind.a800); Body("Use current location", color = Ind.a800) }
                customer.savedPlaces.forEach { p ->
                    Row(Modifier.fillMaxWidth().hairline().clickable { customer.place = PlaceSel(p.label, p.address, p.lat, p.lng); onDismiss() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (p.label == "Work") Lucide.Briefcase else Lucide.House, null, Modifier.size(18.dp)); Column { Body(p.label, weight = FontWeight.Medium); Body(p.address, size = 12, color = Ind.muted) }
                    }
                }
                if (customer.savedPlaces.isEmpty()) Body("Save Home or Work from the Account tab.", size = 12, color = Ind.muted)
            }
        },
        confirmButton = {}, dismissButton = { GhostButton("Close", onDismiss) },
    )
}
