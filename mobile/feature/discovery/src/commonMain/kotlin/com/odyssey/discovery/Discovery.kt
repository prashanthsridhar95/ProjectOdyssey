package com.odyssey.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.*
import com.odyssey.data.AppContainer
import com.odyssey.domain.*
import com.odyssey.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon as M3Icon

// ---- Home: places near me ----

data class HomeState(
    val filters: SearchFilters, val query: String = "", val map: Boolean = false,
    val list: Load<List<LocationSummary>> = Load.Loading, val selected: String? = null,
)

class HomeViewModel(private val app: AppContainer, start: PlaceSel) : BaseViewModel() {
    private val _state = MutableStateFlow(HomeState(SearchFilters(start.lat, start.lng)))
    val state: StateFlow<HomeState> = _state
    private val results = MutableStateFlow<Load<List<LocationSummary>>>(Load.Loading)
    private var variant: String? = null

    init {
        run { results.collect { _state.value = _state.value.copy(list = it, selected = (it as? Load.Ready)?.value?.firstOrNull()?.id) } }
        refresh()
    }

    fun origin(p: PlaceSel, vehicleVariant: String?) { variant = vehicleVariant; _state.value = _state.value.copy(filters = _state.value.filters.copy(lat = p.lat, lng = p.lng, variantKey = vehicleVariant)); refresh() }
    fun filters(f: SearchFilters) { _state.value = _state.value.copy(filters = f.copy(variantKey = variant)); refresh() }
    fun sort(s: String) = filters(_state.value.filters.copy(sort = s))
    fun query(q: String) { _state.value = _state.value.copy(query = q) }
    fun showMap(on: Boolean) { _state.value = _state.value.copy(map = on) }
    fun select(id: String) { _state.value = _state.value.copy(selected = id) }

    fun refresh() {
        val s = _state.value
        results.load { app.searchPlaces(s.filters.copy(text = s.query.takeIf { it.isNotBlank() }, variantKey = variant)) }
    }
}

private val SORTS = listOf("distance" to "Nearest", "earliest" to "Earliest slot", "rating" to "Top rated", "price" to "Lowest price")

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit, onChangePlace: () -> Unit, onOpenNotifications: () -> Unit,
    onNeedVehicle: () -> Unit = {},
) {
    val app = LocalContainer.current
    val cfg = LocalTenant.current.config
    val customer = LocalCustomer.current
    val vertical = LocalVertical.current
    val variant = customer.vehicle?.let { vertical?.variantOf(it) }
    val vm = screenModel("home") { HomeViewModel(app, customer.place) }
    val s = vm.state.collectAsStateValue()
    LaunchedEffect(customer.place, variant) { vm.origin(customer.place, variant) }
    LaunchedEffect(s.query) { delay(450); vm.refresh() }
    var showFilters by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ind.bg).statusBarsPadding()) {
        if (s.map) {
            val places = (s.list as? Load.Ready)?.value ?: emptyList()
            MapView(places, s.selected, customer.place, vm::select, onOpen, onList = { vm.showMap(false) }, onChangePlace = onChangePlace)
            return@Column
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).clickable(onClick = onChangePlace), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Icon(Lucide.MapPin, null, Modifier.size(20.dp), tint = Ind.a700)
                    Column(Modifier.weight(1f, fill = false)) {
                        Heading(customer.place.label, 18)
                        if (customer.place.address.isNotBlank()) Body(customer.place.address, size = 12, color = Ind.muted, modifier = Modifier)
                    }
                    M3Icon(Lucide.ChevronDown, null, Modifier.size(16.dp))
                }
                val v = customer.vehicle
                if (v != null && vertical != null) Tag(v.label.substringBefore(" ("), TagKind.ACCENT, vertical.iconFor(v))
                else if (vertical != null) Box(Modifier.clickable(onClick = onNeedVehicle)) { Tag("Add ${vertical.assetNoun.lowercase()}", TagKind.OUTLINE, Lucide.Car) }
                IconBox(Lucide.Bell, tint = Ind.text, onClick = onOpenNotifications)
            }
            Row(Modifier.fillMaxWidth().height(44.dp).background(Ind.surface).hairline().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(Lucide.Search, null, Modifier.size(18.dp), tint = Ind.muted)
                Box(Modifier.weight(1f)) { Field("", s.query, vm::query, Modifier.fillMaxWidth(), placeholder = "Search washes or services", height = 40, bare = true) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seg(listOf("List", "Map"), if (s.map) 1 else 0, Modifier.width(120.dp)) { vm.showMap(it == 1) }
                Box(Modifier.clickable { showFilters = true }) { Tag("Filters" + if (s.filters.activeCount > 0) " · ${s.filters.activeCount}" else "", TagKind.OUTLINE, Lucide.SlidersHorizontal) }
                Spacer(Modifier.weight(1f))
                Box {
                    Row(Modifier.clickable { sortMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                        Body(SORTS.first { it.first == s.filters.sort }.second, size = 12, color = Ind.muted); M3Icon(Lucide.ChevronDown, null, Modifier.size(14.dp), tint = Ind.muted)
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Ind.bg) { SORTS.forEach { (k, l) -> DropdownMenuItem(text = { Body(l) }, onClick = { sortMenu = false; vm.sort(k) }) } }
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Remote(s.list, vm::refresh) { places ->
                if (places.isEmpty()) {
                    if (s.filters.activeCount > 0) EmptyState(Lucide.ListFilter, "Nothing matches these filters", "Try clearing one or two.")
                    else EmptyState(Lucide.MapPin, "No ${cfg.labels.provider.lowercase()}s nearby yet", "We are not in this area yet. Try another place, or check back soon.")
                    cfg.serviceAreas.forEach { a -> Box(Modifier.fillMaxWidth(), Alignment.Center) { SecondaryButton("Look in ${a.name}", { customer.place = PlaceSel(a.name, "", a.lat, a.lng) }) } }
                }
                if (LocalWindow.current.isWide) {
                    places.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { Box(Modifier.weight(1f)) { PlaceCard(it, onClick = { onOpen(it.id) }) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else places.forEach { PlaceCard(it, onClick = { onOpen(it.id) }) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showFilters) FiltersSheet(s.filters, cfg.serviceTemplates.map { it.name }, cfg.amenities, count = (s.list as? Load.Ready)?.value?.size, onApply = { vm.filters(it); showFilters = false }, onDismiss = { showFilters = false })
}

/** Next free time as a short phrase: "10:30", or "Tomorrow 10:30". */
fun nextText(iso: String): String {
    val day = dayChipLabel(iso.toInstantOrNull()?.let { istDateOf(it) } ?: istDate())
    return if (day == "Today") formatTime(iso) else "$day ${formatTime(iso)}"
}

private fun istDateOf(i: kotlin.time.Instant): String = istDate(0, i)

fun areaOf(address: String): String = address.split(",").map { it.trim() }.let { it.getOrNull(1) ?: it.first() }

@Composable
fun PlaceCard(p: LocationSummary, onClick: () -> Unit) {
    val money = LocalTenant.current.config.money
    Column(Modifier.fillMaxWidth().padding(6.dp).blueprint().clickable(onClick = onClick)) {
        BlueprintArt(Modifier.fillMaxWidth().height(96.dp), seed = p.id, monogram = initialsOf(p.name))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Heading(p.name, 19, Modifier.weight(1f))
                M3Icon(Lucide.Star, null, Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Body(if (p.ratingCount > 0) p.ratingAvg.toString() else "New", size = 13, weight = FontWeight.Medium)
            }
            Body("${areaOf(p.address)} · ${p.distanceKm} km", size = 12, color = Ind.muted)
            p.deal?.let { Tag(if (p.dealNow) "Now · $it" else it, if (p.dealNow) TagKind.SOLID else TagKind.ACCENT, Lucide.Zap) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.priceFromMinor != null) Row { Body("From ", size = 13); Body(formatMoney(p.priceFromMinor, money.currency), size = 13, weight = FontWeight.SemiBold) }
                Spacer(Modifier.weight(1f))
                p.nextFreeAt?.let { Tag("Next ${nextText(it)}", TagKind.ACCENT, Lucide.Clock) } ?: Tag(if (p.openNow) "Fully booked" else "Closed", TagKind.NEUTRAL)
            }
        }
    }
}

// ---- Map: a schematic of nearby places (real map tiles plug in behind the same pins) ----

@Composable
private fun MapView(
    places: List<LocationSummary>, selected: String?, origin: PlaceSel, onSelect: (String) -> Unit, onOpen: (String) -> Unit,
    onList: () -> Unit, onChangePlace: () -> Unit,
) {
    val money = LocalTenant.current.config.money
    Box(Modifier.fillMaxSize()) {
        Hatch(Modifier.fillMaxSize(), base = Ind.n200)
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val w = maxWidth; val h = maxHeight
            val lats = places.map { it.lat } + origin.lat; val lngs = places.map { it.lng } + origin.lng
            val minLat = lats.min(); val maxLat = lats.max(); val minLng = lngs.min(); val maxLng = lngs.max()
            fun x(lng: Double) = (if (maxLng == minLng) 0.5 else (lng - minLng) / (maxLng - minLng)) * 0.72 + 0.14
            fun y(lat: Double) = (if (maxLat == minLat) 0.5 else 1 - (lat - minLat) / (maxLat - minLat)) * 0.5 + 0.2
            Box(Modifier.offset(w * x(origin.lng).toFloat() - 7.dp, h * y(origin.lat).toFloat() - 7.dp).size(14.dp).background(Ind.a700, androidx.compose.foundation.shape.CircleShape))
            places.forEach { p ->
                val on = p.id == selected
                val label = formatMoney(p.priceFromMinor, money.currency) + if (on && p.nextFreeAt != null) " · ${formatTime(p.nextFreeAt!!)}" else ""
                Box(Modifier.offset(w * x(p.lng).toFloat() - 28.dp, h * y(p.lat).toFloat() - 14.dp).clickable { onSelect(p.id) }.then(if (on) Modifier.background(Ind.accent) else Modifier.background(Ind.bg).hairline()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Body(label, size = if (on) 13 else 12, weight = FontWeight.SemiBold, color = if (on) Ind.bg else Ind.text)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).height(44.dp).background(Ind.bg).hairline().clickable(onClick = onChangePlace).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(Lucide.MapPin, null, Modifier.size(18.dp), tint = Ind.a700); Body(origin.label + if (origin.address.isNotBlank()) " · ${areaOf(origin.address)}" else "")
            }
            IconBox(Lucide.List, size = 44.dp, onClick = onList)
        }
        places.firstOrNull { it.id == selected }?.let { p ->
            Row(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).fillMaxWidth().background(Ind.bg).blueprint().clickable { onOpen(p.id) }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BlueprintArt(Modifier.size(88.dp), seed = p.id, showCar = false, monogram = initialsOf(p.name))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Heading(p.name, 19)
                    Body("${p.distanceKm} km · ★ ${p.ratingAvg} (${p.ratingCount})" + (p.amenities.firstOrNull()?.let { " · $it" } ?: ""), size = 12, color = Ind.muted)
                    if (p.priceFromMinor != null) Body("From ${formatMoney(p.priceFromMinor, money.currency)}", size = 13, weight = FontWeight.SemiBold)
                    p.nextFreeAt?.let { Tag("Next free ${formatTime(it)}", TagKind.ACCENT) }
                }
            }
        }
    }
}

// ---- Filters sheet ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(current: SearchFilters, services: List<String>, amenities: List<String>, count: Int?, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(current) }
    val money = LocalTenant.current.config.money
    var range by remember { mutableStateOf((f.minPriceMinor?.toFloat() ?: 20000f)..(f.maxPriceMinor?.toFloat() ?: 100000f)) }
    var priceOn by remember { mutableStateOf(f.minPriceMinor != null || f.maxPriceMinor != null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ind.bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Filters", 24, Modifier.weight(1f))
                Box(Modifier.clickable { f = SearchFilters(f.lat, f.lng, f.sort); priceOn = false }) { Body("Clear all", size = 14, color = Ind.a700) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Body("Open now", size = 15, modifier = Modifier.weight(1f)); SquareToggle(f.openNow) { f = f.copy(openNow = it) } }
            Section("Service") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {}
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.forEach { name -> Choice(name, name in f.services) { f = f.copy(services = if (name in f.services) f.services - name else f.services + name) } }
                }
            }
            Section("Price range") {
                Row { Body(if (priceOn) "${formatMoney(range.start.toLong(), money.currency)} – ${formatMoney(range.endInclusive.toLong(), money.currency)}" else "Any price", size = 13, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Box(Modifier.clickable { priceOn = !priceOn }) { Body(if (priceOn) "Clear" else "Set", size = 13, color = Ind.a700) } }
                if (priceOn) RangeSlider(range, { range = it }, valueRange = 0f..300000f, colors = SliderDefaults.colors(thumbColor = Ind.accent, activeTrackColor = Ind.accent, inactiveTrackColor = Ind.n300))
            }
            Section("Rating") { Seg(listOf("Any", "4.0+", "4.5+"), when { f.minRating >= 4.5 -> 2; f.minRating >= 4.0 -> 1; else -> 0 }) { f = f.copy(minRating = listOf(0.0, 4.0, 4.5)[it]) } }
            Section("Amenities") {
                amenities.forEach { a -> CheckRow(a, a in f.amenities) { f = f.copy(amenities = if (a in f.amenities) f.amenities - a else f.amenities + a) } }
            }
            PrimaryButton(if (count != null) "Show $count ${LocalTenant.current.config.labels.location.lowercase()}s" else "Show results", {
                onApply(f.copy(minPriceMinor = if (priceOn) range.start.toLong() else null, maxPriceMinor = if (priceOn) range.endInclusive.toLong() else null))
            })
        }
    }
}

// ---- Place page ----

data class PlaceUi(val variantKey: String, val selected: Set<String> = emptySet(), val detail: Load<LocationDetail> = Load.Loading)

class PlaceViewModel(private val app: AppContainer, private val locationId: String, firstVariant: String, private val from: PlaceSel) : BaseViewModel() {
    private val _state = MutableStateFlow(PlaceUi(firstVariant))
    val state: StateFlow<PlaceUi> = _state
    private val loaded = MutableStateFlow<Load<LocationDetail>>(Load.Loading)

    init {
        run { loaded.collect { _state.value = _state.value.copy(detail = it) } }
        reload()
    }

    fun reload() { loaded.load { app.loadPlace(locationId, _state.value.variantKey, from.lat, from.lng) } }
    fun variant(key: String) { _state.value = _state.value.copy(variantKey = key, selected = emptySet()) }
    fun toggle(id: String) { val s = _state.value; _state.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun favorite(on: Boolean, onError: (String) -> Unit) = run(onError) { app.customer.setFavorite(locationId, on) }
}

@Composable
fun PlaceScreen(locationId: String, onBack: () -> Unit, onPickSlot: (itemIds: List<String>, variantKey: String) -> Unit) {
    val app = LocalContainer.current
    val tenant = LocalTenant.current.config
    val customer = LocalCustomer.current
    val platform = LocalPlatform.current
    val vertical = LocalVertical.current
    val start = customer.vehicle?.let { vertical?.variantOf(it) } ?: tenant.variants.options.first().key
    val vm = screenModel("place-$locationId") { PlaceViewModel(app, locationId, start, customer.place) }
    val s = vm.state.collectAsStateValue()
    val isFav = locationId in customer.favorites

    Column(Modifier.fillMaxSize().background(Ind.bg)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            BlueprintArt(Modifier.fillMaxSize(), seed = locationId, monogram = (s.detail as? Load.Ready)?.value?.location?.name?.let(::initialsOf))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconBox(Lucide.ArrowLeft, onClick = onBack)
                IconBox(Lucide.Heart, tint = if (isFav) Ind.danger else Ind.text, onClick = {
                    customer.favorites = if (isFav) customer.favorites - locationId else customer.favorites + locationId
                    vm.favorite(!isFav) { customer.favorites = if (isFav) customer.favorites + locationId else customer.favorites - locationId }
                })
            }
        }
        val detail = s.detail
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Remote(detail, vm::reload) { d ->
                val p = d.location
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Heading(p.name, 28)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (p.ratingCount > 0) { Body("★ ${p.ratingAvg}", size = 13, weight = FontWeight.Medium); Body("${p.ratingCount} reviews · ", size = 13, color = Ind.muted) }
                        Body("${p.distanceKm} km · Open till ${formatClock(p.closeTime)}", size = 13, color = Ind.muted)
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { p.amenities.forEach { Tag(it, TagKind.NEUTRAL) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).height(40.dp).hairline().clickable { platform.directions(p.lat, p.lng, p.name) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { M3Icon(Lucide.Navigation, null, Modifier.size(16.dp)); Body("Directions", size = 13) }
                    Row(Modifier.weight(1f).height(40.dp).hairline().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { M3Icon(Lucide.Clock, null, Modifier.size(16.dp)); Body("${formatClock(p.openTime)} – ${formatClock(p.closeTime)}", size = 13) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Body("Prices for", size = 12, color = Ind.muted)
                    Seg(tenant.variants.options.map { it.label.take(5) }, tenant.variants.options.indexOfFirst { it.key == s.variantKey }.coerceAtLeast(0), Modifier.weight(1f)) { vm.variant(tenant.variants.options[it].key) }
                }
                val offered = d.items.filter { it.prices.containsKey(s.variantKey) }
                if (offered.isEmpty()) EmptyNote("Nothing offered for this ${tenant.variants.name.lowercase()}.")
                Column {
                    offered.forEach { item ->
                        val on = item.id in s.selected
                        Row(Modifier.fillMaxWidth().clickable { vm.toggle(item.id) }.drawLine().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Heading(item.name, 17)
                                if (item.description.isNotBlank()) Body(item.description, size = 12, color = Ind.muted)
                                Row { Body(formatMoney(item.prices[s.variantKey], tenant.money.currency), weight = FontWeight.SemiBold); Body(" · ${item.durationMinutes} min", size = 12, color = Ind.muted) }
                            }
                            if (on) Row(Modifier.height(36.dp).background(Ind.a100).hairline(Ind.accent).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { M3Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Ind.a800); Body("Added", size = 13, color = Ind.a800, weight = FontWeight.Medium) }
                            else Row(Modifier.height(36.dp).hairline().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { M3Icon(Lucide.Plus, null, Modifier.size(14.dp)); Body("Add", size = 13, weight = FontWeight.Medium) }
                        }
                    }
                }
                if (d.reviews.isNotEmpty()) Section("Reviews") {
                    d.reviews.take(5).forEach { r -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Body("${"★".repeat(r.rating)}  ${r.customerName}", weight = FontWeight.Medium); if (r.tags.isNotEmpty()) Body(r.tags.joinToString(" · "), size = 12, color = Ind.a700); if (r.comment.isNotBlank()) Body(r.comment, size = 14) } }
                }
            }
        }
        val items = ((detail as? Load.Ready)?.value?.items ?: emptyList()).filter { it.id in s.selected }
        val checkout = app.buildCheckout(items, s.variantKey, tenant.money)
        Column(Modifier.fillMaxWidth().background(Ind.bg).topHairline().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp).navigationBarsPadding()) {
            PrimaryButton(
                if (checkout == null) "Choose a ${tenant.labels.item.lowercase()}" else "${items.size} item${if (items.size > 1) "s" else ""} · ${formatMoney(checkout.subtotalMinor, tenant.money.currency)}",
                { onPickSlot(s.selected.toList(), s.variantKey) }, enabled = checkout != null, trailing = if (checkout != null) "Pick a slot →" else null,
            )
        }
    }
}

private fun Modifier.drawLine(): Modifier = this.then(Modifier.bottomHairline())

fun formatClock(hhmm: String): String {
    val h = hhmm.take(2).toIntOrNull() ?: return hhmm
    val m = hhmm.drop(3).take(2)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return if (m == "00") "$h12 ${if (h < 12) "am" else "pm"}" else "$h12:$m ${if (h < 12) "am" else "pm"}"
}
