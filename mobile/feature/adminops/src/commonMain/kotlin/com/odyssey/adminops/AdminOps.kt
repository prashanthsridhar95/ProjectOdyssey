package com.odyssey.adminops

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
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val glyph: String) { DASHBOARD("Dashboard", "📊"), VENDORS("Vendors", "🏪"), ORDERS("Bookings", "📋") }

/** Which verification steps are open from each status. Mirrors the web admin panel and the server's workflow. */
private val STEPS = mapOf(
    "SUBMITTED" to listOf("REVIEW" to "Start review", "REJECT" to "Reject"),
    "UNDER_REVIEW" to listOf("APPROVE" to "Approve", "REQUEST_CHANGES" to "Request changes", "REJECT" to "Reject"),
    "CHANGES_REQUESTED" to listOf("REVIEW" to "Back to review"),
    "APPROVED" to listOf("GO_LIVE" to "Go live"),
    "LIVE" to listOf("SUSPEND" to "Suspend"),
    "SUSPENDED" to listOf("REINSTATE" to "Reinstate"),
)
private val NEEDS_NOTE = setOf("REJECT", "REQUEST_CHANGES", "SUSPEND")

@Composable
fun AdminHome(user: User, onSignOut: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var openVendor by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val report: (String) -> Unit = { m -> scope.launch { snack.showSnackbar(m) } }

    openVendor?.let { VendorDetail(it, onBack = { openVendor = null }, report = report); return }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { NavigationBar { Tab.entries.forEach { t -> NavigationBarItem(tab == t, { tab = t }, { Text(t.glyph) }, label = { Text(t.label) }) } } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Admin", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(user.name, fontSize = 12.sp) }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
            when (tab) {
                Tab.DASHBOARD -> DashboardTab()
                Tab.VENDORS -> VendorsTab(onOpen = { openVendor = it })
                Tab.ORDERS -> OrdersTab(report)
            }
        }
    }
}

class DashboardViewModel(app: AppContainer, private val loader: suspend () -> Dashboard = { app.adminOps.dashboard() }) : BaseViewModel() {
    val state = MutableStateFlow<Load<Dashboard>>(Load.Loading)
    init { reload() }
    fun reload() { state.load { loader() } }
}

@Composable
private fun DashboardTab() {
    val app = LocalContainer.current
    val vm = screenModel("a-dash") { DashboardViewModel(app) }
    val s = vm.state.collectAsStateValue()
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Remote(s, vm::reload) { d ->
            SurfaceCard {
                Labeled("Bookings today", d.ordersToday.toString()); Labeled("Bookings in total", d.ordersTotal.toString())
                Labeled("Gross value (GMV)", formatMoney(d.gmvMinor)); Labeled("Commission earned", formatMoney(d.commissionMinor))
                Labeled("Completed", d.completed.toString()); Labeled("Cancelled or rejected", d.cancelled.toString()); Labeled("Customers", d.customers.toString())
            }
            Section("Vendors by status") { d.providersByStatus.forEach { (k, v) -> SurfaceCard { Labeled(k.titleCase(), v.toString()) } } }
        }
    }
}

class VendorsViewModel(private val app: AppContainer) : BaseViewModel() {
    val status = MutableStateFlow<String?>(null)
    val list = MutableStateFlow<Load<List<Provider>>>(Load.Loading)
    init { reload() }
    fun reload() { val s = status.value; list.load { app.adminOps.providers(s) } }
    fun filter(s: String?) { status.value = s; reload() }
}

@Composable
private fun VendorsTab(onOpen: (String) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("a-vendors") { VendorsViewModel(app) }
    val status = vm.status.collectAsStateValue()
    val list = vm.list.collectAsStateValue()
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("All", status == null) { vm.filter(null) }; Chip("Submitted", status == "SUBMITTED") { vm.filter("SUBMITTED") }; Chip("Live", status == "LIVE") { vm.filter("LIVE") } }
        Remote(list, vm::reload) { vendors ->
            if (vendors.isEmpty()) EmptyNote("No vendors here.")
            vendors.forEach { p ->
                SurfaceCard(onClick = { onOpen(p.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(p.name, fontWeight = FontWeight.SemiBold); StatusBadge(p.status.titleCase(), p.status) }
                    Text("${p.ownerName ?: ""} ${p.ownerPhone ?: ""}", fontSize = 13.sp)
                    Text("Commission: ${if (p.commissionBps == null) "default" else formatPercent(p.commissionBps)}", fontSize = 12.sp)
                }
            }
        }
    }
}

class VendorDetailViewModel(private val app: AppContainer, private val id: String) : BaseViewModel() {
    val state = MutableStateFlow<Load<Provider>>(Load.Loading)
    init { reload() }
    fun reload() { state.load { app.adminOps.provider(id) } }
    fun step(action: String, note: String?, onError: (String) -> Unit) = run(onError) { app.adminOps.step(id, action, note); reload() }
    fun commission(bps: Int?, onError: (String) -> Unit) = run(onError) { app.adminOps.commission(id, bps); reload() }
}

@Composable
private fun VendorDetail(id: String, onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("a-vendor-$id") { VendorDetailViewModel(app, id) }
    val s = vm.state.collectAsStateValue()
    var noteFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var commission by remember { mutableStateOf<String?>(null) }

    Screen("Vendor", onBack = onBack) {
        Remote(s, vm::reload) { p ->
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(p.name, fontWeight = FontWeight.Bold, fontSize = 20.sp); StatusBadge(p.status.titleCase(), p.status) }
                Text("${p.ownerName ?: ""} · ${p.ownerPhone ?: ""}")
                p.reviewNote?.let { Text("Last note: $it", fontSize = 13.sp) }
                p.locations.forEach { Text("${it.name} · ${it.capacity} at a time · ${it.openTime}–${it.closeTime}", fontSize = 13.sp) }
                p.documents.forEach { Text("${it.type.titleCase()}: ${it.reference}", fontSize = 13.sp) }
            }
            Section("Verification") {
                (STEPS[p.status] ?: emptyList()).forEach { (a, label) ->
                    if (a in NEEDS_NOTE) SecondaryButton(label, { noteFor = a to label }, danger = a != "REQUEST_CHANGES") else PrimaryButton(label, { vm.step(a, null, report) })
                }
            }
            Section("Commission") {
                Field("Percent of service amount", commission ?: p.commissionBps?.let { (it / 100.0).toString().removeSuffix(".0") } ?: "", { commission = it.filter { c -> c.isDigit() || c == '.' } }, supporting = "Empty = platform default")
                PrimaryButton("Save commission", { vm.commission(commission?.takeIf { it.isNotBlank() }?.let { (it.toDouble() * 100).toInt() }, report); commission = null }, enabled = commission != null)
            }
        }
    }
    noteFor?.let { (a, label) -> ReasonDialog(label, "Note for the vendor (required)", { vm.step(a, it, report); noteFor = null }, { noteFor = null }) }
}

class AdminOrdersViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.adminOps.orders(null) } }
    fun cancel(id: String, reason: String, onError: (String) -> Unit) = run(onError) { app.adminOps.cancel(id, reason); reload() }
}

@Composable
private fun OrdersTab(report: (String) -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("a-orders") { AdminOrdersViewModel(app) }
    val list = vm.list.collectAsStateValue()
    var cancelFor by remember { mutableStateOf<Order?>(null) }
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Remote(list, vm::reload) { orders ->
            if (orders.isEmpty()) EmptyNote("No bookings yet.")
            orders.forEach { o ->
                SurfaceCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${o.code} · ${o.locationName}", fontWeight = FontWeight.SemiBold); StatusBadge(o.statusLabel, o.status) }
                    Text("${o.customerName} · ${formatDateTime(o.startsAt)}", fontSize = 13.sp)
                    Text("Total ${formatMoney(o.totalMinor)} · commission ${formatMoney(o.commissionMinor)}", fontSize = 13.sp)
                    if ("ADMIN_CANCEL" in o.actions) SecondaryButton("Cancel and refund", { cancelFor = o }, danger = true)
                }
            }
        }
    }
    cancelFor?.let { o -> ReasonDialog("Cancel ${o.code}", "Reason (required)", { vm.cancel(o.id, it, report); cancelFor = null }, { cancelFor = null }) }
}
