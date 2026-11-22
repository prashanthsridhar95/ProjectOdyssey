package com.odyssey.booking

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlin.random.Random
import kotlin.time.Clock

private fun newKey() = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

// ---- Slot picker ----

data class SlotsState(val date: String, val slots: Load<List<Slot>> = Load.Loading, val selected: String? = null)

class SlotsViewModel(private val app: AppContainer, private val locationId: String, private val itemIds: List<String>) : BaseViewModel() {
    private val _state = MutableStateFlow(SlotsState(istDate()))
    val state: StateFlow<SlotsState> = _state
    private val loaded = MutableStateFlow<Load<List<Slot>>>(Load.Loading)

    init {
        run { loaded.collect { _state.value = _state.value.copy(slots = it) } }
        load()
    }

    private fun load() { val d = _state.value.date; loaded.load { app.loadSlots(locationId, d, itemIds) } }
    fun day(date: String) { _state.value = _state.value.copy(date = date, selected = null); load() }
    fun pick(startsAt: String) { _state.value = _state.value.copy(selected = startsAt) }
    fun retry() = load()
}

@Composable
fun SlotPickerScreen(locationId: String, itemIds: List<String>, onBack: () -> Unit, onContinue: (startsAt: String) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("slots-$locationId-${itemIds.joinToString()}") { SlotsViewModel(app, locationId, itemIds) }
    val s = vm.state.collectAsStateValue()
    val windowDays = LocalTenant.current.config.availability.windowDays

    Screen("Pick a time", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until minOf(windowDays, 4)).forEach { off -> val d = istDate(off); Chip(dayChipLabel(d), s.date == d) { vm.day(d) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (4 until minOf(windowDays, 8)).forEach { off -> val d = istDate(off); Chip(dayChipLabel(d), s.date == d) { vm.day(d) } }
        }
        Remote(s.slots, vm::retry) { slots ->
            val groups = groupSlots(slots)
            if (slots.none { it.bookable }) EmptyNote("No free slots on this day. Try another day.")
            groups.forEach { (part, list) ->
                Section(part.label) {
                    list.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { sl -> Chip(formatTime(sl.startsAt), s.selected == sl.startsAt, enabled = sl.bookable) { vm.pick(sl.startsAt) } }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false).height(8.dp))
        PrimaryButton("Continue", { s.selected?.let(onContinue) }, enabled = s.selected != null)
    }
}

// ---- Checkout: hold the slot, then pay ----

data class CheckoutState(
    val summary: Load<CheckoutSummary> = Load.Loading, val order: Order? = null, val secondsLeft: Int = 0,
    val method: String = "UPI", val simulateFail: Boolean = false, val paying: Boolean = false, val error: String? = null, val paid: Order? = null,
)

data class CheckoutSummary(val place: String, val itemNames: List<String>, val checkout: Checkout, val startsAt: String, val currency: String)

class CheckoutViewModel(
    private val app: AppContainer, private val locationId: String, private val itemIds: List<String>,
    private val variantKey: String, private val startsAt: String, private val assetId: String?, private val money: MoneyConfig,
) : BaseViewModel() {
    private val _state = MutableStateFlow(CheckoutState())
    val state: StateFlow<CheckoutState> = _state
    private val placeKey = newKey()
    private val payKey = newKey()

    init { start() }

    /** Placing the order holds the slot for a few minutes while the customer pays. */
    fun start() {
        _state.value = _state.value.copy(summary = Load.Loading, error = null)
        viewModelScope.launch {
            try {
                val d = app.loadPlace(locationId, variantKey)
                val items = d.items.filter { it.id in itemIds }
                val c = app.buildCheckout(items, variantKey, money) ?: error("Those services are not available any more")
                val order = app.placeAndPay.place(PlaceOrderInput(locationId, itemIds, variantKey, startsAt, assetId), placeKey)
                _state.value = _state.value.copy(summary = Load.Ready(CheckoutSummary(d.location.name, items.map { it.name }, c, startsAt, money.currency)), order = order)
                tick(order)
            } catch (e: Exception) { _state.value = _state.value.copy(summary = Load.Failed(e.friendly())) }
        }
    }

    private fun tick(order: Order) {
        val expires = order.holdExpiresAt?.toInstantOrNull() ?: return
        viewModelScope.launch {
            while (_state.value.paid == null) {
                val left = (expires - Clock.System.now()).inWholeSeconds.toInt()
                _state.value = _state.value.copy(secondsLeft = maxOf(0, left))
                if (left <= 0) break
                delay(1000)
            }
        }
    }

    fun method(m: String) { _state.value = _state.value.copy(method = m) }
    fun simulateFail(v: Boolean) { _state.value = _state.value.copy(simulateFail = v) }

    fun pay() {
        val s = _state.value
        val order = s.order ?: return
        if (s.paying) return
        _state.value = s.copy(paying = true, error = null)
        viewModelScope.launch {
            try {
                val paid = app.placeAndPay.pay(order.id, s.method, if (s.simulateFail) "fail" else null, payKey)
                _state.value = _state.value.copy(paying = false, paid = paid)
            } catch (e: Exception) { _state.value = _state.value.copy(paying = false, error = e.friendly()) }
        }
    }

    fun release() { _state.value.order?.takeIf { _state.value.paid == null }?.let { o -> run { app.bookings.abandon(o.id) } } }
}

@Composable
fun CheckoutScreen(
    locationId: String, itemIds: List<String>, variantKey: String, startsAt: String, assetId: String?, allowSimulation: Boolean,
    onBack: () -> Unit, onPaid: (Order) -> Unit,
) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("checkout-$locationId-$startsAt") { CheckoutViewModel(app, locationId, itemIds, variantKey, startsAt, assetId, tenant.money) }
    val s = vm.state.collectAsStateValue()
    LaunchedEffect(s.paid) { s.paid?.let(onPaid) }

    Screen("Checkout", onBack = { vm.release(); onBack() }) {
        Remote(s.summary, vm::start) { sum ->
            SurfaceCard {
                Text(sum.place, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(formatDateTime(sum.startsAt))
                Text(sum.itemNames.joinToString(", "), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
            if (s.secondsLeft > 0) Text("Slot held for ${s.secondsLeft / 60}:${(s.secondsLeft % 60).toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            else if (s.order != null) ErrorBox("Your hold expired. Go back and pick the time again.")
            SurfaceCard {
                Labeled("Service", formatMoney(sum.checkout.subtotalMinor, sum.currency))
                Labeled("Taxes", formatMoney(sum.checkout.taxMinor, sum.currency))
                if (sum.checkout.feeMinor > 0) Labeled("Convenience fee", formatMoney(sum.checkout.feeMinor, sum.currency))
                Labeled("Total", formatMoney(sum.checkout.totalMinor, sum.currency))
            }
            Section("Pay with") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("UPI" to "UPI", "CARD" to "Card", "NETBANKING" to "Net banking").forEach { (k, l) -> Chip(l, s.method == k) { vm.method(k) } } }
                if (allowSimulation) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(s.simulateFail, vm::simulateFail); Spacer(Modifier.width(8.dp)); Text("Test: make the payment fail", fontSize = 13.sp)
                }
            }
            s.error?.let { ErrorBox(it) }
            PrimaryButton(if (s.paying) "Paying…" else "Pay ${formatMoney(sum.checkout.totalMinor, sum.currency)}", vm::pay, enabled = !s.paying && s.secondsLeft > 0)
            Text("Free cancellation until ${tenant.cancellation.fullRefundHoursBefore} hours before your slot.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

// ---- My bookings ----

class BookingsViewModel(private val app: AppContainer) : BaseViewModel() {
    val orders = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    init { reload() }
    fun reload() { orders.load { app.bookings.mine() } }
}

@Composable
fun BookingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalContainer.current
    val labels = LocalTenant.current.config.labels
    val flow = LocalTenant.current.config.flow
    val vm = screenModel("bookings") { BookingsViewModel(app) }
    val state = vm.orders.collectAsStateValue()
    var upcoming by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { vm.reload() }

    Screen("My ${labels.order.lowercase()}s", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Upcoming", upcoming) { upcoming = true }; Chip("Past", !upcoming) { upcoming = false } }
        Remote(state, vm::reload) { all ->
            val terminal = flow.states.filter { it.terminal }.map { it.name }.toSet()
            val list = all.filter { (it.status !in terminal) == upcoming }.filter { it.status != flow.initial }
            if (list.isEmpty()) EmptyNote("Nothing here yet.")
            list.forEach { o ->
                SurfaceCard(onClick = { onOpen(o.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(o.locationName, fontWeight = FontWeight.SemiBold); StatusBadge(o.statusLabel, o.status) }
                    Text(formatDateTime(o.startsAt))
                    Text("${o.lines.joinToString { it.name }} · ${formatMoney(o.totalMinor)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    Text("Code ${o.code}", fontSize = 12.sp)
                }
            }
        }
    }
}

// ---- Booking detail: cancel and review ----

data class DetailState(val detail: Load<OrderDetail> = Load.Loading, val busy: Boolean = false, val message: String? = null, val error: String? = null)

class BookingDetailViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state
    private val loaded = MutableStateFlow<Load<OrderDetail>>(Load.Loading)
    init { run { loaded.collect { _state.value = _state.value.copy(detail = it) } }; reload() }
    fun reload() { loaded.load { app.bookings.detail(id) } }

    fun cancel() = act("Cancelled. Your refund is on its way.") { app.bookings.cancel(id) }
    fun review(rating: Int, comment: String) = act("Thanks for your review!") { app.bookings.review(id, rating, comment) }

    private fun act(done: String, block: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block(); _state.value = _state.value.copy(busy = false, message = done); reload() }
            catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = e.friendly()) }
        }
    }
}

@Composable
fun BookingDetailScreen(orderId: String, onBack: () -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("booking-$orderId") { BookingDetailViewModel(app, orderId) }
    val s = vm.state.collectAsStateValue()
    var confirmCancel by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(5) }
    var comment by remember { mutableStateOf("") }

    Screen("${tenant.labels.order} details", onBack = onBack) {
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
        s.error?.let { ErrorBox(it) }
        Remote(s.detail, vm::reload) { d ->
            val o = d.order
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(o.locationName, fontWeight = FontWeight.Bold, fontSize = 20.sp); StatusBadge(o.statusLabel, o.status) }
                Text(formatDateTime(o.startsAt)); Text("Show this code at the counter: ${o.code}", fontWeight = FontWeight.SemiBold)
                o.assetLabel?.let { Text(it) }
                o.cancellationReason?.let { Text("Reason: $it", color = MaterialTheme.colorScheme.error) }
            }
            SurfaceCard {
                o.lines.forEach { Labeled(it.name, formatMoney(it.priceMinor, tenant.money.currency)) }
                Labeled("Taxes", formatMoney(o.taxMinor, tenant.money.currency))
                if (o.feeMinor > 0) Labeled("Fee", formatMoney(o.feeMinor, tenant.money.currency))
                Labeled("Total paid", formatMoney(o.totalMinor, tenant.money.currency))
            }
            if ("CANCEL_BY_CUSTOMER" in o.actions) {
                val hours = (o.startsAt.toInstantOrNull()?.let { (it - Clock.System.now()).inWholeMinutes / 60.0 }) ?: 0.0
                SecondaryButton("Cancel", { confirmCancel = true }, danger = true, enabled = !s.busy)
                if (confirmCancel) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmCancel = false }, title = { Text("Cancel this ${tenant.labels.order.lowercase()}?") },
                    text = { Text("You will get ${formatMoney(refundPreview(o, tenant.cancellation, hours), tenant.money.currency)} back.") },
                    confirmButton = { androidx.compose.material3.Button(onClick = { confirmCancel = false; vm.cancel() }) { Text("Yes, cancel") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
                )
            }
            if (o.status == tenant.flow.completed && !o.reviewed) {
                Section("How was it?") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..5).forEach { n -> Chip(if (n <= rating) "★$n" else "☆$n", n == rating) { rating = n } } }
                    Field("Comment (optional)", comment, { comment = it })
                    PrimaryButton("Submit review", { vm.review(rating, comment) }, enabled = !s.busy)
                }
            }
            if (d.events.isNotEmpty()) Section("History") {
                d.events.forEach { e -> Text("${formatTime(e.at)} · ${e.to.titleCase()}${e.note?.let { " ($it)" } ?: ""}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)) }
            }
        }
    }
}
