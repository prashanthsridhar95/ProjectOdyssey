package com.odyssey.vendorops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

private enum class Tab(val label: String, val glyph: String) { BOOKINGS("Bookings", "📋"), SLOTS("Slots", "🕒"), MENU("Menu", "🧾"), EARNINGS("Earnings", "₹") }

/** The vendor app's home: a bottom bar whose tabs depend on the role. Owners see everything; counter staff only bookings and slots. */
@Composable
fun VendorHome(user: User, onSignOut: () -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("provider") { ProviderViewModel(app) }
    val state = vm.provider.collectAsStateValue()
    val owner = user.role == Role.PROVIDER_OWNER
    val manager = owner || user.role == Role.PROVIDER_MANAGER
    val tabs = Tab.entries.filter { it == Tab.BOOKINGS || it == Tab.SLOTS || (it == Tab.MENU && manager) || (it == Tab.EARNINGS && owner) }
    var tab by remember { mutableStateOf(Tab.BOOKINGS) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val report: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { NavigationBar { tabs.forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Text(t.glyph) }, label = { Text(t.label) }) } } },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            Remote(state, vm::reload) { p ->
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(p.name, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("${user.name} · ${user.role.name.removePrefix("PROVIDER_").titleCase()}", fontSize = 12.sp) }
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    }
                    if (p.status != "LIVE") Box(Modifier.padding(horizontal = 16.dp)) { ErrorBox("You are not live yet (${p.status.titleCase()}). ${p.reviewNote ?: "The platform team is reviewing your details."}") }
                    val loc = p.locations.firstOrNull()
                    if (loc == null) EmptyNote("No outlet yet.") else when (tab) {
                        Tab.BOOKINGS -> BookingsTab(loc.id, report)
                        Tab.SLOTS -> SlotsTab(loc, report)
                        Tab.MENU -> MenuTab(loc.id, owner, report)
                        Tab.EARNINGS -> EarningsTab(report)
                    }
                }
            }
        }
    }
}

class ProviderViewModel(private val app: AppContainer) : BaseViewModel() {
    val provider = MutableStateFlow<Load<Provider>>(Load.Loading)
    init { reload() }
    fun reload() { provider.load { app.provider.me() } }
}

// ---- Bookings ----

private val LABEL = mapOf("ACCEPT" to "Accept", "REJECT" to "Reject", "START" to "Start", "COMPLETE" to "Complete", "NO_SHOW" to "No-show", "CANCEL_BY_PROVIDER" to "Cancel")
private val NEEDS_REASON = setOf("REJECT", "CANCEL_BY_PROVIDER")

class BookingsViewModel(private val app: AppContainer, private val locationId: String) : BaseViewModel() {
    val date = MutableStateFlow(istDate())
    val orders = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    fun reload() { val d = date.value; orders.load { app.providerDay.bookings(d, locationId) } }
    fun day(d: String) { date.value = d; reload() }
    fun act(orderId: String, action: String, note: String?, onError: (String) -> Unit) = run(onError) { app.providerDay.act(orderId, action, note); reload() }
}

@Composable
private fun BookingsTab(locationId: String, report: (String) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("p-bookings-$locationId") { BookingsViewModel(app, locationId) }
    val date = vm.date.collectAsStateValue()
    val state = vm.orders.collectAsStateValue()
    var reasonFor by remember { mutableStateOf<Pair<Order, String>?>(null) }
    // Refresh every 15 seconds so new bookings show up without a tap.
    LaunchedEffect(date) { while (true) { vm.reload(); delay(15_000) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..2).forEach { off -> val d = istDate(off); Chip(dayChipLabel(d), date == d) { vm.day(d) } } }
        Column(Modifier.weight(1f).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Remote(state, vm::reload) { list ->
                if (list.isEmpty()) EmptyNote("No bookings for this day.")
                list.forEach { o ->
                    SurfaceCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatTime(o.startsAt)} – ${formatTime(o.endsAt)}", fontWeight = FontWeight.Bold)
                            StatusBadge(o.statusLabel, o.status)
                        }
                        Text("${o.customerName}${o.assetLabel?.let { " · $it" } ?: ""}")
                        Text(o.lines.joinToString { it.name } + " · " + formatMoney(o.subtotalMinor), fontSize = 13.sp)
                        if (o.source == "WALK_IN") Text("Walk-in", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        o.cancellationReason?.let { Text("Reason: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            o.actions.filter { it in LABEL }.forEach { a ->
                                if (a in setOf("ACCEPT", "START", "COMPLETE")) Button(onClick = { vm.act(o.id, a, null, report) }) { Text(LABEL.getValue(a)) }
                                else SecondaryButton(LABEL.getValue(a), { if (a in NEEDS_REASON) reasonFor = o to a else vm.act(o.id, a, null, report) }, danger = a != "NO_SHOW")
                            }
                        }
                    }
                }
            }
        }
    }
    reasonFor?.let { (o, a) -> ReasonDialog("${LABEL[a]} ${o.code}", "Reason (shown to the customer)", { vm.act(o.id, a, it, report); reasonFor = null }, { reasonFor = null }) }
}

// ---- Slots ----

class SlotsViewModel(private val app: AppContainer, private val loc: ProviderLocation) : BaseViewModel() {
    val date = MutableStateFlow(istDate())
    val slots = MutableStateFlow<Load<Pair<List<Slot>, List<Block>>>>(Load.Loading)
    fun reload() { val d = date.value; slots.load { app.provider.slots(loc.id, d) to app.provider.blocks(loc.id, d) } }
    fun day(d: String) { date.value = d; reload() }
    fun block(s: Slot, onError: (String) -> Unit) = run(onError) { app.provider.addBlock(loc.id, s.startsAt, s.endsAt, "Blocked from app"); reload() }
    fun reopen(b: Block, onError: (String) -> Unit) = run(onError) { app.provider.removeBlock(loc.id, b.id); reload() }
    fun walkIn(itemId: String, variant: String, s: Slot, name: String, onError: (String) -> Unit) = run(onError) { app.provider.walkIn(loc.id, itemId, variant, s.startsAt, name); reload() }
}

@Composable
private fun SlotsTab(loc: ProviderLocation, report: (String) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("p-slots-${loc.id}") { SlotsViewModel(app, loc) }
    val date = vm.date.collectAsStateValue()
    val state = vm.slots.collectAsStateValue()
    var picked by remember { mutableStateOf<Slot?>(null) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(Unit) { vm.reload(); items = runCatching { app.provider.items(loc.id) }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..3).forEach { off -> val d = istDate(off); Chip(dayChipLabel(d), date == d) { vm.day(d) } } }
        Text("${loc.name} · ${loc.capacity} at a time. Tap a slot for options.", fontSize = 13.sp)
        Column(Modifier.weight(1f).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Remote(state, vm::reload) { (slots, blocks) ->
                slots.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            val blocked = blocks.any { it.startsAt.toInstantOrNull()!! < s.endsAt.toInstantOrNull()!! && it.endsAt.toInstantOrNull()!! > s.startsAt.toInstantOrNull()!! }
                            val note = when { blocked -> "Blocked"; s.free == 0 -> "Full"; !s.bookable -> "Not open"; else -> "${s.free} free" }
                            FilterChip(selected = blocked, onClick = { picked = s }, label = { Text("${formatTime(s.startsAt)}\n$note", fontSize = 12.sp) })
                        }
                    }
                }
            }
        }
    }
    picked?.let { s ->
        val block = (state as? Load.Ready)?.value?.second?.firstOrNull { it.startsAt.toInstantOrNull()!! < s.endsAt.toInstantOrNull()!! && it.endsAt.toInstantOrNull()!! > s.startsAt.toInstantOrNull()!! }
        var walkItem by remember { mutableStateOf(items.firstOrNull { it.active }?.id) }
        var variant by remember { mutableStateOf(tenant.variants.options.first().key) }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picked = null }, title = { Text(formatTime(s.startsAt)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (block != null) Text("This slot is blocked: ${block.reason}")
                    else if (s.bookable) {
                        Text("Add a walk-in")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tenant.variants.options.forEach { Chip(it.label, variant == it.key) { variant = it.key } } }
                        items.filter { it.active && it.prices.containsKey(variant) }.forEach { Chip(it.name, walkItem == it.id) { walkItem = it.id } }
                        Field("Customer name", name, { name = it })
                    } else Text("This slot cannot be changed.")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (block != null) Button(onClick = { vm.reopen(block, report); picked = null }) { Text("Reopen") }
                    else {
                        TextButton(onClick = { vm.block(s, report); picked = null }) { Text("Block") }
                        if (s.bookable) Button(onClick = { walkItem?.let { vm.walkIn(it, variant, s, name.ifBlank { "Walk-in" }, report) }; picked = null }, enabled = walkItem != null) { Text("Add walk-in") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Close") } },
        )
    }
}

// ---- Menu ----

class MenuViewModel(private val app: AppContainer, private val locationId: String) : BaseViewModel() {
    val items = MutableStateFlow<Load<List<Item>>>(Load.Loading)
    init { reload() }
    fun reload() { items.load { app.provider.items(locationId) } }
    fun toggle(i: Item, onError: (String) -> Unit) = run(onError) { app.provider.saveItem(locationId, i.copy(active = !i.active), null); reload() }
    suspend fun savePrices(i: Item, prices: Map<String, Long>, pin: String) { app.provider.saveItem(locationId, i.copy(prices = prices), pin); reload() }
}

@Composable
private fun MenuTab(locationId: String, owner: Boolean, report: (String) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("p-menu-$locationId") { MenuViewModel(app, locationId) }
    val state = vm.items.collectAsStateValue()
    val gate = rememberPinGate()
    var editing by remember { mutableStateOf<Item?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Turning a service on or off needs no PIN. Changing a price needs the Owner PIN.", fontSize = 13.sp)
        Remote(state, vm::reload) { list ->
            list.forEach { i ->
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(i.name, fontWeight = FontWeight.SemiBold); Text("${i.durationMinutes} min", fontSize = 12.sp) }
                        Switch(i.active, { vm.toggle(i, report) })
                    }
                    Text(tenant.variants.options.mapNotNull { v -> i.prices[v.key]?.let { "${v.label} ${formatMoney(it)}" } }.joinToString("  ·  "), fontSize = 13.sp)
                    if (owner) SecondaryButton("Edit prices", { editing = i })
                }
            }
        }
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(tenant.variants.options.associate { it.key to (item.prices[it.key]?.div(100)?.toString() ?: "") }) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(item.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { tenant.variants.options.forEach { v -> Field("${v.label} (₹)", text.getValue(v.key), { text = text + (v.key to it.filter(Char::isDigit)) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number) } } },
            confirmButton = {
                Button(onClick = {
                    val prices = text.filterValues { it.isNotBlank() }.mapValues { it.value.toLong() * 100 }
                    editing = null
                    gate.ask("Owner PIN to change prices") { pin -> vm.savePrices(item, prices, pin); report("Prices saved") }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    PinGateHost(gate, report)
}

// ---- Earnings (owner PIN) ----

@Composable
private fun EarningsTab(report: (String) -> Unit) {
    val app = LocalContainer.current
    val gate = rememberPinGate()
    var data by remember { mutableStateOf<Earnings?>(null) }
    LaunchedEffect(Unit) { gate.ask("Owner PIN to view earnings") { pin -> data = app.provider.earnings(pin) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val e = data
        if (e == null) { Text("Earnings are protected by your Owner PIN."); SecondaryButton("Enter PIN", { gate.ask("Owner PIN to view earnings") { pin -> data = app.provider.earnings(pin) } }) }
        else {
            SurfaceCard {
                Labeled("Completed bookings", e.orders.toString()); Labeled("Service value", formatMoney(e.grossMinor)); Labeled("Commission", formatMoney(e.commissionMinor))
                Labeled("Your earnings", formatMoney(e.earnedMinor)); Labeled("Paid out", formatMoney(e.paidOutMinor)); Labeled("Waiting for payout", formatMoney(e.pendingMinor))
            }
        }
    }
    PinGateHost(gate, report)
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
