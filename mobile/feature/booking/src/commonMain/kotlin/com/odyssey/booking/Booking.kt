package com.odyssey.booking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private fun newKey() = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

/** When the booking ends if it starts at [startIso]: whole slots, as the server books them. */
fun endOf(startIso: String, durationMinutes: Int, slotMinutes: Int): String {
    val start = startIso.toInstantOrNull() ?: return startIso
    val slots = maxOf(1, (durationMinutes + slotMinutes - 1) / slotMinutes)
    return (start + (slots * slotMinutes).minutes).toString()
}

private fun range(start: String, end: String) = "${formatDay(start).let { if (it == formatDay(istDate().let { d -> "${d}T05:00:00Z" })) "Today" else it }}, ${formatTime(start)} – ${formatTime(end)}"

// ---- Slot picker (also used to reschedule) ----

data class SlotsState(val date: String, val slots: Load<List<Slot>> = Load.Loading, val selected: String? = null, val title: String = "", val durationMinutes: Int = 30, val priceMinor: Long? = null)

class SlotsViewModel(private val app: AppContainer, private val locationId: String, private val itemIds: List<String>, private val variantKey: String?) : BaseViewModel() {
    private val _state = MutableStateFlow(SlotsState(istDate()))
    val state: StateFlow<SlotsState> = _state
    private val loaded = MutableStateFlow<Load<List<Slot>>>(Load.Loading)

    init {
        run { loaded.collect { _state.value = _state.value.copy(slots = it) } }
        load()
        run {
            val d = app.loadPlace(locationId, variantKey)
            val items = d.items.filter { it.id in itemIds }
            _state.value = _state.value.copy(
                title = "${d.location.name} · ${items.joinToString(" + ") { it.name }} · ${items.sumOf { it.durationMinutes }} min",
                durationMinutes = items.sumOf { it.durationMinutes }, priceMinor = variantKey?.let { v -> items.sumOf { it.prices[v] ?: 0 } },
            )
        }
    }

    private fun load() { val d = _state.value.date; loaded.load { app.loadSlots(locationId, d, itemIds, variantKey) } }
    fun day(date: String) { _state.value = _state.value.copy(date = date, selected = null); load() }
    fun pick(startsAt: String) { _state.value = _state.value.copy(selected = startsAt) }
    fun retry() = load()
}

@Composable
fun SlotPickerScreen(
    locationId: String, itemIds: List<String>, variantKey: String?, onBack: () -> Unit, onContinue: (startsAt: String) -> Unit,
    heading: String = "Pick a slot", confirmText: String = "Continue to payment",
) {
    val app = LocalContainer.current
    val vm = screenModel("slots-$locationId-${itemIds.joinToString()}-$heading") { SlotsViewModel(app, locationId, itemIds, variantKey) }
    val s = vm.state.collectAsStateValue()
    val cfg = LocalTenant.current.config
    val days = (0 until minOf(cfg.availability.windowDays + 1, 8)).map { istDate(it) }
    val slots = (s.slots as? Load.Ready)?.value ?: emptyList()
    val step = slots.firstOrNull()?.let { ((it.endsAt.toInstantOrNull()!! - it.startsAt.toInstantOrNull()!!).inWholeMinutes).toInt() } ?: 30
    val end = s.selected?.let { endOf(it, s.durationMinutes, step) }
    val chosen = slots.firstOrNull { it.startsAt == s.selected }
    val deals = slots.filter { it.bookable && it.discounted }

    Screen(
        heading, subtitle = s.title.ifBlank { null }, onBack = onBack,
        footer = {
            if (s.selected != null && end != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Body(range(s.selected, end), modifier = Modifier.weight(1f))
                if (chosen?.discounted == true) Body(formatMoney(chosen.originalPriceMinor, cfg.money.currency), size = 13, color = Ind.muted, strike = true)
                (chosen?.priceMinor ?: s.priceMinor)?.let { Body(formatMoney(it, cfg.money.currency), weight = FontWeight.SemiBold, color = if (chosen?.discounted == true) Ind.a700 else Ind.text) }
            }
            PrimaryButton(confirmText, { s.selected?.let(onContinue) }, enabled = s.selected != null)
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                val on = s.date == d
                val dt = LocalDateParts.of(d)
                Column(Modifier.width(60.dp).then(if (on) Modifier.background(Ind.accent, Ind.rSm) else Modifier.hairline()).clickable { vm.day(d) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Body(if (d == days.first()) "TODAY" else dt.weekday, size = 11, color = if (on) Ind.bg else Ind.muted)
                    Heading(dt.day, 22, color = if (on) Ind.bg else Ind.text)
                }
            }
        }
        Remote(s.slots, vm::retry) { all ->
            if (all.none { it.bookable }) EmptyNote("No free slots on this day. Try another day.")
            if (deals.isNotEmpty()) Row(Modifier.fillMaxWidth().enterOnce().background(Ind.a100, Ind.rMd).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Lucide.Zap, null, Modifier.size(18.dp), tint = Ind.a800)
                Body("${deals.first().deal ?: "Deal"} at ${deals.size} time${if (deals.size == 1) "" else "s"} today. Look for the marked slots.".let { if (s.date != days.first()) it.replace(" today", " this day") else it }, size = 13, color = Ind.a800, modifier = Modifier.weight(1f))
            }
            groupSlots(all).forEach { (part, list) ->
                Section(part.label) {
                    list.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { sl -> SlotCell(sl, s.selected == sl.startsAt, end, Modifier.weight(1f)) { vm.pick(sl.startsAt) } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Body("Slots under ${cfg.availability.leadMinutes} minutes from now are not shown. You can book up to ${cfg.availability.windowDays} days ahead.", size = 12, color = Ind.muted)
        }
    }
}

@Composable
private fun SlotCell(sl: Slot, selected: Boolean, end: String?, modifier: Modifier, onClick: () -> Unit) {
    val base = modifier.height(48.dp)
    when {
        selected -> Column(base.blueprint(fill = Ind.accent, line = Ind.accent).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Body(clock(sl.startsAt), size = 15, color = Ind.bg, weight = FontWeight.SemiBold); if (end != null) Body("to ${clock(end)}", size = 10, color = Ind.bg)
        }
        !sl.bookable -> Box(base.hairline(), Alignment.Center) { Body(clock(sl.startsAt), size = 15, color = Ind.mutedSoft, strike = true) }
        sl.discounted -> Column(base.background(Ind.a100, Ind.rSm).hairline(Ind.accent).clickable(onClick = onClick).semantics { contentDescription = "${formatTime(sl.startsAt)}, ${sl.deal ?: "deal"}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Body(clock(sl.startsAt), size = 15, color = Ind.a800, weight = FontWeight.Medium); Body(sl.deal ?: "Deal", size = 10, color = Ind.a800, weight = FontWeight.SemiBold)
        }
        else -> Column(base.hairline().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Body(clock(sl.startsAt), size = 15); if (sl.free == 1) Body("1 bay left", size = 10, color = Ind.a700)
        }
    }
}

private fun clock(iso: String) = formatTime(iso).removeSuffix(" am").removeSuffix(" pm")

/** Weekday and day-of-month for a YYYY-MM-DD string. */
object LocalDateParts {
    class Parts(val weekday: String, val day: String)
    fun of(date: String): Parts {
        val d = kotlinx.datetime.LocalDate.parse(date)
        return Parts(listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")[d.dayOfWeek.ordinal], d.day.toString())
    }
}

// ---- Checkout: hold the slot, apply a promo, pay ----

data class CheckoutState(
    val summary: Load<CheckoutSummary> = Load.Loading, val order: Order? = null, val secondsLeft: Int = 0, val method: String = "UPI", val test: String? = null,
    val paying: Boolean = false, val error: String? = null, val paid: Order? = null, val pending: Order? = null, val promoInput: String = "", val promoError: String? = null,
    val vouchers: List<Voucher> = emptyList(),
)

data class CheckoutSummary(val place: String, val items: List<Item>, val quote: Quote, val startsAt: String, val endsAt: String, val currency: String)

class CheckoutViewModel(
    private val app: AppContainer, private val locationId: String, private val itemIds: List<String>,
    private val variantKey: String, private val startsAt: String, private val assetId: String?, private val money: MoneyConfig,
) : BaseViewModel() {
    private val _state = MutableStateFlow(CheckoutState())
    val state: StateFlow<CheckoutState> = _state
    private var promo: String? = null
    private var payKey = newKey()
    private var ticker = 0

    init {
        start()
        viewModelScope.launch { runCatching { app.customer.vouchers() }.onSuccess { _state.value = _state.value.copy(vouchers = it) } }
    }

    /** Placing the order holds the slot for a few minutes while the customer pays. Applying a promo re-places it with the discount. */
    fun start() {
        _state.value = _state.value.copy(summary = Load.Loading, error = null)
        viewModelScope.launch {
            try {
                val d = app.loadPlace(locationId, variantKey)
                val items = d.items.filter { it.id in itemIds }
                val quote = app.customer.quote(locationId, itemIds, variantKey, promo, startsAt)
                val order = app.placeAndPay.place(PlaceOrderInput(locationId, itemIds, variantKey, startsAt, assetId, promo), newKey())
                _state.value = _state.value.copy(summary = Load.Ready(CheckoutSummary(d.location.name, items, quote, order.startsAt, order.endsAt, money.currency)), order = order, promoError = null)
                payKey = newKey()
                tick(order)
            } catch (e: Exception) { _state.value = _state.value.copy(summary = Load.Failed(e.friendly())) }
        }
    }

    private fun tick(order: Order) {
        val expires = order.holdExpiresAt?.toInstantOrNull() ?: return
        val mine = ++ticker
        viewModelScope.launch {
            while (mine == ticker && _state.value.paid == null) {
                val left = (expires - Clock.System.now()).inWholeSeconds.toInt()
                _state.value = _state.value.copy(secondsLeft = maxOf(0, left))
                if (left <= 0) break
                delay(1000)
            }
        }
    }

    fun promoText(v: String) { _state.value = _state.value.copy(promoInput = v.uppercase(), promoError = null) }

    fun applyPromo(typed: String? = null) {
        val code = (typed ?: _state.value.promoInput).trim().uppercase()
        if (code.isEmpty()) return
        if (typed != null) _state.value = _state.value.copy(promoInput = code, promoError = null)
        viewModelScope.launch {
            try {
                app.customer.quote(locationId, itemIds, variantKey, code, startsAt)   // validates before we give up the current hold
                _state.value.order?.let { app.bookings.abandon(it.id) }
                promo = code
                start()
            } catch (e: Exception) { _state.value = _state.value.copy(promoError = e.friendly()) }
        }
    }

    fun removePromo() { _state.value.order?.let { o -> run { app.bookings.abandon(o.id) } }; promo = null; _state.value = _state.value.copy(promoInput = ""); start() }
    fun method(m: String) { _state.value = _state.value.copy(method = m) }
    fun test(t: String?) { _state.value = _state.value.copy(test = t) }

    fun pay() {
        val s = _state.value
        val order = s.order ?: return
        if (s.paying) return
        _state.value = s.copy(paying = true, error = null)
        viewModelScope.launch {
            try {
                val paid = app.placeAndPay.pay(order.id, s.method, s.test, payKey)
                _state.value = if (paid.paymentPending) _state.value.copy(paying = false, pending = paid) else _state.value.copy(paying = false, paid = paid)
            } catch (e: Exception) { _state.value = _state.value.copy(paying = false, error = e.friendly()) }
        }
    }

    fun checkAgain() {
        val order = _state.value.pending ?: return
        viewModelScope.launch {
            try {
                val o = app.customer.checkPayment(order.id)
                _state.value = if (o.paymentPending) _state.value.copy(pending = o) else _state.value.copy(pending = null, paid = o)
            } catch (e: Exception) { _state.value = _state.value.copy(pending = null, error = e.friendly()) }
        }
    }

    fun release() { _state.value.order?.takeIf { _state.value.paid == null }?.let { o -> run { app.bookings.abandon(o.id) } } }
}

private val METHODS = listOf("UPI" to "UPI", "CARD" to "Credit or debit card", "NETBANKING" to "Netbanking", "WALLET" to "Wallets")

@Composable
fun CheckoutScreen(
    locationId: String, itemIds: List<String>, variantKey: String, startsAt: String, assetId: String?, allowSimulation: Boolean,
    onBack: () -> Unit, onPaid: (Order) -> Unit,
) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val platform = LocalPlatform.current
    val customer = LocalCustomer.current
    val vm = screenModel("checkout-$locationId-$startsAt") { CheckoutViewModel(app, locationId, itemIds, variantKey, startsAt, assetId, tenant.money) }
    val s = vm.state.collectAsStateValue()
    LaunchedEffect(s.paid) { s.paid?.let(onPaid) }

    s.pending?.let { pending ->
        PaymentPending(pending, onCheck = vm::checkAgain, onSupport = { pending.locationPhone?.let(platform::call) }, onBack = { onBack() })
        return
    }

    Screen(
        "Checkout", onBack = { vm.release(); onBack() },
        actions = { if (s.secondsLeft > 0) Tag("Slot held ${s.secondsLeft / 60}:${(s.secondsLeft % 60).toString().padStart(2, '0')}", TagKind.ACCENT, Lucide.Timer) },
        footer = {
            val q = (s.summary as? Load.Ready)?.value?.quote
            PrimaryButton(if (s.paying) "Paying…" else "Pay ${formatMoney(q?.totalMinor, tenant.money.currency)}", vm::pay, enabled = q != null && !s.paying && s.secondsLeft > 0)
        },
    ) {
        Remote(s.summary, vm::start) { sum ->
            val q = sum.quote
            Column(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Heading(sum.place, 17)
                Body("${range(sum.startsAt, sum.endsAt)}${customer.vehicle?.let { " · ${it.label}" } ?: ""}", size = 13, color = Ind.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sum.items.forEach { Labeled(it.name, formatMoney(it.prices[variantKey], sum.currency)) }
                if (q.feeMinor > 0) Labeled("Convenience fee", formatMoney(q.feeMinor, sum.currency))
                Labeled("GST (${formatPercent(tenant.money.taxBps)})", formatMoney(q.taxMinor, sum.currency))
                DiscountLines(q.discounts, q.discountMinor, q.promoCode, sum.currency)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
                Labeled("To pay", formatMoney(q.totalMinor, sum.currency), strong = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Field("", s.promoInput, vm::promoText, Modifier.weight(1f), placeholder = "Voucher or friend's code", enabled = q.promoCode == null)
                if (q.promoCode == null) SecondaryButton("Apply", { vm.applyPromo() }, enabled = s.promoInput.isNotBlank()) else SecondaryButton("Applied · Remove", vm::removePromo)
            }
            s.promoError?.let { Body(it, size = 13, color = Ind.danger) }
            if (q.promoCode == null && s.vouchers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Body("Your vouchers", size = 12, color = Ind.muted)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.vouchers.forEach { v -> Choice("${v.code} · ${v.description.substringAfter(": ")}", false) { vm.applyPromo(v.code) } }
                }
            }
            q.promotionName?.let { Body("$it is priced in. Only the best offer applies, and a voucher can still be added.", size = 12, color = Ind.muted) }
            Section("Pay with") { METHODS.forEach { (k, l) -> RadioRow(l, s.method == k) { vm.method(k) } } }
            if (allowSimulation) Section("Test only") {
                RadioRow("Normal", s.test == null, height = 44) { vm.test(null) }
                RadioRow("Decline the payment", s.test == "fail", height = 44) { vm.test("fail") }
                RadioRow("Slow bank (stays pending)", s.test == "pending", height = 44) { vm.test("pending") }
            }
            s.error?.let { ErrorBox(it) }
            Body("Free cancellation up to ${tenant.cancellation.fullRefundHoursBefore} hours before your slot.", size = 12, color = Ind.muted)
        }
    }
}

@Composable
private fun PaymentPending(order: Order, onCheck: () -> Unit, onSupport: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(64.dp).hairline(Ind.accent), Alignment.Center) { Icon(Lucide.WifiOff, null, Modifier.size(28.dp), tint = Ind.a700) }
        Heading("We're confirming your payment", 32)
        Body("Your connection dropped while we were waiting for the bank. You won't be charged twice. Your slot stays held while we check.", size = 15, color = Ind.muted)
        Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Lucide.Check, null, Modifier.size(18.dp), tint = Ind.a700); Body("Slot held until ${order.holdExpiresAt?.let(::formatTime) ?: "shortly"}") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Lucide.Loader, null, Modifier.size(18.dp), tint = Ind.a700); Body("Waiting for payment status") }
        }
        PrimaryButton("Check again", onCheck, height = 52)
        GhostButton("Contact support", onSupport, Modifier.fillMaxWidth())
        GhostButton("Back to the checkout", onBack, Modifier.fillMaxWidth())
    }
}

/** Each discount on its own line ("Afternoon happy hour − ₹120", "Code FIRSTWASH − ₹100"). Older bookings only carry a total. */
@Composable
fun DiscountLines(lines: List<DiscountLine>, totalMinor: Long, code: String?, currency: String) {
    val shown = lines.ifEmpty { if (totalMinor > 0) listOf(DiscountLine("VOUCHER", code?.let { "Code $it" } ?: "Discount", totalMinor)) else emptyList() }
    shown.forEach { d ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (d.kind == "PROMOTION") Lucide.Zap else if (d.kind == "REFERRAL") Lucide.Gift else Lucide.Tag, null, Modifier.size(14.dp), tint = Ind.a700)
                Body(d.label, color = Ind.a700)
            }
            Body("− ${formatMoney(d.amountMinor, currency)}", color = Ind.a700)
        }
    }
}

// ---- Confirmed ----

@Composable
fun ConfirmedScreen(order: Order, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val cfg = LocalTenant.current.config
    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 8.dp)) { Spacer(Modifier.weight(1f)); Icon(Lucide.X, "Close", Modifier.size(24.dp).clickable(onClick = onClose)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("Paid ${formatMoney(order.totalMinor, cfg.money.currency)}", TagKind.ACCENT, Lucide.Check)
                Heading("You're booked.", 36)
                Body("${formatDay(order.startsAt)}, ${formatTime(order.startsAt)} at ${order.locationName}${order.locationAddress?.let { ", ${it.split(",").getOrNull(1)?.trim() ?: ""}" } ?: ""}.", size = 15, color = Ind.muted)
            }
            Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                QrCode(order.code, Modifier.size(180.dp))
                Body("Show this at the counter", size = 12, color = Ind.muted)
                Heading(displayCode(order.code), 32, color = Ind.text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("Services", color = Ind.muted); Body(order.lines.joinToString { it.name }) }
                order.assetLabel?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("Vehicle", color = Ind.muted); Body(it) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp, 8.dp, 24.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Add to calendar", { order.startsAt.toInstantOrNull()?.let { platform.addToCalendar("${order.lines.first().name} at ${order.locationName}", it.toEpochMilliseconds(), (order.endsAt.toInstantOrNull() ?: it).toEpochMilliseconds(), order.locationAddress ?: order.locationName) } }, Modifier.weight(1f).height(48.dp), icon = Lucide.CalendarPlus)
            SecondaryButton("Directions", { if (order.locationLat != null && order.locationLng != null) platform.directions(order.locationLat!!, order.locationLng!!, order.locationName) }, Modifier.weight(1f).height(48.dp), icon = Lucide.Navigation)
        }
    }
}

// ---- Bookings: upcoming with progress, and recent ----

data class BookingsState(val orders: Load<List<Order>> = Load.Loading, val details: Map<String, OrderDetail> = emptyMap(), val upcoming: Boolean = true, val message: String? = null)

class BookingsViewModel(private val app: AppContainer) : BaseViewModel() {
    private val _state = MutableStateFlow(BookingsState())
    val state: StateFlow<BookingsState> = _state
    private val loaded = MutableStateFlow<Load<List<Order>>>(Load.Loading)

    init { run { loaded.collect { _state.value = _state.value.copy(orders = it) } }; reload() }

    fun reload() {
        loaded.load { app.bookings.mine() }
        viewModelScope.launch {
            delay(300)
            val list = (loaded.value as? Load.Ready)?.value ?: return@launch
            val open = list.filter { it.status !in setOf("COMPLETED", "CANCELLED", "REJECTED", "EXPIRED", "NO_SHOW", "PENDING_PAYMENT") }
            _state.value = _state.value.copy(details = open.mapNotNull { o -> runCatching { app.bookings.detail(o.id) }.getOrNull() }.associateBy { it.order.id })
        }
    }

    fun tab(upcoming: Boolean) { _state.value = _state.value.copy(upcoming = upcoming) }
    fun cancel(id: String) = run({ _state.value = _state.value.copy(message = it) }) { app.bookings.cancel(id); _state.value = _state.value.copy(message = "Cancelled. Your refund is on its way."); reload() }
}

@Composable
fun BookingsScreen(onOpen: (String) -> Unit, onRebook: (String) -> Unit, onReschedule: (Order) -> Unit, bottomBar: @Composable () -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val platform = LocalPlatform.current
    val vm = screenModel("bookings") { BookingsViewModel(app) }
    val s = vm.state.collectAsStateValue()
    var confirmCancel by remember { mutableStateOf<Order?>(null) }
    LaunchedEffect(Unit) { while (true) { vm.reload(); delay(20_000) } }
    val flow = tenant.flow
    val terminal = flow.states.filter { it.terminal }.map { it.name }.toSet()
    val all = (s.orders as? Load.Ready)?.value ?: emptyList()
    val live = all.filter { it.status != flow.initial }
    val upcoming = live.filter { it.status !in terminal }.sortedBy { it.startsAt }
    val past = live.filter { it.status in terminal && it.status != "EXPIRED" }

    Screen(tenant.labels.order + "s", bottomBar = bottomBar) {
        Seg(listOf("Upcoming · ${upcoming.size}", "Past"), if (s.upcoming) 0 else 1) { vm.tab(it == 0) }
        s.message?.let { Body(it, color = Ind.a700, weight = FontWeight.Medium) }
        Remote(s.orders, vm::reload) {
            if (s.upcoming) {
                if (upcoming.isEmpty()) EmptyState(Lucide.CalendarDays, "Nothing coming up", "Book a ${LocalVertical.current?.serviceNoun ?: "slot"} and it shows here with live updates.")
                upcoming.forEach { o -> ActiveBooking(o, s.details[o.id], tenant, onOpen, onReschedule = { onReschedule(o) }, onCancel = { confirmCancel = o }, onCall = { (s.details[o.id]?.order?.locationPhone ?: o.locationPhone)?.let(platform::call) }) }
            }
            if (!s.upcoming || past.isNotEmpty()) Section("Recent") {
                val rows = if (s.upcoming) past.take(3) else past
                if (rows.isEmpty()) EmptyNote("No past ${tenant.labels.order.lowercase()}s yet.")
                rows.forEach { o ->
                    Row(Modifier.fillMaxWidth().topHairline().clickable { onOpen(o.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Body(o.locationName, size = 15, weight = FontWeight.Medium); Body("${formatDay(o.startsAt).substringAfter(", ")} · ${o.lines.joinToString { it.name }} · ${formatMoney(o.totalMinor)}${if (o.status != flow.completed) " · ${o.statusLabel}" else ""}", size = 12, color = Ind.muted) }
                        SecondaryButton("Rebook", { onRebook(o.locationId) }, Modifier.height(36.dp), icon = Lucide.RotateCcw)
                    }
                }
            }
        }
    }
    confirmCancel?.let { o ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = null }, containerColor = Ind.bg, title = { Heading("Cancel this ${tenant.labels.order.lowercase()}?", 22) },
            text = { Body("You will get ${formatMoney(refundPreview(o, tenant.cancellation, hoursUntil(o.startsAt)), tenant.money.currency)} back.") },
            confirmButton = { PrimaryButton("Yes, cancel", { confirmCancel = null; vm.cancel(o.id) }, height = 44) }, dismissButton = { GhostButton("Keep it", { confirmCancel = null }) },
        )
    }
}

@Composable
private fun ActiveBooking(o: Order, detail: OrderDetail?, tenant: TenantConfig, onOpen: (String) -> Unit, onReschedule: () -> Unit, onCancel: () -> Unit, onCall: () -> Unit) {
    val labels = mapOf("CONFIRMED" to "Confirmed", "CHECKED_IN" to "Car checked in", "IN_PROGRESS" to "Wash in progress", "COMPLETED" to "Ready for pickup")
    val steps = progressSteps(detail?.events ?: emptyList(), o.status, labels)
    val canMove = o.status in setOf(tenant.flow.paid, tenant.flow.accepted) && o.reschedules < 2 && hoursUntil(o.startsAt) >= tenant.cancellation.fullRefundHoursBefore
    Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().clickable { onOpen(o.id) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Tag(o.statusLabel, TagKind.ACCENT); Spacer(Modifier.weight(1f)); Body(displayCode(o.code), size = 12, color = Ind.muted) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Heading(o.locationName, 20); Body("${formatDay(o.startsAt)}, ${formatTime(o.startsAt)} · ${o.lines.joinToString { it.name }}", size = 13, color = Ind.muted) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { steps.forEach { Box(Modifier.weight(1f).height(4.dp).background(if (it.done || it.current) Ind.accent else Ind.n300, Ind.pill).then(if (it.current) Modifier.pulsing(0.5f, 2000) else Modifier)) } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (st.current && o.status == "IN_PROGRESS") Lucide.Droplets else if (st.done) Lucide.Check else Lucide.Circle, null, Modifier.size(18.dp), tint = if (st.done || st.current) Ind.a700 else Ind.mutedSoft)
                    Body(st.label, modifier = Modifier.weight(1f), weight = if (st.current) FontWeight.SemiBold else FontWeight.Normal, color = if (st.done || st.current) Ind.text else Ind.mutedSoft)
                    Body(st.at?.let(::formatTime) ?: if (st.label.startsWith("Ready")) "about ${endTime(o)}" else "", size = 12, color = Ind.muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Reschedule", onReschedule, Modifier.weight(1f).height(40.dp), enabled = canMove)
            SecondaryButton("Cancel", onCancel, Modifier.weight(1f).height(40.dp), enabled = "CANCEL_BY_CUSTOMER" in o.actions)
            SecondaryButton("Call outlet", onCall, Modifier.weight(1f).height(40.dp), enabled = (detail?.order?.locationPhone ?: o.locationPhone) != null)
        }
    }
}

private fun endTime(o: Order) = formatTime(o.endsAt)

// ---- Booking detail ----

class BookingDetailViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val state = MutableStateFlow<Load<OrderDetail>>(Load.Loading)
    init { reload() }
    fun reload() { state.load { app.bookings.detail(id) } }
}

@Composable
fun BookingDetailScreen(orderId: String, onBack: () -> Unit, onRate: (String) -> Unit, onReport: (String) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("booking-$orderId") { BookingDetailViewModel(app, orderId) }
    val s = vm.state.collectAsStateValue()
    Screen("${tenant.labels.order} details", onBack = onBack) {
        Remote(s, vm::reload) { d ->
            val o = d.order
            Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Heading(o.locationName, 22, Modifier.weight(1f)); StatusBadge(o.statusLabel, o.status) }
                Body("${formatDay(o.startsAt)}, ${formatTime(o.startsAt)}")
                Row { Body("Code ", color = Ind.muted); Body(displayCode(o.code), weight = FontWeight.SemiBold) }
                o.assetLabel?.let { Body(it) }
                o.cancellationReason?.let { Body("Reason: $it", color = Ind.danger) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                o.lines.forEach { Labeled(it.name, formatMoney(it.priceMinor, tenant.money.currency)) }
                if (o.feeMinor > 0) Labeled("Convenience fee", formatMoney(o.feeMinor, tenant.money.currency))
                Labeled("Taxes", formatMoney(o.taxMinor, tenant.money.currency))
                DiscountLines(o.discounts, o.discountMinor, o.promoCode, tenant.money.currency)
                Labeled("Total paid", formatMoney(o.totalMinor, tenant.money.currency), strong = true)
            }
            if (o.status == tenant.flow.completed) {
                if (!o.reviewed) PrimaryButton("Rate your ${LocalVertical.current?.serviceNoun ?: "visit"}", { onRate(o.id) })
                SecondaryButton("Report a problem", { onReport(o.id) }, Modifier.fillMaxWidth(), icon = Lucide.Flag)
            }
            if (d.events.isNotEmpty()) Section("History") {
                d.events.forEach { e -> Body("${formatTime(e.at)} · ${if (e.action == "RESCHEDULE") "Rescheduled" else e.to.titleCase()}${e.note?.let { " ($it)" } ?: ""}", size = 13, color = Ind.muted) }
            }
        }
    }
}

// ---- Rate ----

class RateViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val order = MutableStateFlow<Load<Order>>(Load.Loading)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    init { order.load { app.bookings.detail(id).order } }
    fun submit(rating: Int, comment: String, tags: List<String>, done: () -> Unit) {
        busy.value = true; error.value = null
        viewModelScope.launch { try { app.bookings.review(id, rating, comment, tags); done() } catch (e: Exception) { error.value = e.friendly() } finally { busy.value = false } }
    }
}

@Composable
fun RateScreen(orderId: String, onClose: () -> Unit, onReport: () -> Unit) {
    val app = LocalContainer.current
    val vertical = LocalVertical.current
    val vm = screenModel("rate-$orderId") { RateViewModel(app, orderId) }
    val o = (vm.order.collectAsStateValue() as? Load.Ready)?.value
    val busy = vm.busy.collectAsStateValue()
    val error = vm.error.collectAsStateValue()
    var rating by remember { mutableStateOf(5) }
    var tags by remember { mutableStateOf(setOf<String>()) }
    var comment by remember { mutableStateOf("") }
    Screen(
        o?.let { "${it.locationName} · ${formatDay(it.startsAt).substringAfter(", ")}" } ?: "Rate", onBack = onClose,
        footer = { PrimaryButton(if (busy) "Sending…" else "Submit review", { vm.submit(rating, comment, tags.toList(), onClose) }, enabled = !busy) },
    ) {
        Heading("How was your ${vertical?.serviceNoun ?: "visit"}?", 32)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { (1..5).forEach { n -> Text("★", Modifier.clickable { rating = n }, color = if (n <= rating) Ind.accent else Ind.n400, fontSize = 40.sp) } }
        Section("What went well") {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (vertical?.reviewTags ?: listOf("On time", "Friendly staff", "Good value")).forEach { t -> Choice(t, t in tags) { tags = if (t in tags) tags - t else tags + t } }
            }
        }
        Field("Write a review (optional)", comment, { comment = it }, multiline = true)
        Row(Modifier.fillMaxWidth().hairline().clickable(onClick = onReport).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Lucide.Flag, null, Modifier.size(20.dp), tint = Ind.a700)
            Column(Modifier.weight(1f)) { Body("Report a problem", size = 15, weight = FontWeight.Medium); Body("Add photos. Our team reviews within 24 hours.", size = 12, color = Ind.muted) }
            Icon(Lucide.ChevronRight, null, Modifier.size(18.dp))
        }
        error?.let { ErrorBox(it) }
    }
}

// ---- Report a problem ----

class ReportViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun send(category: String, description: String, photos: List<String>, done: () -> Unit) {
        busy.value = true; error.value = null
        viewModelScope.launch { try { app.customer.report(id, category, description, photos); done() } catch (e: Exception) { error.value = e.friendly() } finally { busy.value = false } }
    }
}

@Composable
fun ReportScreen(orderId: String, onBack: () -> Unit, onSent: () -> Unit) {
    val app = LocalContainer.current
    val vertical = LocalVertical.current
    val platform = LocalPlatform.current
    val vm = screenModel("report-$orderId") { ReportViewModel(app, orderId) }
    val busy = vm.busy.collectAsStateValue()
    val error = vm.error.collectAsStateValue()
    val categories = vertical?.reportCategories ?: listOf("QUALITY" to "Quality", "DAMAGE" to "Damage", "WAIT" to "Long wait", "CHARGE" to "Charged incorrectly")
    var category by remember { mutableStateOf(categories.first().first) }
    var text by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(listOf<String>()) }
    Screen(
        "Report a problem", onBack = onBack,
        footer = { PrimaryButton(if (busy) "Sending…" else "Send report", { vm.send(category, text, photos, onSent) }, enabled = text.isNotBlank() && !busy) },
    ) {
        Section("What happened") { categories.forEach { (k, l) -> RadioRow(l, category == k, height = 48) { category = k } } }
        Section("Photos") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { i, _ -> Box(Modifier.weight(1f).aspectRatio(1f).hairline().clickable { photos = photos.filterIndexed { j, _ -> j != i } }, Alignment.Center) { Body("photo ${i + 1}\ntap to remove", size = 10, color = Ind.muted, modifier = Modifier) } }
                if (photos.size < 3) Column(Modifier.weight(1f).aspectRatio(1f).hairline(Ind.mutedSoft).clickable { platform.pickPhoto { p -> if (p != null) photos = photos + p } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) { Icon(Lucide.Camera, null, Modifier.size(22.dp)); Body("Add", size = 12, color = Ind.muted) }
                repeat(2 - photos.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Field("Describe the problem", text, { text = it }, multiline = true)
        Body("If upheld, you get a partial or full refund to your original payment method.", size = 12, color = Ind.muted)
        error?.let { ErrorBox(it) }
    }
}
