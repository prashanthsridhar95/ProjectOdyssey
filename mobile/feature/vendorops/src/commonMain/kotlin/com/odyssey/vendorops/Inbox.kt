package com.odyssey.vendorops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val ACTION_LABEL = mapOf(
    "ACCEPT" to "Accept", "REJECT" to "Reject", "CHECK_IN" to "Check in", "START" to "Start wash", "COMPLETE" to "Complete",
    "NO_SHOW" to "Mark no-show", "CANCEL_BY_PROVIDER" to "Cancel",
)
private val NEEDS_REASON = setOf("REJECT", "CANCEL_BY_PROVIDER")
private val PRIMARY = listOf("ACCEPT", "CHECK_IN", "START", "COMPLETE")

private enum class Group(val label: String) { NEW("New"), UPCOMING("Upcoming"), IN_BAY("In bay"), DONE("Done") }

private fun groupOf(o: Order) = when (o.status) {
    "PAID" -> Group.NEW
    "CONFIRMED" -> Group.UPCOMING
    "CHECKED_IN", "IN_PROGRESS" -> Group.IN_BAY
    else -> Group.DONE
}

fun vehicleText(o: Order) = "${o.variantKey.titleCase()} · ${o.lines.joinToString { it.name }}"

// ---- Inbox ----

class InboxViewModel(private val app: AppContainer, private val locationId: String) : BaseViewModel() {
    val orders = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    val queued = MutableStateFlow(0)
    fun reload() {
        orders.load { app.providerDay.flush(); queued.value = app.providerDay.queued(); app.providerDay.bookings(istDate(), locationId) }
    }
    /** Runs a counter action; offline it is queued and the screen says so. */
    fun act(o: Order, action: String, note: String?, report: (String) -> Unit) = run(report) {
        val done = app.providerDay.act(o.id, action, note)
        queued.value = app.providerDay.queued()
        if (done == null) report("Offline. This will sync when you're back online.")
        reload()
    }
}

@Composable
fun InboxScreen(
    p: Provider, loc: ProviderLocation, user: User, report: (String) -> Unit,
    onOpen: (String) -> Unit, onWalkIn: () -> Unit, onScan: () -> Unit, onLock: () -> Unit,
) {
    val app = LocalContainer.current
    val vm = screenModel("inbox-${loc.id}") { InboxViewModel(app, loc.id) }
    val state = vm.orders.collectAsStateValue()
    val queued = vm.queued.collectAsStateValue()
    var tab by remember { mutableStateOf(0) }
    var autoAccept by remember(loc.id) { mutableStateOf(loc.autoAccept) }
    var reason by remember { mutableStateOf<Pair<Order, String>?>(null) }
    LaunchedEffect(loc.id) { while (true) { vm.reload(); delay(12_000) } }
    val canChange = user.role != Role.PROVIDER_STAFF

    val all = (state as? Load.Ready)?.value ?: emptyList()
    val dead = setOf("PENDING_PAYMENT", "EXPIRED")
    val today = all.filter { it.status !in dead }
    val kept = today.filter { it.status !in setOf("CANCELLED", "REJECTED") }
    val groups = Group.entries.associateWith { g -> today.filter { groupOf(it) == g }.sortedBy { it.startsAt } }
    val now = kotlin.time.Clock.System.now()
    val busyNow = kept.count { (it.startsAt.toInstantOrNull() ?: now) <= now && (it.endsAt.toInstantOrNull() ?: now) > now }
    val capacityMinutes = (clockMinutes(loc.closeTime) - clockMinutes(loc.openTime)) * loc.capacity
    val busyMinutes = kept.sumOf { ((it.endsAt.toInstantOrNull()!! - it.startsAt.toInstantOrNull()!!).inWholeMinutes).toInt() }
    val filled = if (capacityMinutes > 0) (busyMinutes * 100 / capacityMinutes).coerceIn(0, 100) else 0

    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Heading("Today", 30)
                Body("${loc.name} · ${user.name.substringBefore(" ")} (${user.role.name.removePrefix("PROVIDER_").titleCase()})", size = 12, color = Ind.muted)
            }
            Box(Modifier.clickable(enabled = canChange) { autoAccept = !autoAccept; vm.viewModelScope.launch { runCatching { app.provider.setAutoAccept(loc.id, autoAccept) }.onFailure { autoAccept = !autoAccept; report(it.friendly()) } } }) { Tag("Auto-accept ${if (autoAccept) "on" else "off"}", if (autoAccept) TagKind.ACCENT else TagKind.OUTLINE) }
            Spacer(Modifier.width(8.dp))
            IconBox(Lucide.Lock, onClick = onLock)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).hairline()) {
            listOf(kept.size.toString() to "Booked", "$busyNow / ${loc.capacity}" to "Bays busy", "$filled%" to "Slots filled").forEachIndexed { i, (v, l) ->
                Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 14.dp)) { Heading(v, 26); Body(l, size = 12, color = Ind.muted) }
                if (i < 2) Box(Modifier.width(1.dp).height(64.dp).background(Ind.divider))
            }
        }
        if (queued > 0) Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 0.dp).background(Ind.a100).hairline(Ind.accent).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.CloudOff, null, Modifier.size(18.dp), tint = Ind.a800); Body("Offline. $queued action${if (queued > 1) "s" else ""} will sync when you're back online.", size = 13, color = Ind.a800)
        }
        Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp)) {
            Seg(Group.entries.map { "${it.label} ${groups[it]!!.size}" }, tab, Modifier.fillMaxWidth()) { tab = it }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Remote(state, vm::reload) {
                val rows = groups[Group.entries[tab]]!!
                if (rows.isEmpty()) EmptyNote("Nothing here.")
                rows.forEach { o -> BookingRow(o, onOpen, onAct = { a -> if (a in NEEDS_REASON) reason = o to a else vm.act(o, a, null, report) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 14.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton("Walk-in", onWalkIn, Modifier.fillMaxWidth().height(48.dp), icon = Lucide.UserPlus) }
            Box(Modifier.weight(1f)) { SecondaryButton("Scan to check in", onScan, Modifier.fillMaxWidth().height(48.dp), icon = Lucide.ScanLine) }
        }
    }
    reason?.let { (o, a) -> ReasonDialog("${ACTION_LABEL[a]} ${displayCode(o.code)}", "Reason (shown to the customer)", { r -> reason = null; vm.act(o, a, r, report) }, { reason = null }) }
}

private fun clockMinutes(hhmm: String): Int = (hhmm.take(2).toIntOrNull() ?: 0) * 60 + (hhmm.drop(3).take(2).toIntOrNull() ?: 0)

@Composable
private fun BookingRow(o: Order, onOpen: (String) -> Unit, onAct: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(6.dp).blueprint(marks = false).clickable { onOpen(o.id) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(60.dp)) { Heading(formatTime(o.startsAt).removeSuffix(" am").removeSuffix(" pm"), 20); Body(durationText(o), size = 12, color = Ind.muted) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Body(o.assetLabel ?: o.customerName, size = 15, weight = FontWeight.SemiBold)
                Body(vehicleText(o), size = 12, color = Ind.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Body(o.customerName, size = 12); Tag(if (o.source == "WALK_IN") "Walk-in" else "App", TagKind.NEUTRAL) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge(o.statusLabel, o.status); o.bay?.let { Body("Bay $it", size = 12, color = Ind.muted) }
            }
        }
        val actions = o.actions.filter { it in ACTION_LABEL }
        if (actions.isNotEmpty() && o.status in setOf("PAID", "CONFIRMED", "CHECKED_IN", "IN_PROGRESS")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.sortedBy { if (it in PRIMARY) 0 else 1 }.forEach { a ->
                    if (a in PRIMARY) Box(Modifier.weight(1f)) { PrimaryButton(ACTION_LABEL.getValue(a), { onAct(a) }, height = 44) }
                    else if (a == "REJECT" || a == "CANCEL_BY_PROVIDER") SecondaryButton(ACTION_LABEL.getValue(a), { onAct(a) }, danger = true)
                }
            }
        }
    }
}

// ---- Booking detail ("in bay") ----

class DetailViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val detail = MutableStateFlow<Load<OrderDetail>>(Load.Loading)
    val queued = MutableStateFlow(0)
    init { reload() }
    fun reload() { detail.load { app.providerDay.flush(); queued.value = app.providerDay.queued(); app.bookings.detail(id) } }
    fun act(o: Order, action: String, note: String?, report: (String) -> Unit) = run(report) {
        val done = app.providerDay.act(o.id, action, note)
        queued.value = app.providerDay.queued()
        if (done == null) report("Offline. This will sync when you're back online.")
        reload()
    }
}

@Composable
fun BookingDetailScreen(orderId: String, onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val vm = screenModel("vdetail-$orderId") { DetailViewModel(app, orderId) }
    val s = vm.detail.collectAsStateValue()
    val queued = vm.queued.collectAsStateValue()
    var reason by remember { mutableStateOf<String?>(null) }
    val o = (s as? Load.Ready)?.value?.order
    Screen(o?.let { displayCode(it.code) } ?: "Booking", onBack = onBack, footer = {
        if (o != null) {
            o.actions.filter { it in PRIMARY }.forEach { a -> PrimaryButton(ACTION_LABEL.getValue(a), { vm.act(o, a, null, report) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("NO_SHOW" in o.actions) SecondaryButton("Mark no-show", { vm.act(o, "NO_SHOW", null, report) }, Modifier.weight(1f))
                if (o.customerPhone.isNotBlank()) SecondaryButton("Call customer", { platform.call(o.customerPhone) }, Modifier.weight(1f), icon = Lucide.Phone)
                if ("CANCEL_BY_PROVIDER" in o.actions) SecondaryButton("Cancel", { reason = "CANCEL_BY_PROVIDER" }, danger = true)
            }
        }
    }) {
        Remote(s, vm::reload) { d ->
            val ord = d.order
            Row(verticalAlignment = Alignment.CenterVertically) { Tag("${ord.statusLabel}${ord.bay?.let { " · Bay $it" } ?: ""}", TagKind.ACCENT); Spacer(Modifier.weight(1f)); Body(if (ord.source == "WALK_IN") "Walk-in" else "Booked in app", size = 12, color = Ind.muted) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Heading(ord.assetLabel ?: ord.customerName, 32)
                Body("${ord.variantKey.titleCase()} · ${ord.customerName} · ${formatTime(ord.startsAt)} – ${formatTime(ord.endsAt)}", size = 13, color = Ind.muted)
            }
            Column(Modifier.fillMaxWidth().hairline()) {
                ord.lines.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (i == 0) Lucide.Droplets else Lucide.Wind, null, Modifier.size(20.dp), tint = Ind.a700); Body(l.name, size = 15, modifier = Modifier.weight(1f)); Body("${l.durationMinutes} min", size = 13, color = Ind.muted)
                    }
                    if (i < ord.lines.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
                }
            }
            if (queued > 0) Row(Modifier.fillMaxWidth().background(Ind.a100).hairline(Ind.accent).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.CloudOff, null, Modifier.size(18.dp), tint = Ind.a800); Body("Offline. Actions will sync when you're back online.", size = 13, color = Ind.a800)
            }
            Section("History") {
                d.events.reversed().forEach { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Body(formatTime(e.at), size = 13, color = Ind.muted, modifier = Modifier.width(64.dp))
                        Column { Body("${eventLabel(e)}${e.by?.let { " by ${it.substringBefore(" ")}" } ?: ""}", size = 14); e.note?.let { Body(it, size = 12, color = Ind.muted) } }
                    }
                }
            }
        }
    }
    reason?.let { a -> ReasonDialog("${ACTION_LABEL[a]} ${o?.let { displayCode(it.code) } ?: ""}", "Reason (shown to the customer)", { r -> reason = null; o?.let { vm.act(it, a, r, report) } }, { reason = null }) }
}

private fun eventLabel(e: OrderEvent) = when (e.action) {
    "PLACE" -> "Booked"; "PAYMENT_CAPTURED" -> "Paid in app"; "ACCEPT" -> "Accepted"; "CHECK_IN" -> "Checked in"; "START" -> "Wash started"; "COMPLETE" -> "Completed"
    "WALK_IN" -> "Walk-in added"; "RESCHEDULE" -> "Rescheduled"; else -> e.to.titleCase()
}

// ---- Scan to check in ----

@Composable
fun ScanScreen(onClose: () -> Unit, onFound: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun find(text: String) {
        if (busy || text.isBlank()) return
        busy = true; error = null
        scope.launch { try { onFound(app.provider.checkIn(text).id) } catch (e: Exception) { error = e.friendly() } finally { busy = false } }
    }
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.X, "Close", Modifier.size(24.dp).clickable(onClick = onClose)); Spacer(Modifier.weight(1f)); Heading("Scan customer QR", 22); Spacer(Modifier.weight(1f)) }
        Hatch(Modifier.fillMaxWidth().weight(1f).clickable { platform.scanCode { r -> if (r != null) find(r) } }, "tap to open the camera")
        PrimaryButton("Open camera", { platform.scanCode { r -> if (r != null) find(r) } })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Body("No camera? Type the booking code", size = 13, color = Ind.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Field("", code, { code = it.uppercase() }, Modifier.weight(1f), placeholder = "RN 4K7Q")
                SecondaryButton(if (busy) "…" else "Find", { find(code) }, Modifier.height(44.dp), enabled = code.isNotBlank() && !busy)
            }
            error?.let { Body(it, size = 13, color = Ind.danger) }
        }
    }
}

// ---- Walk-in ----

@Composable
fun WalkInScreen(loc: ProviderLocation, onClose: () -> Unit, onDone: (String) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vertical = LocalVertical.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<Load<List<Item>>>(Load.Loading) }
    var plate by remember { mutableStateOf("") }
    var variant by remember { mutableStateOf(tenant.variants.options.first().key) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { items = try { Load.Ready(app.provider.items(loc.id)) } catch (e: Exception) { Load.Failed(e.friendly()) } }
    val list = ((items as? Load.Ready)?.value ?: emptyList()).filter { it.active && !it.outOfStock && it.prices.containsKey(variant) }
    val picked = list.filter { it.id in chosen }
    val minutes = picked.filter { it.baysUsed > 0 }.sumOf { it.durationMinutes }

    Screen(
        "Add walk-in", onBack = onClose,
        footer = {
            PrimaryButton(if (busy) "Adding…" else "Add and check in", {
                busy = true; error = null
                scope.launch { try { onDone(app.provider.walkIn(loc.id, picked.map { it.id }, variant, null, "Walk-in", plate.trim().takeIf { it.isNotBlank() }, null).id) } catch (e: Exception) { error = e.friendly() } finally { busy = false } }
            }, enabled = picked.isNotEmpty() && !busy)
        },
    ) {
        Field("Number plate", plate, { plate = it.uppercase() }, placeholder = "KA 53 E 2210")
        Section(tenant.variants.name) { Seg(tenant.variants.options.map { it.label.take(6) }, tenant.variants.options.indexOfFirst { it.key == variant }.coerceAtLeast(0)) { variant = tenant.variants.options[it].key; chosen = emptySet() } }
        Section("${tenant.labels.item}s") {
            Remote(items, {}) { _ ->
                if (list.isEmpty()) EmptyNote("Nothing offered for this ${tenant.variants.name.lowercase()}.")
                list.forEach { it2 -> CheckRow("${it2.name}   ${formatMoney(it2.prices[variant], tenant.money.currency)}", it2.id in chosen) { chosen = if (it2.id in chosen) chosen - it2.id else chosen + it2.id } }
            }
        }
        Section("Bay and time") {
            Row(Modifier.fillMaxWidth().hairline().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Clock, null, Modifier.size(20.dp), tint = Ind.a700)
                Column(Modifier.weight(1f)) { Body("Now · next free bay${if (minutes > 0) " · $minutes min" else ""}", weight = FontWeight.Medium); Body("The calendar shows exactly which bay.", size = 12, color = Ind.muted) }
            }
        }
        Body("Walk-ins take a slot so online customers only see what's really free. Paid at the counter; no commission.", size = 12, color = Ind.muted)
        error?.let { ErrorBox(it) }
    }
}
