package com.odyssey.domain

import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong

/**
 * Use cases hold the application rules. Screens call these, never the repositories directly,
 * so the rules are shared by Android and iOS and testable without a device.
 */

class SignInWithOtp(private val auth: AuthRepository) {
    suspend fun request(phone: String) = auth.requestOtp(normalizePhone(phone))
    suspend fun verify(phone: String, code: String, name: String?) = auth.verifyOtp(normalizePhone(phone), code.trim(), name?.trim()?.takeIf { it.isNotEmpty() })
}

class SignInWithPin(private val auth: AuthRepository) {
    suspend operator fun invoke(phone: String, pin: String) = auth.staffLogin(normalizePhone(phone), pin)
}

fun normalizePhone(raw: String): String {
    val p = raw.filter { it.isDigit() || it == '+' }
    return if (p.startsWith("+")) p else if (p.length == 10) "+91$p" else p
}

fun isValidPhone(raw: String): Boolean = normalizePhone(raw).filter { it.isDigit() }.length in 10..15

class SearchPlaces(private val repo: CustomerRepository) {
    suspend operator fun invoke(filters: SearchFilters) = repo.search(filters)
}

class LoadPlace(private val repo: CustomerRepository) {
    suspend operator fun invoke(id: String, variantKey: String?, lat: Double? = null, lng: Double? = null) = repo.detail(id, variantKey, lat, lng)
}

class LoadSlots(private val repo: CustomerRepository) {
    suspend operator fun invoke(locationId: String, date: String, itemIds: List<String>, variantKey: String? = null) = repo.slots(locationId, date, itemIds, variantKey)
}

/** Price breakdown shown before the customer pays. The server recomputes the same numbers when the booking is placed. */
data class Checkout(val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val totalMinor: Long, val durationMinutes: Int, val discountMinor: Long = 0)

class BuildCheckout {
    /** An estimate for showing a price before the server quotes it. Tax covers the service plus the fee, matching the server. */
    operator fun invoke(items: List<Item>, variantKey: String, money: MoneyConfig, discountMinor: Long = 0): Checkout? {
        if (items.isEmpty()) return null
        val prices = items.map { it.prices[variantKey] ?: return null }
        val subtotal = prices.sum()
        val tax = ((subtotal + money.customerFeeMinor) * money.taxBps / 10_000.0).roundToLong()
        val discount = minOf(discountMinor, subtotal + money.customerFeeMinor + tax)
        return Checkout(subtotal, tax, money.customerFeeMinor, subtotal + tax + money.customerFeeMinor - discount, items.sumOf { it.durationMinutes }, discount)
    }
}

class PlaceAndPayOrder(private val customer: CustomerRepository) {
    suspend fun place(input: PlaceOrderInput, key: String) = customer.placeOrder(input, key)
    suspend fun pay(orderId: String, method: String, simulate: String?, key: String) = customer.pay(orderId, method, simulate, key)
}

class ManageBookings(private val orders: OrderRepository, private val customer: CustomerRepository) {
    suspend fun mine(): List<Order> = orders.myOrders()
    suspend fun detail(id: String) = orders.order(id)
    suspend fun cancel(id: String) = orders.act(id, "CANCEL_BY_CUSTOMER", null)
    suspend fun abandon(id: String) = orders.act(id, "ABANDON", null)
    suspend fun review(id: String, rating: Int, comment: String, tags: List<String> = emptyList()) = customer.review(id, rating.coerceIn(1, 5), comment, tags)
}

/** What a customer would get back right now if they cancelled, according to the tenant's policy. */
fun refundPreview(order: Order, policy: CancellationPolicy, hoursUntilStart: Double): Long =
    if (hoursUntilStart >= policy.fullRefundHoursBefore) order.totalMinor
    else order.totalMinor - (order.totalMinor * policy.lateRetainBps / 10_000.0).roundToLong()

enum class DayPart(val label: String) { MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening") }

/** Groups a day's slots so a long list reads as three short ones. */
fun groupSlots(slots: List<Slot>): Map<DayPart, List<Slot>> {
    fun part(s: Slot): DayPart {
        val hour = s.startsAt.toInstantOrNull()?.toLocalDateTime(IST)?.hour ?: 0
        return when { hour < 12 -> DayPart.MORNING; hour < 17 -> DayPart.AFTERNOON; else -> DayPart.EVENING }
    }
    val byPart = slots.groupBy(::part)
    return DayPart.entries.filter { it in byPart }.associateWith { byPart.getValue(it) }   // morning, afternoon, evening in order
}

@kotlinx.serialization.Serializable
data class PendingAction(val orderId: String, val action: String, val note: String? = null)

/**
 * Counter actions taken without a connection wait here and are sent when the network returns, in order.
 * The queue survives the app being closed, so a busy counter never loses a check-in.
 */
class ActionQueue(private val kv: KeyValueStore, private val key: String = "pending-actions") {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val serializer = kotlinx.serialization.builtins.ListSerializer(PendingAction.serializer())

    fun pending(): List<PendingAction> = kv.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    fun enqueue(a: PendingAction) { kv.put(key, json.encodeToString(serializer, pending() + a)) }
    private fun save(list: List<PendingAction>) = kv.put(key, if (list.isEmpty()) null else json.encodeToString(serializer, list))

    /** Sends queued actions in order. Stops at the first network failure; drops an action the server refuses for good. */
    suspend fun flush(send: suspend (PendingAction) -> Unit): Int {
        var sent = 0
        var left = pending()
        while (left.isNotEmpty()) {
            try { send(left.first()); sent++; left = left.drop(1) }
            catch (e: ApiException) { if (e.code == "network") break; left = left.drop(1) }
            save(left)
        }
        save(left)
        return sent
    }
}

class RunProviderDay(private val repo: ProviderRepository, private val orders: OrderRepository, private val queue: ActionQueue) {
    suspend fun bookings(date: String, locationId: String?) = repo.orders(date, locationId)

    /** Runs the action now, or queues it when offline. Returns null when queued. */
    suspend fun act(orderId: String, action: String, note: String?): Order? =
        try { orders.act(orderId, action, note) } catch (e: ApiException) { if (e.code == "network") { queue.enqueue(PendingAction(orderId, action, note)); null } else throw e }

    fun queued(): Int = queue.pending().size
    suspend fun flush(): Int = queue.flush { orders.act(it.orderId, it.action, it.note) }
}

class RunAdmin(private val repo: AdminRepository, private val orders: OrderRepository) {
    suspend fun dashboard(range: String = "7d") = repo.dashboard(range)
    suspend fun providers(status: String?) = repo.providers(status)
    suspend fun provider(id: String) = repo.provider(id)
    suspend fun step(id: String, action: String, note: String?) = repo.providerAction(id, action, note)
    suspend fun commission(id: String, bps: Int?) = repo.setCommission(id, bps)
    suspend fun orders(status: String?) = repo.orders(status)
    suspend fun cancel(orderId: String, reason: String) = orders.act(orderId, "ADMIN_CANCEL", reason)
    suspend fun hold(id: String, on: Boolean) = repo.setHold(id, on)
    suspend fun reviewDocument(id: String, type: String, verified: Boolean, note: String?) = repo.reviewDocument(id, type, if (verified) "VERIFIED" else "NEEDS_FIX", note)
    suspend fun tickets(openOnly: Boolean) = repo.tickets(if (openOnly) "OPEN" else null)
    suspend fun resolve(id: String, uphold: Boolean, refundMinor: Long, note: String) = repo.resolveTicket(id, uphold, refundMinor.coerceAtLeast(0), note.trim())
}

/** Checks on what people type, shared by every app so the rules match. */
object Validate {
    fun ifsc(s: String) = Regex("^[A-Z]{4}0[A-Z0-9]{6}$").matches(s)
    fun accountNumber(s: String) = Regex("^\\d{9,18}$").matches(s)
    /** Rejects PINs anyone would guess first: all one digit, or a straight run up or down. */
    fun strongPin(p: String): Boolean {
        if (p.isEmpty() || p.any { !it.isDigit() } || p.all { it == p[0] }) return false
        val up = p.zipWithNext().all { (a, b) -> b - a == 1 }; val down = p.zipWithNext().all { (a, b) -> a - b == 1 }
        return !up && !down
    }
    fun indianMobile(raw: String) = normalizePhone(raw).let { it.startsWith("+91") && it.length == 13 }
}

/** "+919845021734" -> "+91 98450 21734". */
fun formatPhone(p: String): String = Regex("^\\+91(\\d{5})(\\d{5})$").find(p)?.let { "+91 ${it.groupValues[1]} ${it.groupValues[2]}" } ?: p

/** Booking progress for the timeline: which step the order is on. Built from the order's history, so it matches what the vendor did. */
data class ProgressStep(val label: String, val at: String?, val done: Boolean, val current: Boolean)

fun progressSteps(events: List<OrderEvent>, status: String, labels: Map<String, String>): List<ProgressStep> {
    val order = listOf("CONFIRMED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED")
    val reached = events.associate { it.to to it.at }
    return order.map { s -> ProgressStep(labels[s] ?: s.titleCase(), reached[s], done = s in reached.keys, current = s == status) }
}

fun hoursUntil(iso: String, now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Double =
    iso.toInstantOrNull()?.let { (it - now).inWholeMinutes / 60.0 } ?: 0.0

/** "RN 4K7Q": the booking code split for easy reading aloud at the counter. */
fun displayCode(code: String): String = if (code.length > 4) code.take(code.length - 4) + " " + code.takeLast(4) else code
