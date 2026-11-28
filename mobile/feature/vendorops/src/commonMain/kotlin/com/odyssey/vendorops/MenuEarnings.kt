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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ---- Menu ----

class MenuViewModel(private val app: AppContainer, private val locationId: String) : BaseViewModel() {
    val items = MutableStateFlow<Load<List<Item>>>(Load.Loading)
    init { reload() }
    fun reload() { items.load { app.provider.items(locationId) } }
}

@Composable
fun MenuScreen(locationId: String, owner: Boolean, onEdit: (Item?) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vm = screenModel("v-menu-$locationId") { MenuViewModel(app, locationId) }
    val state = vm.items.collectAsStateValue()
    var cat by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.reload() }
    Screen("Menu", actions = { if (owner) SecondaryButton("Service", { onEdit(null) }, icon = Lucide.Plus) }) {
        Remote(state, vm::reload) { items ->
            val cats = items.map { it.category.ifBlank { "Other" } }.distinct()
            if (cats.isEmpty()) EmptyNote("No ${tenant.labels.item.lowercase()}s yet.")
            else {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEachIndexed { i, c -> Choice("$c · ${items.count { (it.category.ifBlank { "Other" }) == c }}", cat == i) { cat = i } }
                }
                items.filter { it.category.ifBlank { "Other" } == cats[cat.coerceIn(0, cats.lastIndex)] }.forEach { i ->
                    Column(Modifier.fillMaxWidth().hairline().clickable { onEdit(i) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Heading(i.name, 19); Body("${i.durationMinutes} min · ${i.baysUsed} bay${if (i.baysUsed != 1) "s" else ""}", size = 12, color = Ind.muted) }
                            if (!i.active) Tag("Off", TagKind.NEUTRAL) else if (i.outOfStock) Tag("Out of stock", TagKind.OUTLINE)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tenant.variants.options.forEach { v ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Body(v.label.take(6), size = 11, color = Ind.muted); Body(i.prices[v.key]?.let { formatMoney(it, tenant.money.currency) } ?: "—", size = 14, weight = FontWeight.Medium) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditItemScreen(locationId: String, item: Item?, owner: Boolean, onBack: () -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val gate = rememberPinGate()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var minutes by remember { mutableStateOf(item?.durationMinutes ?: 30) }
    var bays by remember { mutableStateOf(item?.baysUsed ?: 1) }
    var active by remember { mutableStateOf(item?.active ?: true) }
    var stock by remember { mutableStateOf(item?.outOfStock ?: false) }
    var prices by remember { mutableStateOf(tenant.variants.options.associate { it.key to (item?.prices?.get(it.key)?.div(100)?.toString() ?: "") }) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun built(): Item {
        val map = prices.filterValues { it.isNotBlank() }.mapValues { it.value.toLong() * 100 }
        return Item(item?.id ?: "", name.trim(), item?.description ?: "", category.trim(), minutes, active, map, bays, stock)
    }
    val priceChanged = item == null || built().prices != item.prices

    Screen(
        item?.name ?: "New service", onBack = onBack, actions = { Tag("Owner", TagKind.NEUTRAL, Lucide.Lock) },
        footer = {
            PrimaryButton("Save changes", {
                error = null
                val save: suspend (String?) -> Unit = { pin -> app.provider.saveItem(locationId, built(), pin); onBack() }
                if (priceChanged) gate.ask("Owner PIN to change prices") { pin -> save(pin) } else scope.launch { try { save(null) } catch (e: Exception) { error = e.friendly() } }
            }, enabled = name.isNotBlank() && built().prices.isNotEmpty() && (owner || !priceChanged))
        },
    ) {
        Field("Name", name, { name = it }, enabled = owner || item == null)
        Field("Category", category, { category = it }, placeholder = "Exterior, Interior, Add-on")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { NumStepper("Duration", "$minutes min", { minutes = (minutes - 5).coerceAtLeast(5) }, { minutes += 5 }) }
            Box(Modifier.weight(1f)) { NumStepper("Bays used", bays.toString(), { bays = (bays - 1).coerceAtLeast(0) }, { bays += 1 }) }
        }
        Body("Add-ons that run alongside another service use 0 bays.", size = 12, color = Ind.muted)
        Section("Price by ${tenant.variants.name.lowercase()}") {
            tenant.variants.options.forEach { v ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Body(v.label, modifier = Modifier.weight(1f)); Box(Modifier.width(150.dp)) { Field("", prices.getValue(v.key), { t -> prices = prices + (v.key to t.filter(Char::isDigit)) }, keyboard = KeyboardType.Number, placeholder = "Not offered") }
                }
            }
        }
        if (item != null && priceChanged) Body("New prices apply to new bookings only. Existing bookings keep what they were booked at.", size = 12, color = Ind.muted)
        Row(verticalAlignment = Alignment.CenterVertically) { Body("Available to customers", modifier = Modifier.weight(1f)); SquareToggle(active) { active = it } }
        Row(verticalAlignment = Alignment.CenterVertically) { Body("Out of stock", modifier = Modifier.weight(1f)); SquareToggle(stock) { stock = it } }
        error?.let { ErrorBox(it) }
    }
    PinGateHost(gate) { error = it }
}

@Composable
private fun NumStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Body(label, size = 12, color = Ind.muted)
        Row(Modifier.fillMaxWidth().height(44.dp).hairline(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onMinus), Alignment.Center) { Heading("−", 22) }
            Box(Modifier.weight(1f), Alignment.Center) { Body(value, size = 15, weight = FontWeight.Medium) }
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onPlus), Alignment.Center) { Heading("+", 22) }
        }
    }
}

// ---- Earnings (Owner PIN) ----

@Composable
fun EarningsScreen(report: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val gate = rememberPinGate()
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(1) }
    var data by remember { mutableStateOf<EarningsReport?>(null) }
    var payouts by remember { mutableStateOf<List<PayoutRow>>(emptyList()) }
    var pinUsed by remember { mutableStateOf<String?>(null) }
    val key = listOf("today", "week", "month")[range]

    fun load(pin: String) = scope.launch {
        try { data = app.provider.earnings(key, pin); payouts = app.provider.payouts(pin); pinUsed = pin } catch (e: Exception) { report(e.friendly()) }
    }
    LaunchedEffect(range) { val pin = pinUsed; if (pin != null) load(pin) else gate.ask("Owner PIN to view earnings") { p -> data = app.provider.earnings(key, p); payouts = app.provider.payouts(p); pinUsed = p } }

    Screen("Earnings") {
        Seg(listOf("Today", "This week", "Month"), range) { range = it }
        val e = data
        if (e == null) { Body("Earnings are protected by your Owner PIN.", color = Ind.muted); SecondaryButton("Enter PIN", { gate.ask("Owner PIN to view earnings") { p -> data = app.provider.earnings(key, p); payouts = app.provider.payouts(p); pinUsed = p } }) }
        else {
            Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caps("Net payable · ${formatDay(e.from).substringAfter(", ")} – ${formatDay(e.to).substringAfter(", ")}")
                Heading(formatMoney(e.netMinor), 44)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
                Labeled("Service amount · ${e.orders} bookings", formatMoney(e.serviceMinor))
                Labeled("Commission (${formatPercent(e.commissionBps)})", "− ${formatMoney(e.commissionMinor)}")
                Labeled("Gateway fee share", "− ${formatMoney(e.gatewayFeeMinor)}")
            }
            Row(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Lucide.CalendarClock, null, Modifier.size(20.dp), tint = Ind.a700)
                Body("Pays out ${formatDay("${e.nextPayoutDate}T06:00:00Z")}${e.bankMasked?.let { " to $it" } ?: ""}", size = 14)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Payout history", 20, Modifier.weight(1f))
                GhostButton("Statement", { pinUsed?.let { pin -> scope.launch { try { platform.shareText("Statement", app.provider.statement(key, pin)) } catch (ex: Exception) { report(ex.friendly()) } } } })
            }
            if (payouts.isEmpty()) Body("No payouts yet.", size = 13, color = Ind.muted)
            payouts.forEach { p ->
                Row(Modifier.fillMaxWidth().topHairline().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Body(formatDay(p.createdAt).substringAfter(", "), weight = FontWeight.Medium); Body("${p.reference ?: ""} · ${p.orderCount} bookings", size = 12, color = Ind.muted) }
                    Column(horizontalAlignment = Alignment.End) { Body(formatMoney(p.amountMinor), weight = FontWeight.SemiBold); StatusBadge(p.status.titleCase(), if (p.status == "PROCESSED") "CONFIRMED" else "CANCELLED") }
                }
            }
        }
    }
    PinGateHost(gate, report)
}
