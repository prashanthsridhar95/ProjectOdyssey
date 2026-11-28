package com.odyssey.vendorops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.*
import com.odyssey.auth.LoginMode
import com.odyssey.auth.LoginScreen
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface VRoute {
    data object Tabs : VRoute
    data class Detail(val orderId: String) : VRoute
    data object Scan : VRoute
    data object WalkIn : VRoute
    data class EditItem(val item: Item?) : VRoute
    data object Reviews : VRoute
    data object Outlet : VRoute
    data object Staff : VRoute
    data object Bank : VRoute
    data object OwnerPin : VRoute
    data object Earnings : VRoute
    data object Promotions : VRoute
}

private enum class VTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    STATUS("Status", Lucide.ShieldCheck), BOOKINGS("Bookings", Lucide.Inbox), SLOTS("Slots", Lucide.CalendarDays), MENU("Menu", Lucide.List), EARNINGS("Earnings", Lucide.Wallet), MORE("More", Lucide.Ellipsis)
}

class ProviderViewModel(private val app: AppContainer) : BaseViewModel() {
    val provider = MutableStateFlow<Load<Provider>>(Load.Loading)
    init { reload() }
    fun reload() {
        provider.load {
            try { app.provider.me() } catch (e: com.odyssey.domain.ApiException) {
                // A session for a vendor that no longer exists (or has expired) goes back to sign-in instead of an error.
                if (e.isUnauthorized || e.code == "not_found") app.logout()
                throw e
            }
        }
    }
}

/**
 * The vendor app. Shows the sign-in screen the first time, then the counter lock screen (who is at the counter? PIN),
 * then the day's work. Staff only see bookings and slots; managers add the menu; the owner sees everything, with money behind the Owner PIN.
 */
@Composable
fun VendorApp(tenantName: String) {
    val app = LocalContainer.current
    val session = app.session.collectAsStateValue()
    val locked = app.locked.collectAsStateValue()
    var roster by remember { mutableStateOf(app.roster()) }
    val platform = LocalPlatform.current

    // Remember who can unlock this device: refreshed whenever an owner or manager is signed in.
    LaunchedEffect(session?.user?.id) {
        val u = session?.user ?: return@LaunchedEffect
        if (u.role == Role.PROVIDER_OWNER || u.role == Role.PROVIDER_MANAGER) runCatching {
            app.saveOutlet(app.provider.me().name)
            app.saveRoster(app.provider.staff().map { RosterEntry(it.id, it.name, it.phone, it.role) }); roster = app.roster()
        }
    }
    DisposableEffect(Unit) { onDispose { platform.alertSound(false) } }

    when {
        session == null || session.user.role !in setOf(Role.PROVIDER_OWNER, Role.PROVIDER_MANAGER, Role.PROVIDER_STAFF) -> {
            LaunchedEffect(session) { if (session != null) app.logout() }
            LoginScreen("$tenantName for Business", "Run the counter: bookings, bays and walk-ins.", modes = setOf(LoginMode.OTP, LoginMode.STAFF_PIN), showHero = false, onSignedIn = { app.locked.value = false })
        }
        locked && roster.isNotEmpty() -> LockScreen(roster, app.lockoutName() ?: tenantName) { app.locked.value = false }
        else -> VendorHome(session.user)
    }
}

// ---- Lock screen ----

@Composable
private fun LockScreen(roster: List<RosterEntry>, outlet: String, onUnlocked: () -> Unit) {
    val app = LocalContainer.current
    var who by remember { mutableStateOf(roster.first()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val length = if (who.role == Role.PROVIDER_OWNER) 6 else 4

    fun press(d: String) {
        if (busy || pin.length >= length) return
        pin += d; error = null
        if (pin.length == length) {
            busy = true
            scope.launch {
                try { app.signInWithPin(who.phone, pin); pin = ""; onUnlocked() }
                catch (e: Exception) { error = e.friendly(); pin = "" } finally { busy = false }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Caps(outlet); Heading("Who's at the counter?", 32) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            roster.forEach { p ->
                val on = p.id == who.id
                Column(
                    Modifier.width(112.dp).then(if (on) Modifier.blueprint(fill = Ind.a100, line = Ind.accent) else Modifier.hairline()).clickable { who = p; pin = ""; error = null }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(44.dp).background(if (on) Ind.accent else Ind.n200, CircleShape), Alignment.Center) { Body(p.name.split(" ").filter { it.isNotBlank() }.let { w -> (w.first().take(1) + if (w.size > 1) w.last().take(1) else "") }.uppercase(), weight = FontWeight.SemiBold, color = if (on) Ind.bg else Ind.text) }
                    androidx.compose.material3.Text(p.name, Modifier.padding(horizontal = 6.dp), fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Ind.text)
                    Body(p.role.name.removePrefix("PROVIDER_").titleCase(), size = 11, color = if (on) Ind.a800 else Ind.muted)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Body("Enter your $length-digit PIN", size = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(length) { i -> Box(Modifier.size(14.dp).then(if (i < pin.length) Modifier.background(Ind.accent, CircleShape) else Modifier.border(1.5.dp, Ind.mutedSoft, CircleShape))) }
            }
            Body(error ?: " ", size = 13, color = Ind.danger)
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Forgot", "0", "⌫")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).height(60.dp).then(if (k == "Forgot") Modifier else Modifier.hairline()).clickable { when (k) { "Forgot" -> forgot = true; "⌫" -> pin = pin.dropLast(1); else -> press(k) } }, Alignment.Center) {
                            if (k == "Forgot") Body("Forgot", size = 13, color = Ind.a700) else if (k == "⌫") Icon(Lucide.Delete, null, Modifier.size(24.dp)) else Heading(k, 26)
                        }
                    }
                }
            }
        }
    }
    if (forgot) ConfirmDialog(
        "Forgot your PIN?", if (who.role == Role.PROVIDER_OWNER) "This signs the device out. Sign in again with your phone and a one-time code, then set a new Owner PIN under More." else "Ask the owner to set a new PIN for you. If you sign the device out, an owner or manager has to sign in again before anyone can use it.",
        "Sign the device out", { forgot = false; app.logout() }, { forgot = false }, danger = true,
    )
}

// ---- Home with tabs, routes and the accept alert ----

@Composable
private fun VendorHome(user: User) {
    val app = LocalContainer.current
    val vm = screenModel("provider") { ProviderViewModel(app) }
    val state = vm.provider.collectAsStateValue()
    val nav = remember { Navigator<VRoute>(VRoute.Tabs) }
    val platform = LocalPlatform.current
    val owner = user.role == Role.PROVIDER_OWNER
    val manager = owner || user.role == Role.PROVIDER_MANAGER
    var tab by remember { mutableStateOf(VTab.BOOKINGS) }
    var alert by remember { mutableStateOf<Order?>(null) }
    var silenced by remember { mutableStateOf(setOf<String>()) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val report: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }

    Remote(state, vm::reload) { p ->
        val live = p.status == "LIVE"
        // Five tabs at most: before going live, Status takes the Earnings spot and earnings move under More.
        val tabs = VTab.entries.filter { (it == VTab.STATUS && !live) || it == VTab.BOOKINGS || it == VTab.SLOTS || (it == VTab.MENU && manager) || (it == VTab.EARNINGS && owner && live) || it == VTab.MORE }
        var unreplied by remember { mutableStateOf(0) }
        LaunchedEffect(p.id) { unreplied = runCatching { app.provider.reviews().count { it.reply == null } }.getOrDefault(0) }
        val loc = p.locations.firstOrNull()
        LaunchedEffect(live) { if (!live) tab = VTab.STATUS }

        // Look for paid bookings waiting to be accepted (manual-accept outlets) and sound the alert.
        LaunchedEffect(loc?.id) {
            while (loc != null) {
                runCatching { app.providerDay.flush() }
                val waiting = runCatching { app.providerDay.bookings(istDate(), loc.id) }.getOrDefault(emptyList()).firstOrNull { "ACCEPT" in it.actions && it.id !in silenced }
                if (waiting != null && alert?.id != waiting.id) { alert = waiting; platform.alertSound(true) }
                delay(12_000)
            }
        }

        when (val r = nav.current) {
            VRoute.Tabs -> Column(Modifier.fillMaxSize().background(Ind.bg)) {
                Box(Modifier.weight(1f)) {
                    if (loc == null) EmptyNote("No outlet yet.") else when (tab) {
                        VTab.STATUS -> OnboardingScreen(p, onMenu = { tab = VTab.MENU }, onStaff = { if (owner) nav.push(VRoute.Staff) else report("Ask the owner to add staff.") }, onBank = { if (owner) nav.push(VRoute.Bank) else report("Ask the owner to add the bank account.") }, onRefresh = vm::reload)
                        VTab.BOOKINGS -> InboxScreen(p, loc, user, report, onOpen = { nav.push(VRoute.Detail(it)) }, onWalkIn = { nav.push(VRoute.WalkIn) }, onScan = { nav.push(VRoute.Scan) }, onLock = { app.locked.value = true })
                        VTab.SLOTS -> CalendarScreen(loc, report, onWalkIn = { nav.push(VRoute.WalkIn) })
                        VTab.MENU -> MenuScreen(loc.id, owner, onEdit = { nav.push(VRoute.EditItem(it)) })
                        VTab.EARNINGS -> EarningsScreen(report)
                        VTab.MORE -> MoreScreen(p, user, unreplied, onEarnings = if (owner && !live) ({ nav.push(VRoute.Earnings) }) else null, onReviews = { nav.push(VRoute.Reviews) },
                            onOutlet = { nav.push(VRoute.Outlet) }, onStaff = { nav.push(VRoute.Staff) }, onBank = { nav.push(VRoute.Bank) }, onPin = { nav.push(VRoute.OwnerPin) }, onLock = { app.locked.value = true }, onPromotions = { nav.push(VRoute.Promotions) })
                    }
                }
                IndSnackbarHost(snack)
                BottomTabs(tabs.map { TabItem(it.label, it.icon) }, tabs.indexOf(tab).coerceAtLeast(0)) { tab = tabs[it] }
            }
            is VRoute.Detail -> BookingDetailScreen(r.orderId, onBack = { nav.pop() }, report = report)
            VRoute.Scan -> ScanScreen(onClose = { nav.pop() }, onFound = { nav.replace(VRoute.Detail(it)) })
            VRoute.WalkIn -> loc?.let { WalkInScreen(it, onClose = { nav.pop() }, onDone = { nav.replace(VRoute.Detail(it)) }) }
            is VRoute.EditItem -> loc?.let { EditItemScreen(it.id, r.item, owner, onBack = { nav.pop() }) }
            VRoute.Reviews -> ReviewsScreen(onBack = { nav.pop() }, report = report, onChanged = { unreplied = (unreplied - 1).coerceAtLeast(0) })
            VRoute.Outlet -> loc?.let { OutletSettingsScreen(it, onBack = { nav.pop() }, onSaved = vm::reload, report = report) }
            VRoute.Staff -> StaffScreen(onBack = { nav.pop() }, report = report, onChanged = vm::reload)
            VRoute.Bank -> BankScreen(p, onBack = { nav.pop() }, onSaved = vm::reload, report = report)
            VRoute.OwnerPin -> OwnerPinScreen(onBack = { nav.pop() }, report = report)
            VRoute.Promotions -> PromotionsScreen(p, user, onBack = { nav.pop() }, report = report)
            VRoute.Earnings -> Column(Modifier.fillMaxSize()) { Row(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp)) { Icon(Lucide.ArrowLeft, "Back", Modifier.size(24.dp).clickable { nav.pop() }) }; Box(Modifier.weight(1f)) { EarningsScreen(report) } }
        }
        if (nav.current != VRoute.Tabs) IndSnackbarHost(snack)

        alert?.let { o ->
            AcceptAlert(o, onDone = { platform.alertSound(false); silenced = silenced + o.id; alert = null }, report)
        }
    }
}

// ---- New booking alert (manual accept) ----

@Composable
private fun AcceptAlert(o: Order, onDone: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var left by remember { mutableStateOf(0) }
    var silent by remember { mutableStateOf(false) }
    var reject by remember { mutableStateOf(false) }
    LaunchedEffect(o.id) {
        while (true) {
            left = o.acceptBy?.toInstantOrNull()?.let { (it - kotlin.time.Clock.System.now()).inWholeSeconds.toInt() } ?: 0
            if (left <= 0) { onDone(); break }
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Ind.accent).clickable { silent = true; platform.alertSound(false) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Lucide.BellRing, null, Modifier.size(20.dp), tint = Ind.bg); Body(if (silent) "Silenced" else "Alert sounding · tap to silence", color = Ind.bg, weight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading("New booking", 32)
                Heading(formatTime(o.startsAt), 40, color = Ind.a700)
                Body("${formatDay(o.startsAt)} · ${durationText(o)} · 1 bay", color = Ind.muted)
            }
            Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled("Vehicle", "${o.variantKey.titleCase()} · ${o.assetLabel ?: "-"}"); Labeled("Services", o.lines.joinToString { it.name }); Labeled("Customer", o.customerName)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
                Labeled("You earn", formatMoney(o.subtotalMinor - (o.commissionMinor ?: 0)), strong = true)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Body("Auto-rejects in", size = 12, color = Ind.muted); Heading("${left / 60}:${(left % 60).toString().padStart(2, '0')}", 40)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Reject", { reject = true }, Modifier.weight(1f).height(56.dp), danger = true)
            Box(Modifier.weight(2f)) { PrimaryButton("Accept booking", { scope.launch { try { app.providerDay.act(o.id, "ACCEPT", null); onDone() } catch (e: Exception) { report(e.friendly()) } } }, height = 56) }
        }
    }
    if (reject) ConfirmDialog("Reject ${displayCode(o.code)}?", "${o.customerName} is refunded ${formatMoney(o.totalMinor)} in full and told straight away. Rejections count against your cancellation rate.", "Reject", reasonLabel = "Reason (the customer sees this)", danger = true, onConfirm = { reason -> reject = false; scope.launch { try { app.providerDay.act(o.id, "REJECT", reason); onDone() } catch (e: Exception) { report(e.friendly()) } } }, onDismiss = { reject = false })
}

fun durationText(o: Order): String {
    val s = o.startsAt.toInstantOrNull(); val e = o.endsAt.toInstantOrNull()
    return if (s != null && e != null) "${(e - s).inWholeMinutes} min" else ""
}

// ---- Onboarding status ----

@Composable
private fun OnboardingScreen(p: Provider, onMenu: () -> Unit, onStaff: () -> Unit, onBank: () -> Unit, onRefresh: () -> Unit) {
    val app = LocalContainer.current
    var menuCount by remember { mutableStateOf<Int?>(null) }
    var staffCount by remember { mutableStateOf(0) }
    LaunchedEffect(p.id) {
        menuCount = p.locations.firstOrNull()?.let { runCatching { app.provider.items(it.id).size }.getOrNull() }
        staffCount = runCatching { app.provider.staff().size }.getOrDefault(0)
    }
    val steps = listOf("SUBMITTED" to "Documents submitted", "UNDER_REVIEW" to "Under review", "APPROVED" to "Approved", "LIVE" to "Live")
    val order = listOf("SUBMITTED", "UNDER_REVIEW", "CHANGES_REQUESTED", "APPROVED", "LIVE")
    val at = order.indexOf(p.status).coerceAtLeast(0)
    val rank = mapOf("SUBMITTED" to 0, "UNDER_REVIEW" to 1, "CHANGES_REQUESTED" to 1, "APPROVED" to 2, "LIVE" to 3)
    val current = rank[p.status] ?: 0
    Screen("Status", scroll = true) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(p.status.titleCase(), if (p.status == "SUSPENDED" || p.status == "REJECTED") TagKind.DANGER else TagKind.ACCENT)
            Heading(when (p.status) { "CHANGES_REQUESTED" -> "We need a few changes"; "APPROVED" -> "You're approved"; "SUSPENDED" -> "Your listing is paused"; "REJECTED" -> "Not approved"; else -> "We're checking your documents" }, 32)
            Body(p.reviewNote ?: when (p.status) { "APPROVED" -> "The platform team will take you live shortly."; "SUSPENDED" -> "Customers cannot book you. Contact the platform team."; else -> "Usually done in 2 working days. You can set up your menu and slots meanwhile." }, size = 14, color = Ind.muted)
        }
        Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            steps.forEachIndexed { i, (_, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (i < current) Lucide.Check else Lucide.Circle, null, Modifier.size(18.dp), tint = if (i <= current) Ind.a700 else Ind.mutedSoft)
                    Column { Body(label, weight = if (i == current) FontWeight.SemiBold else FontWeight.Normal, color = if (i <= current) Ind.text else Ind.mutedSoft); if (i == 0) Body(p.documents.joinToString(", ") { it.type.titleCase() }.ifBlank { "No documents yet" }, size = 12, color = Ind.muted) }
                }
            }
        }
        if (p.documents.any { it.state == "NEEDS_FIX" }) Section("To fix") { p.documents.filter { it.state == "NEEDS_FIX" }.forEach { d -> ErrorBox("${d.type.titleCase()}: ${d.note ?: "Please upload it again."}") } }
        Section("Get ready to go live") {
            val loc = p.locations.firstOrNull()
            Check("Outlet address and hours", loc != null && loc.address.isNotBlank())
            Check("Bays and slot length", loc != null && loc.capacity > 0)
            Check("Add menu and prices", (menuCount ?: 0) > 0, onMenu)
            Check("Bank account for payouts", p.bank != null, onBank)
            Check("Add staff and PINs", staffCount > 1, onStaff)
        }
        GhostButton("Refresh status", onRefresh)
    }
}

@Composable
private fun Check(label: String, done: Boolean, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().hairline().then(if (!done && onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (done) Lucide.Check else Lucide.Circle, null, Modifier.size(18.dp), tint = if (done) Ind.a700 else Ind.mutedSoft)
        Body(label, modifier = Modifier.weight(1f), color = if (done) Ind.muted else Ind.text)
        if (!done && onClick != null) Icon(Lucide.ChevronRight, null, Modifier.size(18.dp))
    }
}
