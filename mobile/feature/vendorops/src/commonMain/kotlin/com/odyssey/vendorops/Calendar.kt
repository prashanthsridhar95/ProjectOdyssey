package com.odyssey.vendorops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.coroutines.launch

class CalendarViewModel(private val app: AppContainer, private val loc: ProviderLocation) : BaseViewModel() {
    val date = MutableStateFlow(istDate())
    val data = MutableStateFlow<Load<Calendar>>(Load.Loading)
    init { reload() }
    fun reload() { val d = date.value; data.load { app.provider.calendar(loc.id, d) } }
    fun shift(days: Int) { date.value = LocalDate.parse(date.value).plus(days, DateTimeUnit.DAY).toString(); reload() }
}

private const val HOUR = 64

/** Minutes since midnight in India time for an instant. */
private fun minutesOf(iso: String): Int {
    val t = iso.toInstantOrNull() ?: return 0
    val dt = t.toLocalDateTime(IST)
    return dt.hour * 60 + dt.minute
}

private fun clockToMin(hhmm: String) = (hhmm.take(2).toIntOrNull() ?: 0) * 60 + (hhmm.drop(3).take(2).toIntOrNull() ?: 0)
fun minToClock(m: Int): String { val h = m / 60; val mm = m % 60; val h12 = if (h % 12 == 0) 12 else h % 12; return "$h12:${mm.toString().padStart(2, '0')} ${if (h < 12) "am" else "pm"}" }
fun instantAt(date: String, minutes: Int): String = Instant.parse("${date}T${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}:00+05:30").toString()

@Composable
fun CalendarScreen(loc: ProviderLocation, report: (String) -> Unit, onWalkIn: () -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("calendar-${loc.id}") { CalendarViewModel(app, loc) }
    val date = vm.date.collectAsStateValue()
    val state = vm.data.collectAsStateValue()
    var blocking by remember { mutableStateOf(false) }
    val label = if (date == istDate()) "Today · ${formatDay("${date}T06:00:00Z")}" else formatDay("${date}T06:00:00Z")

    Screen("Slots", scroll = false, footer = {
        PrimaryButton("Block time", { blocking = true }, trailing = null)
        SecondaryButton("Add walk-in", onWalkIn, Modifier.fillMaxWidth(), icon = Lucide.UserPlus)
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconBox(Lucide.ChevronLeft, onClick = { vm.shift(-1) }); Heading(label, 20); IconBox(Lucide.ChevronRight, onClick = { vm.shift(1) })
        }
        Column(Modifier.weight(1f).fillMaxWidth()) { Remote(state, vm::reload) { cal -> DayGrid(cal, loc, date) } }
    }
    if (blocking) BlockSheet(loc, date, onDismiss = { blocking = false }, onSaved = { blocking = false; vm.reload(); report("Blocked") })
}

@Composable
private fun DayGrid(cal: Calendar, loc: ProviderLocation, date: String) {
    val open = clockToMin(cal.openTime); val close = clockToMin(cal.closeTime)
    val startHour = open / 60; val endHour = (close + 59) / 60
    val bays = (1..cal.capacity).toList()
    val dead = setOf("CANCELLED", "REJECTED", "EXPIRED", "PENDING_PAYMENT")
    val booked = cal.orders.filter { it.status !in dead }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 44.dp)) { bays.forEach { b -> Body("Bay $b", size = 12, color = Ind.muted, modifier = Modifier.weight(1f).padding(bottom = 6.dp)) } }
        Box(Modifier.fillMaxWidth().height(((endHour - startHour) * HOUR).dp).clipToBounds()) {
            for (h in startHour until endHour) {
                Box(Modifier.offset(y = ((h - startHour) * HOUR).dp).fillMaxWidth().height(1.dp).background(Ind.divider))
                Body("$h:00".let { if (h > 12) "${h - 12}:00" else it }, size = 11, color = Ind.muted, modifier = Modifier.offset(y = ((h - startHour) * HOUR + 3).dp).width(40.dp))
            }
            BoxWithConstraints(Modifier.padding(start = 44.dp).fillMaxSize()) {
                val colW = maxWidth / cal.capacity
                fun y(min: Int) = (((min - startHour * 60).coerceAtLeast(0)) * HOUR / 60f).dp
                // closed time first, so bookings draw over nothing they cannot overlap
                cal.windows.forEach { w ->
                    val from = minutesOf(w.startsAt).coerceAtLeast(startHour * 60); val to = minutesOf(w.endsAt).let { if (it == 0) endHour * 60 else it }.coerceAtMost(endHour * 60)
                    if (to > from) (w.bays ?: bays).forEach { b ->
                        Box(Modifier.offset(colW * (b - 1), y(from)).width(colW - 3.dp).height(y(to) - y(from))) {
                            Hatch(Modifier.fillMaxSize(), base = Ind.n300)
                            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(3.dp)) { Icon(Lucide.Ban, null, Modifier.size(12.dp)); Body("${w.reason}${if (w.repeat != "NONE") " · repeats ${w.repeat.lowercase()}" else ""}", size = 10) }
                        }
                    }
                }
                booked.forEach { o ->
                    val from = minutesOf(o.startsAt); val to = minutesOf(o.endsAt).let { if (it <= from) from + cal.slotMinutes else it }
                    val b = (o.bay ?: 1).coerceIn(1, cal.capacity)
                    val inBay = o.status == "CHECKED_IN" || o.status == "IN_PROGRESS"
                    Column(
                        Modifier.offset(colW * (b - 1), y(from)).width(colW - 3.dp).height(y(to) - y(from) - 2.dp)
                            .background(if (o.source == "WALK_IN") Ind.n200 else Ind.a100, Ind.rSm).then(if (inBay) Modifier.border2(Ind.accent).pulsing(0.7f, 2600) else Modifier.hairline(if (o.source == "WALK_IN") Ind.n300 else Ind.a300)).padding(4.dp),
                    ) {
                        Body(o.assetLabel ?: o.customerName, size = 10, weight = FontWeight.SemiBold)
                        Body(if (inBay) "In bay" else if (o.source == "WALK_IN") "Walk-in" else o.lines.joinToString { it.name.substringBefore(" ") }, size = 10, color = Ind.muted)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("App booking", Ind.a100, Ind.a300); Legend("Walk-in", Ind.n200, Ind.n300); Legend("Closed", Ind.n300, Ind.n400)
        }
    }
}

private fun Modifier.border2(c: androidx.compose.ui.graphics.Color) = this.border(2.dp, c, Ind.rSm)

@Composable
private fun Legend(text: String, fill: androidx.compose.ui.graphics.Color, line: androidx.compose.ui.graphics.Color) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Box(Modifier.size(12.dp).background(fill, Ind.rSm).hairline(line)); Body(text, size = 11, color = Ind.muted) }

// ---- Block time ----

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BlockSheet(loc: ProviderLocation, date: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val app = LocalContainer.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("Maintenance") }
    var from by remember { mutableStateOf(13 * 60) }
    var to by remember { mutableStateOf(15 * 60) }
    var bays by remember { mutableStateOf<Set<Int>?>(null) }   // null = all
    var repeat by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ind.bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Heading("Block time", 24)
            Section("Reason") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Maintenance", "Lunch", "Staff short", "Other").forEach { r -> Choice(r, reason == r) { reason = r } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Stepper("From", minToClock(from), { from = (from - 30).coerceAtLeast(0) }, { from = (from + 30).coerceAtMost(to - 30) }) }
                Box(Modifier.weight(1f)) { Stepper("To", minToClock(to), { to = (to - 30).coerceAtLeast(from + 30) }, { to = (to + 30).coerceAtMost(24 * 60) }) }
            }
            Section("Bays") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("All", bays == null) { bays = null }
                    (1..loc.capacity).forEach { b -> Choice("Bay $b", bays?.contains(b) == true) { val cur = bays ?: emptySet(); val next = if (b in cur) cur - b else cur + b; bays = next.takeIf { it.isNotEmpty() } } }
                }
            }
            Section("Repeat") { Seg(listOf("Once", "Every day", "Every week"), repeat) { repeat = it } }
            error?.let { msg -> Row(Modifier.fillMaxWidth().background(Ind.a100, Ind.rSm).hairline(Ind.accent).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Lucide.TriangleAlert, null, Modifier.size(20.dp), tint = Ind.a800); Body(msg, size = 13, color = Ind.a800) } }
            PrimaryButton(if (busy) "Blocking…" else "Block ${minToClock(from)} – ${minToClock(to)}", {
                busy = true; error = null
                scope.launch {
                    try { app.provider.addBlock(loc.id, instantAt(date, from), instantAt(date, to), reason, bays?.sorted(), listOf("NONE", "DAILY", "WEEKLY")[repeat]); onSaved() }
                    catch (e: Exception) { error = e.friendly() } finally { busy = false }
                }
            }, enabled = !busy && to > from)
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Body(label, size = 12, color = Ind.muted)
        Row(Modifier.fillMaxWidth().height(44.dp).hairline(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onMinus), Alignment.Center) { Heading("−", 22) }
            Box(Modifier.weight(1f), Alignment.Center) { Body(value, size = 15, weight = FontWeight.Medium) }
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onPlus), Alignment.Center) { Heading("+", 22) }
        }
    }
}
