package com.odyssey.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
    var removing by remember { mutableStateOf<Asset?>(null) }
    Screen(
        "Your garage", bottomBar = bottomBar,
        footer = { if (vertical != null) PrimaryButton("Add a ${vertical.assetNoun.lowercase()}", onAdd) },
    ) {
        if (customer.garage.isEmpty()) EmptyState(Lucide.Car, "No ${vertical?.assetNoun?.lowercase() ?: "item"}s saved yet", "Save one so prices match it and the counter knows what to expect.")
        customer.garage.forEach { a ->
            val on = customer.vehicle?.id == a.id
            Row(Modifier.fillMaxWidth().then(if (on) Modifier.background(Ind.a100, Ind.rSm).hairline(Ind.accent) else Modifier.hairline()).clickable { customer.vehicle = a }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(vertical?.iconFor(a) ?: Lucide.Car, null, Modifier.size(24.dp), tint = if (on) Ind.a800 else Ind.text)
                Column(Modifier.weight(1f)) { Body(a.label, weight = FontWeight.Medium); if (on) Body("Used for prices", size = 12, color = Ind.a800) }
                GhostButton("Remove", { removing = a })
            }
        }
        error?.let { ErrorBox(it) }
    }
    removing?.let { a ->
        ConfirmDialog("Remove ${a.label}?", "Bookings already made for it are not affected. You can add it again any time.", "Remove", {
            removing = null
            scope.launch { try { app.customer.deleteAsset(a.id); customer.garage = customer.garage - a; if (customer.vehicle?.id == a.id) customer.vehicle = customer.garage.firstOrNull() } catch (e: Exception) { error = e.friendly() } }
        }, { removing = null }, danger = true)
    }
}

// ---- Account ----

@Composable
fun AccountScreen(onNotifications: () -> Unit, onFavorites: () -> Unit, onSignOut: () -> Unit, bottomBar: @Composable () -> Unit, onRefer: (() -> Unit)? = null) {
    val app = LocalContainer.current
    val customer = LocalCustomer.current
    val user = app.session.collectAsStateValue()?.user
    val scope = rememberCoroutineScope()
    var addPlace by remember { mutableStateOf<String?>(null) }
    var removePlace by remember { mutableStateOf<SavedPlace?>(null) }
    var signOut by remember { mutableStateOf(false) }
    Screen("Account", bottomBar = bottomBar) {
        Row(Modifier.fillMaxWidth().hairline().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Ind.a100, CircleShape), Alignment.Center) { Heading(initialsOf(user?.name ?: "?"), 18, color = Ind.a800) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Heading(user?.name ?: "", 22); Body(user?.phone?.let(::formatPhone) ?: "", color = Ind.muted) }
        }
        Section("Saved places") {
            if (customer.savedPlaces.isEmpty()) Body("Save Home and Work to search from them quickly.", size = 13, color = Ind.muted)
            customer.savedPlaces.forEach { p ->
                Row(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (p.label == "Work") Lucide.Briefcase else Lucide.House, null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) { Body(p.label, weight = FontWeight.Medium); Body(p.address, size = 12, color = Ind.muted) }
                    GhostButton("Remove", { removePlace = p })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Home", "Work").filter { l -> customer.savedPlaces.none { it.label == l } }.forEach { l -> Box(Modifier.clickable { addPlace = l }) { Tag("Add $l", TagKind.OUTLINE, if (l == "Work") Lucide.Briefcase else Lucide.House) } }
            }
        }
        LinkRow("Notifications", Lucide.Bell, badge = customer.unread.takeIf { it > 0 }?.toString(), onClick = onNotifications)
        LinkRow("Favourites", Lucide.Heart, onClick = onFavorites)
        onRefer?.let { LinkRow("Refer a friend", Lucide.Gift, onClick = it) }
        SecondaryButton("Sign out", { signOut = true }, Modifier.fillMaxWidth(), danger = true)
        Body("Need help with a booking? Open it and tap Report a problem. We reply within 24 hours.", size = 12, color = Ind.muted)
    }
    removePlace?.let { p -> ConfirmDialog("Remove ${p.label}?", "You can save it again any time.", "Remove", { removePlace = null; scope.launch { runCatching { app.customer.deletePlace(p.label) }; customer.savedPlaces = customer.savedPlaces - p } }, { removePlace = null }) }
    if (signOut) ConfirmDialog("Sign out?", "Your bookings and garage stay saved. You will need a new code to sign in again.", "Sign out", { signOut = false; onSignOut() }, { signOut = false })
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

// ---- Refer a friend ----

data class ReferState(val referral: Load<Referral> = Load.Loading, val vouchers: List<Voucher> = emptyList(), val friendCode: String = "", val busy: Boolean = false, val message: String? = null, val error: String? = null)

class ReferViewModel(private val app: AppContainer) : BaseViewModel() {
    val state = MutableStateFlow(ReferState())
    private val loaded = MutableStateFlow<Load<Referral>>(Load.Loading)
    init { run { loaded.collect { state.value = state.value.copy(referral = it) } }; reload() }
    fun reload() {
        loaded.load { app.customer.referral() }
        viewModelScope.launch { runCatching { app.customer.vouchers() }.onSuccess { state.value = state.value.copy(vouchers = it) } }
    }
    fun friendCode(v: String) { state.value = state.value.copy(friendCode = v.uppercase().filter { it.isLetterOrDigit() }, error = null) }
    fun redeem() {
        val code = state.value.friendCode
        if (code.isBlank() || state.value.busy) return
        state.value = state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val v = app.customer.redeemReferral(code)
                state.value = state.value.copy(busy = false, friendCode = "", message = "Added ${v.code}: ${v.description.substringAfter(": ")}. It is applied when you pick it at checkout.")
                reload()
            } catch (e: Exception) { state.value = state.value.copy(busy = false, error = e.friendly()) }
        }
    }
}

/** The customer's own code to share, what friends get, what they earn, and their vouchers. */
@Composable
fun ReferScreen(onBack: () -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val tenant = LocalTenant.current
    val vm = screenModel("refer") { ReferViewModel(app) }
    val s = vm.state.collectAsStateValue()
    Screen("Refer a friend", onBack = onBack) {
        Remote(s.referral, vm::reload) { r ->
            if (!r.enabled) EmptyState(Lucide.Gift, "Referrals are paused", "Vouchers you already have still work. Check back soon.")
            else {
                Heading("Give ${r.welcomeText}, get ${r.rewardText}", 26)
                Body("Your friend gets ${r.welcomeText} on their first booking with your code. You get ${r.rewardText} once they have finished it.", size = 14, color = Ind.muted)
                Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Body("Your code", size = 12, color = Ind.muted)
                    Heading(r.code, 34)
                    PrimaryButton("Share your code", { platform.shareText("${tenant.tenant.name}: a gift for your first booking", r.shareText) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Joined" to r.invited, "Rewarded" to r.rewarded, "Waiting" to r.pending).forEach { (label, n) ->
                        Column(Modifier.weight(1f).hairline().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Heading(n.toString(), 22); Body(label, size = 12, color = Ind.muted) }
                    }
                }
                if (r.pending > 0) Body("Friends who are waiting have joined but not finished their first booking yet.", size = 12, color = Ind.muted)
            }
            Section("Your vouchers") {
                if (s.vouchers.isEmpty()) Body("Vouchers you earn show here. Pick one at checkout to use it.", size = 13, color = Ind.muted)
                s.vouchers.forEach { v ->
                    Row(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Lucide.Ticket, null, Modifier.size(20.dp), tint = Ind.a700)
                        Column(Modifier.weight(1f)) { Body(v.description.substringAfter(": ").ifBlank { v.code }, weight = FontWeight.Medium); Body("${v.code}${v.expiresAt?.let { " · use by ${formatDay(it)}" } ?: ""}", size = 12, color = Ind.muted) }
                    }
                }
            }
            if (r.enabled && !r.usedFriendCode) Section("Got a code from a friend?") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Field("", s.friendCode, vm::friendCode, Modifier.weight(1f), placeholder = "Friend's code")
                    SecondaryButton(if (s.busy) "Adding…" else "Add", vm::redeem, enabled = s.friendCode.isNotBlank() && !s.busy)
                }
                Body("Only for your first booking. You can also type it at checkout.", size = 12, color = Ind.muted)
            }
            s.error?.let { ErrorBox(it) }
            s.message?.let { Body(it, color = Ind.a700, weight = FontWeight.Medium) }
        }
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
            if (items.isEmpty()) EmptyState(Lucide.Bell, "No notifications yet", "Updates about your bookings show up here.")
            items.forEach { n ->
                Row(Modifier.fillMaxWidth().hairline().clickable { n.orderId?.let(onOpenOrder) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(Ind.accent, Ind.rSm), Alignment.Center) { Heading("R", 16, color = Ind.bg) }
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
            if (places.isEmpty()) EmptyState(Lucide.Heart, "No favourites yet", "Tap the heart on a place to keep it here.")
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
