package com.odyssey.adminops

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// The admin app: the platform team's phone companion to the web console. It covers what needs doing on the move:
// the numbers, vendor verification, cancelling and refunding bookings, and customer reports. Payout runs, commission
// rules and marketplace settings stay on the web console, where there is room to check them.

private enum class ATab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OVERVIEW("Overview", Lucide.LayoutDashboard), VENDORS("Vendors", Lucide.Store), BOOKINGS("Bookings", Lucide.CalendarDays), SUPPORT("Support", Lucide.LifeBuoy), ACCOUNT("Account", Lucide.User)
}

private sealed interface ARoute {
    data object Tabs : ARoute
    data class Vendor(val id: String) : ARoute
    data class Booking(val order: Order) : ARoute
    data class Report(val ticket: Ticket) : ARoute
}

/** Which verification steps are open from each status, what each one does, and whether it needs a note. */
internal data class Step(val action: String, val label: String, val explain: String, val note: Boolean = false, val danger: Boolean = false, val primary: Boolean = false)
private val STEPS = mapOf(
    "SUBMITTED" to listOf(Step("REVIEW", "Start review", "Marks the application as being reviewed, so the team knows it is taken.", primary = true), Step("REJECT", "Reject", "Closes the application. The vendor is told why and cannot go live.", note = true, danger = true)),
    "UNDER_REVIEW" to listOf(Step("APPROVE", "Approve", "Documents and bank details check out. The vendor can then be taken live.", primary = true), Step("REQUEST_CHANGES", "Request changes", "Sends the application back with your note so the vendor can fix it.", note = true), Step("REJECT", "Reject", "Closes the application. The vendor is told why and cannot go live.", note = true, danger = true)),
    "CHANGES_REQUESTED" to listOf(Step("REVIEW", "Back to review", "Use this once the vendor has sent the corrections.", primary = true)),
    "APPROVED" to listOf(Step("GO_LIVE", "Go live", "Lists every open outlet in the customer app straight away.", primary = true)),
    "LIVE" to listOf(Step("SUSPEND", "Suspend", "Hides the vendor from customers at once. Existing bookings stay.", note = true, danger = true)),
    "SUSPENDED" to listOf(Step("REINSTATE", "Reinstate", "Lists the vendor for customers again.", primary = true)),
)
private val DOC_NAMES = mapOf("GST" to "GST certificate", "PAN" to "PAN card", "BANK" to "Bank proof", "TRADE_LICENCE" to "Trade licence", "TRADE_LICENSE" to "Trade licence")
private fun docName(t: String) = DOC_NAMES[t.uppercase()] ?: t.titleCase()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AdminHome(user: User, onSignOut: () -> Unit) {
    var tab by remember { mutableStateOf(ATab.OVERVIEW) }
    val nav = remember { Navigator<ARoute>(ARoute.Tabs) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val report: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }
    var vendorFilter by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = nav.canPop || tab != ATab.OVERVIEW) { if (!nav.pop()) tab = ATab.OVERVIEW }

    Box(Modifier.fillMaxSize().background(Ind.bg)) {
        when (val r = nav.current) {
            ARoute.Tabs -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        ATab.OVERVIEW -> OverviewTab(user, onVendor = { nav.push(ARoute.Vendor(it)) }, onReview = { vendorFilter = "SUBMITTED"; tab = ATab.VENDORS }, onSupport = { tab = ATab.SUPPORT })
                        ATab.VENDORS -> VendorsTab(vendorFilter, onFilter = { vendorFilter = it }, onOpen = { nav.push(ARoute.Vendor(it)) })
                        ATab.BOOKINGS -> BookingsTab(onOpen = { nav.push(ARoute.Booking(it)) })
                        ATab.SUPPORT -> SupportTab(onOpen = { nav.push(ARoute.Report(it)) })
                        ATab.ACCOUNT -> AccountTab(user, onSignOut)
                    }
                }
                BottomTabs(ATab.entries.map { TabItem(it.label, it.icon) }, tab.ordinal) { tab = ATab.entries[it] }
            }
            is ARoute.Vendor -> VendorDetail(r.id, onBack = { nav.pop() }, report = report)
            is ARoute.Booking -> BookingDetail(r.order, onBack = { nav.pop() }, report = report)
            is ARoute.Report -> TicketDetail(r.ticket, onBack = { nav.pop() }, onDone = { nav.pop(); report(it) }, report = report)
        }
        IndSnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
}

// ---- Overview ----

class DashboardViewModel(private val app: AppContainer) : BaseViewModel() {
    val range = MutableStateFlow("7d")
    val state = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    init { reload() }
    fun reload() { val r = range.value; state.load { app.adminOps.dashboard(r) } }
    fun setRange(r: String) { range.value = r; state.value = Load.Loading; reload() }
}

@Composable
private fun OverviewTab(user: User, onVendor: (String) -> Unit, onReview: () -> Unit, onSupport: () -> Unit) {
    val app = LocalContainer.current
    val labels = LocalTenant.current.config.labels
    val vm = screenModel("a-dash") { DashboardViewModel(app) }
    val s = vm.state.collectAsStateValue()
    val range = vm.range.collectAsStateValue()
    val ranges = listOf("7d" to "7 days", "30d" to "30 days", "90d" to "90 days")
    Screen("Overview", subtitle = "Hello, ${user.name.substringBefore(" ")}. Here is how the marketplace is doing.", actions = { IconBox(Lucide.RefreshCw, onClick = vm::reload) }) {
        Seg(ranges.map { it.second }, ranges.indexOfFirst { it.first == range }.coerceAtLeast(0)) { vm.setRange(ranges[it].first) }
        Remote(s, vm::reload) { d ->
            StatGrid(listOf(
                Stat("Gross value (GMV)", formatMoney(d.gmvMinor), d.gmvDeltaPct?.let { "${if (it >= 0) "▲" else "▼"} ${kotlin.math.abs(it)}% vs before" }),
                Stat("${labels.order}s", d.bookings.toString(), "${d.bookingsPerDay} a day"),
                Stat("Take rate", formatPercent(d.takeRateBps), "Commission over GMV"),
                Stat("Utilisation", "${d.utilisationPct}%", "Booked share of slots"),
                Stat("Cancellations", "${d.cancellationPct}%", "${d.noShowPct}% no-shows", alert = d.cancellationPct >= 15),
                Stat("${labels.provider}s live", d.vendorsLive.toString(), "${d.vendorsInReview} waiting for review"),
            ))
            if (d.series.any { it.gmvMinor > 0 }) Section("GMV by day") { Bars(d.series) }
            Section("Needs attention") {
                if (d.alerts.isEmpty()) Notice("All clear. Nothing is waiting on the platform team.", NoticeTone.GOOD)
                d.alerts.forEach { a ->
                    val bad = Regex("flag|fail|suspend", RegexOption.IGNORE_CASE).containsMatchIn(a.title)
                    val isTicket = Regex("ticket", RegexOption.IGNORE_CASE).containsMatchIn(a.title)
                    Row(Modifier.fillMaxWidth().hairline().clickable { if (isTicket) onSupport() else if (a.kind == "VERIFICATION") onReview() else d.topVendors.firstOrNull { a.title.startsWith(it.name) }?.let { onVendor(it.providerId) } ?: onReview() }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (bad) Lucide.TriangleAlert else if (isTicket) Lucide.LifeBuoy else Lucide.ShieldCheck, null, Modifier.size(18.dp), tint = if (bad) Ind.danger else Ind.a700)
                        Column(Modifier.weight(1f)) { Body(a.title, weight = FontWeight.Medium); Body(a.meta, size = 12, color = Ind.muted) }
                        Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = Ind.mutedSoft)
                    }
                }
            }
            if (d.topVendors.isNotEmpty()) Section("Top ${labels.provider.lowercase()}s") {
                d.topVendors.forEach { v ->
                    Row(Modifier.fillMaxWidth().bottomHairline().clickable { onVendor(v.providerId) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Body(v.name, weight = FontWeight.Medium); Body("${v.bookings} bookings · ★ ${v.ratingAvg} · ${v.cancelPct}% cancelled", size = 12, color = if (v.cancelPct >= 15) Ind.danger else Ind.muted) }
                        Body(formatMoney(v.gmvMinor), weight = FontWeight.SemiBold)
                    }
                }
            }
            Notice("Payout runs, commission rules and marketplace settings are on the web console.")
        }
    }
}

@Composable
private fun Bars(series: List<DayPoint>) {
    val max = (series.maxOfOrNull { it.gmvMinor } ?: 0L).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width / series.size
            series.forEachIndexed { i, p ->
                val h = size.height * p.gmvMinor / max
                drawRect(Ind.accent, Offset(i * w + w * 0.18f, size.height - h), Size(w * 0.64f, h.coerceAtLeast(if (p.gmvMinor > 0) 2f else 0f)))
            }
            drawLine(Ind.divider, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Body(series.first().date.drop(5), size = 11, color = Ind.muted); Body("Peak ${formatMoney(max)}", size = 11, color = Ind.muted); Body(series.last().date.drop(5), size = 11, color = Ind.muted)
        }
    }
}

// ---- Vendors ----

class VendorsViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<Provider>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.adminOps.providers(null) } }
}

@Composable
private fun VendorsTab(filter: String?, onFilter: (String?) -> Unit, onOpen: (String) -> Unit) {
    val app = LocalContainer.current
    val labels = LocalTenant.current.config.labels
    val vm = screenModel("a-vendors") { VendorsViewModel(app) }
    val list = vm.list.collectAsStateValue()
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.reload() }
    Screen("${labels.provider}s", subtitle = "Verify, take live and manage", actions = { IconBox(Lucide.RefreshCw, onClick = vm::reload) }) {
        SearchField(q, { q = it }, "Name, owner, phone or area")
        val all = (list as? Load.Ready)?.value ?: emptyList()
        val filters = listOf(null to "All", "SUBMITTED" to "Submitted", "UNDER_REVIEW" to "In review", "LIVE" to "Live", "SUSPENDED" to "Suspended")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { (k, l) -> Choice("$l ${if (k == null) all.size else all.count { it.status == k }}", filter == k) { onFilter(k) } }
        }
        Remote(list, vm::reload) { vendors ->
            val needle = q.trim().lowercase()
            val rows = vendors.filter { filter == null || it.status == filter }.filter { needle.isEmpty() || "${it.name} ${it.ownerName} ${it.ownerPhone} ${it.locations.joinToString { l -> l.address }}".lowercase().contains(needle) }
            if (rows.isEmpty()) EmptyState(Lucide.Store, if (needle.isNotEmpty()) "No matches for \"$q\"" else if (filter == "SUBMITTED") "Nothing waiting for review" else "No ${labels.provider.lowercase()}s here", if (filter == "SUBMITTED") "New applications show up here." else null)
            rows.forEach { p ->
                Row(Modifier.fillMaxWidth().bottomHairline().clickable { onOpen(p.id) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlueprintArt(Modifier.size(48.dp).clip(Ind.rSm), seed = p.id, showCar = false, monogram = initialsOf(p.name))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Body(p.name, size = 15, weight = FontWeight.SemiBold)
                        Body("${p.ownerName ?: ""} · ${p.ownerPhone?.let(::formatPhone) ?: ""}", size = 12, color = Ind.muted)
                        val rated = p.locations.filter { it.ratingCount > 0 }
                        Body(if (rated.isEmpty()) "New · ${p.locations.size} outlet${if (p.locations.size == 1) "" else "s"}" else "★ ${rated.first().ratingAvg} · ${p.locations.size} outlet${if (p.locations.size == 1) "" else "s"}", size = 12, color = Ind.muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusBadge(p.status.titleCase(), p.status)
                        if (p.payoutHold) Tag("Payouts held", TagKind.DANGER)
                    }
                }
            }
        }
    }
}

class VendorDetailViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val state = MutableStateFlow<Load<Provider>>(Load.Loading)
    init { reload() }
    fun reload() { state.load { app.adminOps.provider(id) } }
    private fun update(report: (String) -> Unit, done: String, block: suspend () -> Provider) = run(report) { state.value = Load.Ready(block()); report(done) }
    internal fun step(s: Step, note: String?, report: (String) -> Unit) = update(report, "${s.label}: done") { app.adminOps.step(id, s.action, note) }
    fun commission(bps: Int?, report: (String) -> Unit) = update(report, "Commission saved. It applies to new bookings.") { app.adminOps.commission(id, bps) }
    fun hold(on: Boolean, report: (String) -> Unit) = update(report, if (on) "Payouts held" else "Payout hold released") { app.adminOps.hold(id, on) }
    fun doc(type: String, verified: Boolean, note: String?, report: (String) -> Unit) = update(report, if (verified) "${docName(type)} verified" else "Sent back for a fix") { app.adminOps.reviewDocument(id, type, verified, note) }
}

@Composable
private fun VendorDetail(id: String, onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val vm = screenModel("a-vendor-$id") { VendorDetailViewModel(app, id) }
    val s = vm.state.collectAsStateValue()
    var confirm by remember { mutableStateOf<Step?>(null) }
    var fixDoc by remember { mutableStateOf<String?>(null) }
    var holdAsk by remember { mutableStateOf(false) }
    var commission by remember { mutableStateOf<String?>(null) }
    val p = (s as? Load.Ready)?.value

    val steps = p?.let { STEPS[it.status] }.orEmpty()
    val footer: (@Composable ColumnScope.() -> Unit)? = if (steps.isEmpty()) null else {
        {
            val secondary = steps.filter { !it.primary }
            if (secondary.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { secondary.forEach { st -> SecondaryButton(st.label, { confirm = st }, Modifier.weight(1f), danger = st.danger) } }
            steps.filter { it.primary }.forEach { st -> PrimaryButton(st.label, { confirm = st }) }
        }
    }
    Screen(p?.name ?: "Vendor", onBack = onBack, subtitle = p?.let { "Joined ${formatDay(it.createdAt).substringAfter(", ")}" }, footer = footer) {
        Remote(s, vm::reload) { pv ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusBadge(pv.status.titleCase(), pv.status); if (pv.payoutHold) Tag("Payouts held", TagKind.DANGER) }
            pv.reviewNote?.let { Notice("Last note to the vendor: $it", NoticeTone.WARN) }
            val checks = listOf("Owner details" to (pv.ownerName != null), "At least one outlet" to pv.locations.isNotEmpty(), "Documents verified" to (pv.documents.isNotEmpty() && pv.documents.all { it.state == "VERIFIED" }), "Bank account for payouts" to (pv.bank != null))
            Section("Onboarding · ${checks.count { it.second }} of ${checks.size}") {
                checks.forEach { (t, ok) -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (ok) Lucide.CircleCheck else Lucide.Circle, null, Modifier.size(18.dp), tint = if (ok) Ind.a700 else Ind.mutedSoft); Body(t, color = if (ok) Ind.text else Ind.muted) } }
            }
            Section("Documents") {
                if (pv.documents.isEmpty()) Body("No documents yet. The vendor adds them from their app.", size = 13, color = Ind.muted)
                pv.documents.forEach { d ->
                    Column(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Body(docName(d.type), weight = FontWeight.Medium); Body(d.reference, size = 12, color = Ind.muted) }
                            Tag(if (d.state == "VERIFIED") "Verified" else if (d.state == "NEEDS_FIX") "Needs a fix" else "Not checked", if (d.state == "VERIFIED") TagKind.ACCENT else if (d.state == "NEEDS_FIX") TagKind.DANGER else TagKind.NEUTRAL)
                        }
                        d.note?.let { Body(it, size = 12, color = Ind.danger) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d.state != "VERIFIED") SecondaryButton("Verify", { vm.doc(d.type, true, null, report) }, icon = Lucide.Check)
                            if (d.state != "NEEDS_FIX") SecondaryButton("Needs a fix", { fixDoc = d.type })
                        }
                    }
                }
            }
            Section("Owner") {
                Row(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Body(pv.ownerName ?: "-", weight = FontWeight.Medium); Body(pv.ownerPhone?.let(::formatPhone) ?: "", size = 12, color = Ind.muted) }
                    pv.ownerPhone?.let { ph -> IconBox(Lucide.Phone, onClick = { platform.call(ph) }) }
                }
            }
            Section("Outlets") {
                pv.locations.forEach { l ->
                    Column(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Body(l.name, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Tag(if (l.open) "Taking bookings" else "Paused", if (l.open) TagKind.ACCENT else TagKind.NEUTRAL) }
                        Body(l.address, size = 12, color = Ind.muted)
                        Body("${l.openTime} – ${l.closeTime} · ${l.capacity} bays · ${l.slotMinutes} min slots${if (l.ratingCount > 0) " · ★ ${l.ratingAvg} (${l.ratingCount})" else ""}", size = 12, color = Ind.muted)
                    }
                }
            }
            Section("Commission") {
                val text = commission ?: pv.commissionBps?.let { (it / 100.0).toString().removeSuffix(".0") } ?: ""
                val pct = text.toDoubleOrNull()
                val bad = text.isNotBlank() && (pct == null || pct < 0 || pct > 50)
                Field("Percent of the service amount", text, { commission = it.filter { c -> c.isDigit() || c == '.' } }, keyboard = KeyboardType.Decimal, placeholder = "Marketplace default",
                    supporting = if (bad) "Enter a number from 0 to 50" else "Leave empty for the default. Applies to new bookings only.")
                if (commission != null) PrimaryButton("Save commission", { vm.commission(pct?.let { (it * 100).toInt() }, report); commission = null }, enabled = !bad, height = 44)
            }
            Section("Payouts") {
                Labeled("Bank account", pv.bank?.let { "${it.accountNumberMasked} · ${it.ifsc}" } ?: "Not added yet")
                Labeled("Payouts", if (pv.payoutHold) "On hold" else "Normal")
                SecondaryButton(if (pv.payoutHold) "Release hold" else "Hold payouts", { holdAsk = true }, danger = !pv.payoutHold, icon = if (pv.payoutHold) Lucide.Play else Lucide.Pause)
            }
        }
    }
    confirm?.let { st ->
        ConfirmDialog("${st.label}: ${p?.name ?: ""}?", st.explain, st.label, { reason -> confirm = null; vm.step(st, reason.ifBlank { null }, report) }, { confirm = null },
            danger = st.danger, reasonLabel = if (st.note) "Note for the vendor" else null, reasonHint = "The vendor sees this note.")
    }
    fixDoc?.let { t -> ConfirmDialog("${docName(t)} needs a fix", "The vendor is asked to upload it again.", "Send to vendor", { note -> fixDoc = null; vm.doc(t, false, note, report) }, { fixDoc = null }, reasonLabel = "What should they correct?") }
    if (holdAsk && p != null) ConfirmDialog(
        if (p.payoutHold) "Release the payout hold?" else "Hold payouts to ${p.name}?",
        if (p.payoutHold) "Their pending earnings go out with the next payout run." else "Their earnings keep building up but are left out of payout runs until you release the hold. Bookings are not affected.",
        if (p.payoutHold) "Release hold" else "Hold payouts", { holdAsk = false; vm.hold(!p.payoutHold, report) }, { holdAsk = false }, danger = !p.payoutHold,
    )
}

// ---- Bookings ----

class AdminOrdersViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.adminOps.orders(null) } }
}

private fun laneOf(o: Order) = when (o.status) {
    "PAID" -> "waiting"
    "CONFIRMED", "CHECKED_IN", "IN_PROGRESS" -> "active"
    "COMPLETED" -> "done"
    "PENDING_PAYMENT" -> "unpaid"
    else -> "closed"
}

@Composable
private fun BookingsTab(onOpen: (Order) -> Unit) {
    val app = LocalContainer.current
    val labels = LocalTenant.current.config.labels
    val vm = screenModel("a-orders") { AdminOrdersViewModel(app) }
    val list = vm.list.collectAsStateValue()
    var q by remember { mutableStateOf("") }
    var lane by remember { mutableStateOf("all") }
    LaunchedEffect(Unit) { vm.reload() }
    Screen("${labels.order}s", subtitle = "The latest 200 across the marketplace", actions = { IconBox(Lucide.RefreshCw, onClick = vm::reload) }) {
        SearchField(q, { q = it }, "Code, customer, phone or outlet")
        val all = (list as? Load.Ready)?.value ?: emptyList()
        val lanes = listOf("all" to "All", "waiting" to "Waiting", "active" to "Upcoming", "done" to "Completed", "closed" to "Cancelled")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lanes.forEach { (k, l) -> Choice("$l ${if (k == "all") all.size else all.count { laneOf(it) == k }}", lane == k) { lane = k } }
        }
        Remote(list, vm::reload) { orders ->
            val needle = q.trim().lowercase()
            val rows = orders.filter { lane == "all" || laneOf(it) == lane }.filter { needle.isEmpty() || "${it.code} ${it.customerName} ${it.customerPhone} ${it.locationName}".lowercase().contains(needle) }
            if (rows.isEmpty()) EmptyState(Lucide.CalendarDays, if (needle.isNotEmpty()) "No matches for \"$q\"" else "No ${labels.order.lowercase()}s here", "Try another status.")
            rows.forEach { o ->
                Row(Modifier.fillMaxWidth().bottomHairline().clickable { onOpen(o) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Body(displayCode(o.code), weight = FontWeight.SemiBold); if (o.source == "WALK_IN") Tag("Walk-in", TagKind.NEUTRAL) }
                        Body("${o.customerName} · ${o.locationName}", size = 12, color = Ind.muted)
                        Body(formatDateTime(o.startsAt), size = 12, color = Ind.muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { Body(formatMoney(o.totalMinor), weight = FontWeight.SemiBold); StatusBadge(o.statusLabel, o.status) }
                }
            }
        }
    }
}

@Composable
private fun BookingDetail(order: Order, onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val platform = LocalPlatform.current
    val tenant = LocalTenant.current.config
    val scope = rememberCoroutineScope()
    var o by remember { mutableStateOf(order) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Screen(displayCode(o.code), onBack = onBack, subtitle = o.locationName, footer = if ("ADMIN_CANCEL" in o.actions) ({
        SecondaryButton(if (busy) "Cancelling…" else "Cancel and refund ${formatMoney(o.totalMinor)}", { asking = true }, Modifier.fillMaxWidth(), danger = true, enabled = !busy, icon = Lucide.Ban)
    }) else null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusBadge(o.statusLabel, o.status); Tag(if (o.source == "WALK_IN") "Walk-in" else "Booked in app", TagKind.NEUTRAL) }
        Section("Appointment") {
            Labeled("When", formatDateTime(o.startsAt)); Labeled("Ends", formatTime(o.endsAt))
            Labeled(tenant.variants.name, tenant.variants.options.firstOrNull { it.key == o.variantKey }?.label ?: o.variantKey.titleCase())
            o.assetLabel?.let { Labeled("Vehicle", it) }
        }
        Section("Customer") {
            Row(Modifier.fillMaxWidth().hairline().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Body(o.customerName, weight = FontWeight.Medium); Body(formatPhone(o.customerPhone), size = 12, color = Ind.muted) }
                if (o.customerPhone.isNotBlank()) IconBox(Lucide.Phone, onClick = { platform.call(o.customerPhone) })
            }
        }
        Section("Charges") {
            o.lines.forEach { Labeled("${it.name} · ${it.durationMinutes} min", formatMoney(it.priceMinor)) }
            Labeled("Tax", formatMoney(o.taxMinor)); Labeled("Convenience fee", formatMoney(o.feeMinor))
            if (o.discountMinor > 0) Labeled("Discount${o.promoCode?.let { " ($it)" } ?: ""}", "− ${formatMoney(o.discountMinor)}")
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ind.divider))
            Labeled("Customer paid", formatMoney(o.totalMinor), strong = true)
            Labeled("Commission${o.commissionBps?.let { " (${formatPercent(it)})" } ?: ""}", formatMoney(o.commissionMinor))
            Labeled("Vendor's share of service", formatMoney(o.subtotalMinor - (o.commissionMinor ?: 0)))
        }
        o.cancellationReason?.let { Section("Cancellation") { Body(it) } }
    }
    if (asking) ConfirmDialog(
        "Cancel ${displayCode(o.code)} and refund?", "${o.customerName} gets ${formatMoney(o.totalMinor)} back in full, the slot opens again and the vendor is told. This cannot be undone.",
        "Cancel and refund", { reason ->
            asking = false; busy = true
            scope.launch { try { o = app.adminOps.cancel(o.id, reason); report("Cancelled and refunded") } catch (e: Exception) { report(e.friendly()) } finally { busy = false } }
        }, { asking = false }, danger = true, reasonLabel = "Reason", reasonHint = "Shown to the customer and the vendor, and kept in the audit log.",
    )
}

// ---- Support ----

class TicketsViewModel(private val app: AppContainer) : BaseViewModel() {
    val openOnly = MutableStateFlow(true)
    val list = MutableStateFlow<Load<List<Ticket>>>(Load.Loading)
    init { reload() }
    fun reload() { val o = openOnly.value; list.load { app.adminOps.tickets(o) } }
    fun show(open: Boolean) { openOnly.value = open; list.value = Load.Loading; reload() }
}

@Composable
private fun SupportTab(onOpen: (Ticket) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("a-tickets") { TicketsViewModel(app) }
    val open = vm.openOnly.collectAsStateValue()
    val list = vm.list.collectAsStateValue()
    LaunchedEffect(Unit) { vm.reload() }
    Screen("Support", subtitle = "Customer reports. We reply within 24 hours.", actions = { IconBox(Lucide.RefreshCw, onClick = vm::reload) }) {
        Seg(listOf("Open", "All"), if (open) 0 else 1) { vm.show(it == 0) }
        Remote(list, vm::reload) { tickets ->
            if (tickets.isEmpty()) EmptyState(Lucide.CircleCheck, if (open) "Inbox zero" else "No reports yet", if (open) "No open reports. Nice work." else null)
            tickets.sortedBy { it.createdAt }.forEach { t ->
                val overdue = t.status == "OPEN" && (t.createdAt.toInstantOrNull()?.let { (kotlin.time.Clock.System.now() - it).inWholeHours >= 24 } ?: false)
                Column(Modifier.fillMaxWidth().bottomHairline().clickable { onOpen(t) }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Body(t.category.titleCase(), weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (overdue) Tag("Over 24 h", TagKind.DANGER)
                        StatusBadge(t.status.titleCase(), if (t.status == "OPEN") "PENDING" else if (t.status == "UPHELD") "COMPLETED" else "CANCELLED")
                    }
                    Body(t.description, size = 13, color = Ind.text, modifier = Modifier.heightIn(max = 40.dp))
                    Body("${t.customerName ?: ""} · ${t.orderCode?.let(::displayCode) ?: ""} · ${t.locationName ?: ""} · ${relativeTime(t.createdAt)}", size = 12, color = Ind.muted)
                }
            }
        }
    }
}

@Composable
private fun TicketDetail(t: Ticket, onBack: () -> Unit, onDone: (String) -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val scope = rememberCoroutineScope()
    val total = t.totalMinor ?: 0
    var refund by remember { mutableStateOf((total / 100).toString()) }
    var note by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    val refundMinor = (refund.toLongOrNull() ?: -1) * 100
    val refundBad = refundMinor < 0 || refundMinor > total
    Screen("Report on ${t.orderCode?.let(::displayCode) ?: "booking"}", onBack = onBack, subtitle = "${t.category.titleCase()} · ${relativeTime(t.createdAt)}", footer = if (t.status == "OPEN") ({
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Dismiss", { asking = false }, Modifier.weight(1f), enabled = note.isNotBlank() && !busy)
            Box(Modifier.weight(2f)) { PrimaryButton(if (refundMinor > 0) "Uphold · refund ${formatMoney(refundMinor)}" else "Uphold", { asking = true }, enabled = note.isNotBlank() && !refundBad && !busy, height = 44) }
        }
    }) else null) {
        Section("What the customer said") { Body(t.description, size = 15) }
        if (t.photos.isNotEmpty()) Body("${t.photos.size} photo${if (t.photos.size == 1) "" else "s"} attached. Open the report on the web console to see them.", size = 12, color = Ind.muted)
        Section("Booking") { Labeled("Customer", t.customerName ?: "-"); Labeled("Outlet", t.locationName ?: "-"); Labeled("Booking total", formatMoney(total)) }
        if (t.status == "OPEN") Section("Resolution") {
            Field("Refund to the customer (₹)", refund, { refund = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number, supporting = if (refundBad) "Between ₹0 and ${formatMoney(total)}" else "Only if you uphold. It comes out of the vendor's earnings.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("No refund" to 0L, "Half" to total / 2, "Full" to total).forEach { (l, v) -> Choice(l, refundMinor == v / 100 * 100) { refund = (v / 100).toString() } } }
            Field("Note for the customer", note, { note = it }, multiline = true, supporting = "Required. What you found and what you decided.")
        } else Section("Resolution") { Body(t.resolutionNote ?: "-"); if (t.refundMinor > 0) Body("Refunded ${formatMoney(t.refundMinor)}.", size = 13, color = Ind.muted) }
    }
    asking?.let { uphold ->
        ConfirmDialog(
            if (uphold) "Uphold this report?" else "Dismiss this report?",
            if (uphold) (if (refundMinor > 0) "${t.customerName ?: "The customer"} is refunded ${formatMoney(refundMinor)} from the vendor's earnings and sees your note." else "The customer sees your note. No money moves.") else "The customer sees your note. No money moves.",
            if (uphold) "Uphold" else "Dismiss", {
                asking = null; busy = true
                scope.launch { try { app.adminOps.resolve(t.id, uphold, if (uphold) refundMinor else 0, note); onDone(if (uphold) "Report upheld" else "Report dismissed") } catch (e: Exception) { report(e.friendly()) } finally { busy = false } }
            }, { asking = null },
        )
    }
}

// ---- Account ----

@Composable
private fun AccountTab(user: User, onSignOut: () -> Unit) {
    val tenant = LocalTenant.current
    var asking by remember { mutableStateOf(false) }
    Screen("Account") {
        Row(Modifier.fillMaxWidth().hairline().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Ind.a100, CircleShape), Alignment.Center) { Heading(initialsOf(user.name), 18, color = Ind.a800) }
            Column { Heading(user.name, 20); Body("${user.role.name.titleCase()} · ${formatPhone(user.phone)}", size = 12, color = Ind.muted) }
        }
        Section(tenant.tenant.name) {
            Labeled("Marketplace", tenant.tenant.name); Labeled("Vertical", tenant.tenant.vertical.titleCase())
        }
        Notice("Payout runs, commission rules, customers, promos and marketplace settings are on the web console.")
        SettingRow(Lucide.LogOut, "Sign out", danger = true, onClick = { asking = true })
    }
    if (asking) ConfirmDialog("Sign out?", "You will need a new code to sign in again.", "Sign out", { asking = false; onSignOut() }, { asking = false })
}
