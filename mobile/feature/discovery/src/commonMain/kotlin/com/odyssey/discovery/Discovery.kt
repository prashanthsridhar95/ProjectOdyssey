package com.odyssey.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.flow.StateFlow

// ---- Home: places near me ----

data class HomeState(val sort: String = "distance", val query: String = "", val openNow: Boolean = false, val list: Load<List<LocationSummary>> = Load.Loading)

class HomeViewModel(private val app: AppContainer, private val where: LocationProvider) : BaseViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
    private val results = MutableStateFlow<Load<List<LocationSummary>>>(Load.Loading)

    init {
        run { results.collect { _state.value = _state.value.copy(list = it) } }
        refresh()
    }

    fun sort(s: String) { _state.value = _state.value.copy(sort = s); refresh() }
    fun query(q: String) { _state.value = _state.value.copy(query = q) }
    fun openNow(v: Boolean) { _state.value = _state.value.copy(openNow = v); refresh() }

    fun refresh() {
        val s = _state.value
        results.load {
            val (lat, lng) = where.current()
            app.searchPlaces(SearchFilters(lat, lng, s.sort, s.query.takeIf { it.isNotBlank() }, openNow = s.openNow))
        }
    }
}

@Composable
fun HomeScreen(tenantName: String, onOpen: (String) -> Unit, onBookings: () -> Unit, onSignOut: () -> Unit, extraAction: (@Composable () -> Unit)? = null) {
    val app = LocalContainer.current
    val where = LocalLocation.current
    val labels = LocalTenant.current.config.labels
    val vm = screenModel("home") { HomeViewModel(app, where) }
    val s = vm.state.collectAsStateValue()

    Screen(tenantName, actions = { androidx.compose.material3.TextButton(onClick = onBookings) { Text("My ${labels.order.lowercase()}s") }; androidx.compose.material3.TextButton(onClick = onSignOut) { Text("Sign out") } }) {
        Field("Search by name or area", s.query, vm::query)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Nearest", s.sort == "distance") { vm.sort("distance") }
            Chip("Top rated", s.sort == "rating") { vm.sort("rating") }
            Chip("Lowest price", s.sort == "price") { vm.sort("price") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open now", s.openNow) { vm.openNow(!s.openNow) }
            Chip("Search", false) { vm.refresh() }
        }
        extraAction?.invoke()
        Remote(s.list, vm::refresh) { places ->
            if (places.isEmpty()) EmptyNote("No ${labels.provider.lowercase()}s found nearby.")
            places.forEach { PlaceCard(it, onClick = { onOpen(it.id) }) }
        }
    }
}

@Composable
fun PlaceCard(p: LocationSummary, onClick: () -> Unit) {
    val money = LocalTenant.current.config.money
    SurfaceCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(p.address, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
            Text("${p.distanceKm} km", fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.ratingCount > 0) Text("★ ${p.ratingAvg} (${p.ratingCount})", fontSize = 13.sp) else Text("New", fontSize = 13.sp)
            p.priceFromMinor?.let { Text("from ${formatMoney(it, money.currency)}", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            StatusBadge(if (p.openNow) "Open" else "Closed", if (p.openNow) "LIVE" else "EXPIRED")
        }
        p.nextFreeAt?.let { Text("Next free slot: ${formatDay(it)} ${formatTime(it)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
    }
}

// ---- Place detail: services, vehicle type, reviews ----

data class PlaceUi(val variantKey: String, val selected: Set<String> = emptySet(), val assetId: String? = null, val detail: Load<LocationDetail> = Load.Loading)

class PlaceViewModel(private val app: AppContainer, private val locationId: String, firstVariant: String) : BaseViewModel() {
    private val _state = MutableStateFlow(PlaceUi(firstVariant))
    val state: StateFlow<PlaceUi> = _state
    private val loaded = MutableStateFlow<Load<LocationDetail>>(Load.Loading)

    init {
        run { loaded.collect { _state.value = _state.value.copy(detail = it) } }
        reload()
    }

    fun reload() { loaded.load { app.loadPlace(locationId, _state.value.variantKey) } }
    fun variant(key: String) { _state.value = _state.value.copy(variantKey = key, selected = emptySet()) }
    fun asset(assetId: String?, variant: String?) {
        _state.value = _state.value.copy(assetId = assetId, variantKey = variant ?: _state.value.variantKey, selected = if (variant != null && variant != _state.value.variantKey) emptySet() else _state.value.selected)
    }
    fun toggle(id: String) { val s = _state.value; _state.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
}

@Composable
fun PlaceScreen(locationId: String, onBack: () -> Unit, onPickSlot: (itemIds: List<String>, variantKey: String, assetId: String?) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val vertical = LocalVertical.current
    val vm = screenModel("place-$locationId") { PlaceViewModel(app, locationId, tenant.variants.options.first().key) }
    val s = vm.state.collectAsStateValue()

    Screen("Details", onBack = onBack) {
        Remote(s.detail, vm::reload) { d ->
            val p = d.location
            Text(p.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(p.address, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            Text("Open ${p.openTime} – ${p.closeTime}${if (p.ratingCount > 0) "  ·  ★ ${p.ratingAvg} (${p.ratingCount})" else ""}")
            if (p.amenities.isNotEmpty()) Text(p.amenities.joinToString(" · "), fontSize = 13.sp)

            if (vertical != null) {
                Section(tenant.asset?.label ?: tenant.variants.name) { vertical.AssetPicker(s.assetId, s.variantKey, vm::asset) }
            } else {
                Section(tenant.variants.name) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tenant.variants.options.forEach { Chip(it.label, s.variantKey == it.key) { vm.variant(it.key) } } }
                }
            }

            Section("${tenant.labels.item}s") {
                val offered = d.items.filter { it.prices.containsKey(s.variantKey) }
                if (offered.isEmpty()) EmptyNote("Nothing offered for this ${tenant.variants.name.lowercase()}.")
                offered.forEach { item ->
                    val on = item.id in s.selected
                    SurfaceCard(onClick = { vm.toggle(item.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text("${item.durationMinutes} min" + if (item.description.isNotBlank()) " · ${item.description}" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatMoney(item.prices[s.variantKey], tenant.money.currency), fontWeight = FontWeight.SemiBold)
                                Text(if (on) "✓ Added" else "Add", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            if (d.reviews.isNotEmpty()) Section("Reviews") {
                d.reviews.take(5).forEach { r -> SurfaceCard { Text("${"★".repeat(r.rating)}  ${r.customerName}", fontWeight = FontWeight.Medium); if (r.comment.isNotBlank()) Text(r.comment, fontSize = 14.sp) } }
            }
            val checkout = app.buildCheckout(d.items.filter { it.id in s.selected }, s.variantKey, tenant.money)
            PrimaryButton(
                if (checkout == null) "Choose a ${tenant.labels.item.lowercase()}" else "Pick a time · ${formatMoney(checkout.totalMinor, tenant.money.currency)}",
                { onPickSlot(s.selected.toList(), s.variantKey, s.assetId) }, enabled = checkout != null,
            )
        }
    }
}
