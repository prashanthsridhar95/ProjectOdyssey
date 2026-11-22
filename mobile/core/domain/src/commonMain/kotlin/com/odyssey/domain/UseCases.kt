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
    suspend operator fun invoke(id: String, variantKey: String?) = repo.detail(id, variantKey)
}

class LoadSlots(private val repo: CustomerRepository) {
    suspend operator fun invoke(locationId: String, date: String, itemIds: List<String>) = repo.slots(locationId, date, itemIds)
}

/** Price breakdown shown before the customer pays. The server recomputes the same numbers when the booking is placed. */
data class Checkout(val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val totalMinor: Long, val durationMinutes: Int)

class BuildCheckout {
    operator fun invoke(items: List<Item>, variantKey: String, money: MoneyConfig): Checkout? {
        if (items.isEmpty()) return null
        val prices = items.map { it.prices[variantKey] ?: return null }
        val subtotal = prices.sum()
        val tax = (subtotal * money.taxBps / 10_000.0).roundToLong()
        return Checkout(subtotal, tax, money.customerFeeMinor, subtotal + tax + money.customerFeeMinor, items.sumOf { it.durationMinutes })
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
    suspend fun review(id: String, rating: Int, comment: String) = customer.review(id, rating.coerceIn(1, 5), comment)
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
    return slots.groupBy(::part).toSortedMap(compareBy { it.ordinal })
}

class RunProviderDay(private val repo: ProviderRepository, private val orders: OrderRepository) {
    suspend fun bookings(date: String, locationId: String?) = repo.orders(date, locationId)
    suspend fun act(orderId: String, action: String, note: String?) = orders.act(orderId, action, note)
}

class RunAdmin(private val repo: AdminRepository, private val orders: OrderRepository) {
    suspend fun dashboard() = repo.dashboard()
    suspend fun providers(status: String?) = repo.providers(status)
    suspend fun provider(id: String) = repo.provider(id)
    suspend fun step(id: String, action: String, note: String?) = repo.providerAction(id, action, note)
    suspend fun commission(id: String, bps: Int?) = repo.setCommission(id, bps)
    suspend fun orders(status: String?) = repo.orders(status)
    suspend fun cancel(orderId: String, reason: String) = orders.act(orderId, "ADMIN_CANCEL", reason)
}
