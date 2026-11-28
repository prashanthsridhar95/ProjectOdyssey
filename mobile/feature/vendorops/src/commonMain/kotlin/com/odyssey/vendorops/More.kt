package com.odyssey.vendorops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

// Everything that is not the day's work: reviews, outlet settings, staff, the bank account and the Owner PIN.

@Composable
fun MoreScreen(
    p: Provider, user: User, unreplied: Int, onEarnings: (() -> Unit)?, onReviews: () -> Unit, onOutlet: () -> Unit, onStaff: () -> Unit,
    onBank: () -> Unit, onPin: () -> Unit, onLock: () -> Unit, onPromotions: (() -> Unit)? = null,
) {
    val app = LocalContainer.current
    val owner = user.role == Role.PROVIDER_OWNER
    val manager = owner || user.role == Role.PROVIDER_MANAGER
    var signOut by remember { mutableStateOf(false) }
    val loc = p.locations.firstOrNull()
    // The profile does not carry a staff count, so count the people who can sign in.
    var staff by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(p.id) { if (owner) staff = runCatching { app.provider.staff().size }.getOrNull() }
    Screen("More") {
        Row(Modifier.fillMaxWidth().hairline().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BlueprintArt(Modifier.size(52.dp), seed = p.id, showCar = false, monogram = initialsOf(p.name))
            Column(Modifier.weight(1f)) { Heading(p.name, 20); Body("${user.name} · ${user.role.name.removePrefix("PROVIDER_").titleCase()}", size = 12, color = Ind.muted) }
            StatusBadge(p.status.titleCase(), p.status)
        }
        if (owner && p.bank == null) Notice("Add a bank account so your earnings can be paid out.", NoticeTone.WARN, "Add", onBank)
        if (loc != null && !loc.open) Notice("${loc.name} is paused and not taking online bookings.", NoticeTone.WARN, "Open", onOutlet.takeIf { manager })
        Column {
            if (onEarnings != null) SettingRow(Lucide.Wallet, "Earnings and payouts", "Behind your Owner PIN", onClick = onEarnings)
            SettingRow(Lucide.Star, "Reviews", loc?.let { if (it.ratingCount > 0) "★ ${it.ratingAvg} from ${it.ratingCount} reviews" else "No reviews yet" }, badge = unreplied, onClick = onReviews)
            if (manager && onPromotions != null) SettingRow(Lucide.Zap, "Promotions", "Happy hours and date discounts", onClick = onPromotions)
            if (manager && loc != null) SettingRow(Lucide.Store, "Outlet settings", "${loc.openTime} – ${loc.closeTime} · ${loc.capacity} bays · ${if (loc.autoAccept) "auto-accept on" else "you accept each booking"}", onClick = onOutlet)
            if (owner) SettingRow(Lucide.Users, "Staff", staff?.let { "$it ${if (it == 1) "person" else "people"} can sign in, you included" } ?: "Who can sign in at the counter", onClick = onStaff)
            if (owner) SettingRow(Lucide.Landmark, "Bank account", p.bank?.let { "${it.accountNumberMasked} · ${it.ifsc}" } ?: "Not added yet", onClick = onBank)
            if (owner) SettingRow(Lucide.KeyRound, "Owner PIN", "Protects prices, staff, bank details and earnings", onClick = onPin)
            SettingRow(Lucide.Lock, "Lock the counter", "Hands the device to the next person", onClick = onLock)
        }
        SettingRow(Lucide.LogOut, "Sign out of this device", danger = true, onClick = { signOut = true })
    }
    if (signOut) ConfirmDialog("Sign out of this device?", "Counter staff will not be able to unlock it until an owner or manager signs in again.", "Sign out", { signOut = false; app.logout() }, { signOut = false }, danger = true)
}

// ---- Reviews ----

class ReviewsViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<ProviderReview>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.provider.reviews() } }
    fun reply(id: String, text: String, report: (String) -> Unit, done: () -> Unit) = run(report) { app.provider.reply(id, text.trim()); report("Reply posted"); done(); reload() }
}

@Composable
fun ReviewsScreen(onBack: () -> Unit, report: (String) -> Unit, onChanged: () -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("v-reviews") { ReviewsViewModel(app) }
    val s = vm.list.collectAsStateValue()
    var show by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    Screen("Reviews", onBack = onBack, subtitle = "Replies are public, under the customer's review") {
        Remote(s, vm::reload) { all ->
            if (all.isNotEmpty()) StatGrid(listOf(
                Stat("Average", "★ ${(all.sumOf { it.rating } * 10 / all.size) / 10.0}", if (all.size >= 50) "Latest ${all.size} reviews" else "${all.size} reviews"),
                Stat("Not replied", all.count { it.reply == null }.toString(), alert = all.any { it.reply == null && it.rating <= 3 }),
            ))
            Seg(listOf("All", "Not replied", "3★ or fewer"), show) { show = it }
            val rows = all.filter { when (show) { 1 -> it.reply == null; 2 -> it.rating <= 3; else -> true } }
            if (rows.isEmpty()) EmptyState(Lucide.Star, if (show == 0) "No reviews yet" else "Nothing here", if (show == 0) "Customers can rate you after each completed booking." else "Every review in this group has a reply.")
            rows.forEach { r ->
                Column(Modifier.fillMaxWidth().hairline().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Body(r.customerName, weight = FontWeight.SemiBold); Body(relativeTime(r.createdAt), size = 12, color = Ind.muted) }
                        Body("★".repeat(r.rating) + "☆".repeat(5 - r.rating), color = if (r.rating <= 2) Ind.danger else Ind.a700)
                    }
                    if (r.comment.isNotBlank()) Body(r.comment) else Body("Rating only, no comment.", size = 13, color = Ind.mutedSoft)
                    if (r.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.tags.take(3).forEach { Tag(it) } }
                    when {
                        r.reply != null -> Column(Modifier.fillMaxWidth().background(Ind.surface).padding(10.dp)) { Body("Your reply${r.replyAt?.let { " · ${relativeTime(it)}" } ?: ""}", size = 12, color = Ind.muted); Body(r.reply!!, size = 14) }
                        open == r.id -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("", text, { if (it.length <= 500) text = it }, multiline = true, placeholder = if (r.rating <= 3) "Say sorry, and what you will do about it" else "Thank them by name", supporting = "${text.length}/500")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton("Cancel", { open = null })
                                Box(Modifier.weight(1f)) { PrimaryButton("Post reply", { vm.reply(r.id, text, report) { open = null; text = ""; onChanged() } }, enabled = text.isNotBlank(), height = 44) }
                            }
                        }
                        else -> SecondaryButton("Reply", { open = r.id; text = "" }, icon = Lucide.MessageSquare)
                    }
                }
            }
        }
    }
}

// ---- Outlet settings ----

@Composable
fun OutletSettingsScreen(loc: ProviderLocation, onBack: () -> Unit, onSaved: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val scope = rememberCoroutineScope()
    var openTime by remember { mutableStateOf(clockToMinutes(loc.openTime)) }
    var closeTime by remember { mutableStateOf(clockToMinutes(loc.closeTime)) }
    var slot by remember { mutableStateOf(loc.slotMinutes) }
    var capacity by remember { mutableStateOf(loc.capacity) }
    var autoAccept by remember { mutableStateOf(loc.autoAccept) }
    var open by remember { mutableStateOf(loc.open) }
    var busy by remember { mutableStateOf(false) }
    var confirmPause by remember { mutableStateOf(false) }
    val dirty = openTime != clockToMinutes(loc.openTime) || closeTime != clockToMinutes(loc.closeTime) || slot != loc.slotMinutes || capacity != loc.capacity || autoAccept != loc.autoAccept || open != loc.open
    fun hhmm(m: Int) = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    fun save() {
        busy = true
        scope.launch {
            try { app.provider.updateLocation(loc.id, LocationUpdate(hhmm(openTime), hhmm(closeTime), slot, capacity, autoAccept, open)); report("${loc.name} saved"); onSaved(); onBack() }
            catch (e: Exception) { report(e.friendly()) } finally { busy = false }
        }
    }
    Screen(loc.name, onBack = onBack, subtitle = loc.address, footer = {
        PrimaryButton(if (busy) "Saving…" else "Save changes", { if (loc.open && !open) confirmPause = true else save() }, enabled = dirty && !busy && closeTime > openTime)
    }) {
        Section("Opening hours") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { TimeStepper("Opens", minToClock(openTime), { openTime = (openTime - 30).coerceAtLeast(0) }, { openTime = (openTime + 30).coerceAtMost(closeTime - 30) }) }
                Box(Modifier.weight(1f)) { TimeStepper("Closes", minToClock(closeTime), { closeTime = (closeTime - 30).coerceAtLeast(openTime + 30) }, { closeTime = (closeTime + 30).coerceAtMost(24 * 60 - 30) }) }
            }
        }
        Section("Capacity") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { TimeStepper("Bays (cars at a time)", capacity.toString(), { capacity = (capacity - 1).coerceAtLeast(1) }, { capacity = (capacity + 1).coerceAtMost(20) }) }
                Box(Modifier.weight(1f)) { TimeStepper("Slot length", "$slot min", { slot = (slot - 5).coerceAtLeast(10) }, { slot = (slot + 5).coerceAtMost(120) }) }
            }
            Body("Changing bays or slot length affects new bookings. Existing bookings keep their time.", size = 12, color = Ind.muted)
        }
        Section("Bookings") {
            ToggleRow("Taking bookings", "Turn off to pause online bookings, for example during renovation.", open) { open = it }
            ToggleRow("Accept bookings automatically", "New bookings are confirmed without waiting for you. Turn off to accept each one; unanswered ones are rejected and refunded after a while.", autoAccept) { autoAccept = it }
        }
    }
    if (confirmPause) ConfirmDialog("Pause bookings at ${loc.name}?", "Customers stop seeing you in search until you turn this back on. Bookings already made are not affected.", "Pause bookings", { confirmPause = false; save() }, { confirmPause = false }, danger = true)
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Body(title, weight = FontWeight.Medium); Body(detail, size = 12, color = Ind.muted) }
        SquareToggle(on, onChange)
    }
}

@Composable
private fun TimeStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Body(label, size = 12, color = Ind.muted)
        Row(Modifier.fillMaxWidth().height(44.dp).hairline(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onMinus), Alignment.Center) { Heading("−", 22) }
            Box(Modifier.weight(1f), Alignment.Center) { Body(value, size = 15, weight = FontWeight.Medium) }
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(onClick = onPlus), Alignment.Center) { Heading("+", 22) }
        }
    }
}

fun clockToMinutes(hhmm: String) = (hhmm.take(2).toIntOrNull() ?: 0) * 60 + (hhmm.drop(3).take(2).toIntOrNull() ?: 0)

// ---- Staff ----

class StaffViewModel(private val app: AppContainer) : BaseViewModel() {
    val list = MutableStateFlow<Load<List<User>>>(Load.Loading)
    init { reload() }
    fun reload() { list.load { app.provider.staff() } }
}

@Composable
fun StaffScreen(onBack: () -> Unit, report: (String) -> Unit, onChanged: () -> Unit) {
    val app = LocalContainer.current
    val vm = screenModel("v-staff") { StaffViewModel(app) }
    val s = vm.list.collectAsStateValue()
    val gate = rememberPinGate()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<User?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var manager by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (adding) {
        Screen("Add staff", onBack = { adding = false }, footer = {
            PrimaryButton("Add ${if (manager) "manager" else "counter staff"}", {
                error = when {
                    name.isBlank() -> "Enter their name"
                    !Validate.indianMobile(phone) -> "Enter a 10-digit mobile number"
                    pin.length != 4 -> "Set a 4-digit PIN"
                    !Validate.strongPin(pin) -> "Pick a PIN that is harder to guess than 1234 or 1111"
                    else -> null
                }
                if (error == null) gate.ask("Owner PIN to add staff") { owner ->
                    app.provider.addStaff(name.trim(), phone, if (manager) Role.PROVIDER_MANAGER else Role.PROVIDER_STAFF, pin, owner)
                    report("${name.trim()} added. They can sign in now."); adding = false; name = ""; phone = ""; pin = ""; vm.reload(); onChanged()
                }
            })
        }) {
            Body("They sign in on the counter device with their phone number and this PIN, so every action is tied to a person.", size = 13, color = Ind.muted)
            Field("Name", name, { name = it })
            Field("Mobile number", phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' } }, keyboard = KeyboardType.Phone, placeholder = "98450 21734")
            Section("Role") {
                RadioRow("Counter staff", !manager, "Check in and out") { manager = false }
                RadioRow("Manager", manager, "Slots, menu, bookings") { manager = true }
            }
            Section("Their 4-digit PIN") { CodeBoxes(pin, 4, secret = true) { pin = it } }
            error?.let { ErrorBox(it) }
        }
        PinGateHost(gate) { error = it }
        return
    }
    Screen("Staff", onBack = onBack, actions = { SecondaryButton("Add", { adding = true; error = null }, icon = Lucide.UserPlus) }) {
        Remote(s, vm::reload) { people ->
            if (people.size <= 1) EmptyState(Lucide.Users, "Only you so far", "Give each person their own sign-in, so every action is tied to someone.", "Add staff") { adding = true }
            people.sortedBy { it.role.ordinal }.forEach { u ->
                Row(Modifier.fillMaxWidth().bottomHairline().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(Ind.a100), Alignment.Center) { Body(initialsOf(u.name), weight = FontWeight.SemiBold, color = Ind.a800) }
                    Column(Modifier.weight(1f)) { Body(u.name, weight = FontWeight.Medium); Body("${u.role.name.removePrefix("PROVIDER_").titleCase()} · ${formatPhone(u.phone)}", size = 12, color = Ind.muted) }
                    if (u.role != Role.PROVIDER_OWNER) Icon(Lucide.Trash2, "Remove ${u.name}", Modifier.size(20.dp).clickable { removing = u }, tint = Ind.muted)
                }
            }
            Body("Managers can run slots, the menu and bookings. Counter staff can only check customers in and out. Neither sees earnings or changes prices.", size = 12, color = Ind.muted)
        }
    }
    removing?.let { u ->
        ConfirmDialog("Remove ${u.name}?", "They are signed out everywhere and can no longer sign in. Their past actions stay in the log.", "Remove", {
            removing = null
            gate.ask("Owner PIN to remove staff") { owner -> app.provider.removeStaff(u.id, owner); report("${u.name} removed"); vm.reload(); onChanged() }
        }, { removing = null }, danger = true)
    }
    PinGateHost(gate, report)
}

// ---- Bank account ----

@Composable
fun BankScreen(p: Provider, onBack: () -> Unit, onSaved: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val gate = rememberPinGate()
    var holder by remember { mutableStateOf(p.bank?.accountName ?: "") }
    var number by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var ifsc by remember { mutableStateOf(p.bank?.ifsc ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Screen("Bank account", onBack = onBack, footer = {
        PrimaryButton(if (p.bank == null) "Save bank account" else "Change bank account", {
            error = when {
                holder.isBlank() -> "Enter the account holder's name as the bank has it"
                !Validate.accountNumber(number) -> "Account numbers are 9 to 18 digits"
                number != again -> "The two account numbers do not match"
                !Validate.ifsc(ifsc) -> "An IFSC looks like HDFC0001234: 4 letters, a zero, then 6 letters or digits"
                else -> null
            }
            if (error == null) gate.ask("Owner PIN to change bank details") { pin -> app.provider.saveBank(holder.trim(), number, ifsc, pin); report("Bank details saved. Payouts go here from now on."); onSaved(); onBack() }
        })
    }) {
        if (p.bank != null) Notice("Payouts go to ${p.bank!!.accountNumberMasked} · ${p.bank!!.ifsc} (${p.bank!!.accountName}).")
        else Notice("No bank account yet. Add one so your earnings can be paid out.", NoticeTone.WARN)
        Field("Account holder", holder, { holder = it })
        Field("Account number", number, { number = it.filter(Char::isDigit).take(18) }, keyboard = KeyboardType.NumberPassword)
        Field("Account number again", again, { again = it.filter(Char::isDigit).take(18) }, keyboard = KeyboardType.NumberPassword, supporting = "Typed twice so a payout never goes to the wrong account")
        Field("IFSC", ifsc, { ifsc = it.uppercase().filter(Char::isLetterOrDigit).take(11) }, placeholder = "HDFC0001234")
        error?.let { ErrorBox(it) }
    }
    PinGateHost(gate) { error = it }
}

// ---- Owner PIN ----

@Composable
fun OwnerPinScreen(onBack: () -> Unit, report: (String) -> Unit) {
    val app = LocalContainer.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Screen("Owner PIN", onBack = onBack, footer = {
        PrimaryButton(if (busy) "Saving…" else "Update PIN", {
            error = when {
                !Validate.strongPin(next) -> "Pick a PIN that is harder to guess than 123456 or 111111"
                next != again -> "The two new PINs do not match"
                else -> null
            }
            if (error == null) { busy = true; scope.launch { try { app.provider.setOwnerPin(next, current.ifBlank { null }); report("Owner PIN updated"); onBack() } catch (e: Exception) { error = e.friendly() } finally { busy = false } } }
        }, enabled = next.length == 6 && again.length == 6 && !busy)
    }) {
        Body("Needed for prices, staff, the bank account and earnings, even when you are signed in. Share it with nobody.", size = 13, color = Ind.muted)
        Section("Current PIN") { CodeBoxes(current, 6, secret = true) { current = it }; Body("Leave empty if you have not set one yet.", size = 12, color = Ind.muted) }
        Section("New PIN") { CodeBoxes(next, 6, secret = true) { next = it } }
        Section("New PIN again") { CodeBoxes(again, 6, secret = true) { again = it } }
        error?.let { ErrorBox(it) }
    }
}
