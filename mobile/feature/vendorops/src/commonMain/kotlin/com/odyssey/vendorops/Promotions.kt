package com.odyssey.vendorops

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

// Happy hours and date discounts at a glance. They are set up on the web dashboard; here an owner or manager can pause one.

class PromotionsViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<VendorPromotion>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.provider.promotions() } }
    fun setActive(p: VendorPromotion, active: Boolean, report: (String) -> Unit) = run(report) {
        app.provider.setPromotionActive(p.id, active)
        report(if (active) "${p.name} is live again" else "${p.name} paused. Usual prices are back.")
        reload()
    }
}

private val STATUS = mapOf("LIVE" to "Live", "SCHEDULED" to "Scheduled", "PAUSED" to "Paused", "ENDED" to "Ended")

@Composable
fun PromotionsScreen(p: Provider, user: User, onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val currency = LocalTenant.current.config.money.currency
    val vm = screenModel("v-promotions") { PromotionsViewModel(app) }
    val s = vm.list.collectAsStateValue()
    val canPause = user.role == Role.PROVIDER_OWNER || user.role == Role.PROVIDER_MANAGER
    var pausing by remember { mutableStateOf<VendorPromotion?>(null) }
    Screen("Promotions", onBack = onBack, subtitle = "Customers see these prices on the time picker") {
        Remote(s, vm::reload) { all ->
            val own = all.filter { it.providerId == p.id }
            val platform = all.filter { it.providerId == null }
            val current = own.filter { it.status != "ENDED" }
            if (current.isEmpty()) EmptyState(Lucide.Zap, "No offers running", "Set up a happy hour on the web dashboard under Business, Promotions. It shows here once it is saved.")
            current.forEach { o -> PromotionRow(o, currency, canPause && o.editable) { if (o.active) pausing = o else vm.setActive(o, true, report) } }
            if (platform.isNotEmpty()) Section("Platform offers at your outlets") {
                platform.forEach { o -> PromotionRow(o, currency, false, note = "Paid by the platform. Your earnings are not reduced.") {} }
            }
            Body("To create or change an offer, use the web dashboard. Only the best offer applies to a booking, and offers do not apply to walk-ins.", size = 12, color = Ind.muted)
        }
    }
    pausing?.let { o ->
        ConfirmDialog("Pause ${o.name}?", "Customers see your usual prices straight away. Bookings already made keep their price.", "Pause", { pausing = null; vm.setActive(o, false, report) }, { pausing = null })
    }
}

@Composable
private fun PromotionRow(o: VendorPromotion, currency: String, canToggle: Boolean, note: String? = null, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Body(o.name, size = 15, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Tag(STATUS[o.status] ?: o.status, if (o.status == "LIVE") TagKind.ACCENT else TagKind.NEUTRAL)
        }
        Body(o.summary, size = 13)
        Body(note ?: "${o.bookings} booking${if (o.bookings == 1) "" else "s"} · ${formatMoney(o.discountGivenMinor, currency)} off so far", size = 12, color = Ind.muted)
        if (canToggle && o.status != "ENDED") SecondaryButton(if (o.active) "Pause" else "Resume", onToggle, icon = if (o.active) Lucide.Pause else Lucide.Play)
    }
}
