package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Serializable
data class EarningsReport(
    val from: String, val to: String, val orders: Int, val serviceMinor: Long, val commissionMinor: Long, val commissionBps: Int,
    val gatewayFeeMinor: Long, val netMinor: Long, val pendingMinor: Long, val paidOutMinor: Long, val nextPayoutDate: String, val bankMasked: String? = null,
)

@Serializable
data class Overview(
    val range: String, val netMinor: Long, val netDeltaPct: Int? = null, val bookings: Int, val bookingsDelta: Int, val utilisationPct: Int,
    val ratingAvg: Double, val ratingCount: Int, val newReviews: Int, val days: List<String>, val hours: List<Int>, val heatmap: List<List<Int>>, val insight: String,
)

/** Numbers a vendor sees about their own business. Everything is computed from orders and the ledger. */
class ProviderReports(private val r: Repos, private val clock: Clock) {

    private fun zone(l: Location?) = ZoneId.of(l?.timezone ?: "Asia/Kolkata")
    private fun settledIn(providerId: Id, from: Instant, to: Instant) = r.orders.byProvider(providerId, null, null, from, to).filter { it.settled && it.source == OrderSource.APP }

    fun earnings(tenant: Tenant, actor: Actor, from: Instant, to: Instant): EarningsReport {
        if (actor.role != Role.PROVIDER_OWNER) throw forbidden()
        val provider = r.providers.byId(actor.providerId!!) ?: throw notFound("Provider")
        val orders = settledIn(provider.id, from, to)
        val sums = r.ledger.sumByAccount(orders.map { it.id })
        val net = -(sums[Accounts.PROVIDER_PAYABLE] ?: 0L)
        val fee = -(sums[Accounts.GATEWAY_FEE] ?: 0L)
        val service = orders.sumOf { it.subtotalMinor }
        val commission = orders.sumOf { it.commissionMinor }
        val unpaid = r.orders.settledUnpaid(provider.id)
        val pending = -(r.ledger.sumByAccount(unpaid.map { it.id })[Accounts.PROVIDER_PAYABLE] ?: 0L)
        val today = LocalDate.now(zone(null))
        val weekday = runCatching { DayOfWeek.valueOf(tenant.config.money.payoutWeekday) }.getOrDefault(DayOfWeek.MONDAY)
        return EarningsReport(
            from.toString(), to.toString(), orders.size, service, commission, if (service > 0) Math.round(commission * 10_000.0 / service).toInt() else 0, fee, net,
            pending, r.payouts.list(tenant.id, provider.id).filter { it.status == "PROCESSED" }.sumOf { it.amountMinor }, today.with(TemporalAdjusters.next(weekday)).toString(), provider.bank?.let { "${it.accountName} ${it.accountNumberMasked}" },
        )
    }

    /** One CSV row per settled booking, for the owner's accountant. */
    fun statementCsv(tenant: Tenant, actor: Actor, from: Instant, to: Instant): String {
        if (actor.role != Role.PROVIDER_OWNER) throw forbidden()
        val orders = settledIn(actor.providerId!!, from, to)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = orders.map { o ->
            val sums = r.ledger.sumByAccount(listOf(o.id))
            listOf(o.code, o.startsAt.toString(), q(o.customerName), q(o.lines.joinToString("; ") { it.name }), o.subtotalMinor / 100.0, o.commissionMinor / 100.0, -(sums[Accounts.GATEWAY_FEE] ?: 0L) / 100.0, -(sums[Accounts.PROVIDER_PAYABLE] ?: 0L) / 100.0).joinToString(",")
        }
        return (listOf("code,start,customer,services,service_amount,commission,gateway_fee,net") + rows).joinToString("\n")
    }

    fun overview(tenant: Tenant, actor: Actor, locationId: Id?, range: String): Overview {
        if (!actor.role.isProvider) throw forbidden()
        val days = when (range) { "today" -> 1; "month" -> 30; else -> 7 }
        val locations = r.locations.byProvider(actor.providerId!!).filter { locationId == null || it.id == locationId }
        val loc = locations.firstOrNull()
        val z = zone(loc)
        val now = clock.now()
        val todayStart = LocalDate.now(z).atStartOfDay(z).toInstant()
        val from = todayStart.minus(Duration.ofDays(days - 1L))
        val to = todayStart.plus(Duration.ofDays(1))
        val prevFrom = from.minus(Duration.ofDays(days.toLong()))
        val flow = tenant.config.flow
        val dead = setOf(flow.initial, "EXPIRED", "REJECTED", "CANCELLED")
        fun inRange(a: Instant, b: Instant) = r.orders.byProvider(actor.providerId, null, null, a, b).filter { it.locationId in locations.map { l -> l.id } }
        val cur = inRange(from, to)
        val prev = inRange(prevFrom, from)
        fun net(os: List<Order>) = -(r.ledger.sumByAccount(os.filter { it.settled && it.source == OrderSource.APP }.map { it.id })[Accounts.PROVIDER_PAYABLE] ?: 0L)
        val netNow = net(cur); val netPrev = net(prev)
        val bookings = cur.count { it.status !in dead }; val bookingsPrev = prev.count { it.status !in dead }
        val capacityMinutes = locations.sumOf { l -> Duration.between(l.openTime, l.closeTime).toMinutes() * l.capacity } * days
        val busyMinutes = cur.filter { it.status !in dead }.sumOf { Duration.between(it.startsAt, it.endsAt).toMinutes() }
        val utilisation = if (capacityMinutes > 0) (busyMinutes * 100 / capacityMinutes).toInt().coerceIn(0, 100) else 0

        // Busiest hours: share of bay time used per weekday and hour over the last four weeks, in four shades.
        val hours = (8..20).toList()
        val hist = inRange(todayStart.minus(Duration.ofDays(27)), to).filter { it.status !in dead }
        val capacity = locations.sumOf { it.capacity }.coerceAtLeast(1)
        val heat = DayOfWeek.entries.map { dow ->
            hours.map { h ->
                var used = 0L; var total = 0L
                for (week in 0 until 4) {
                    val day = LocalDate.now(z).minusDays(27L - 0).with(TemporalAdjusters.nextOrSame(dow)).plusDays(week * 7L - 0)
                    val s = day.atTime(h, 0).atZone(z).toInstant(); val e = s.plusSeconds(3600)
                    used += hist.count { it.startsAt < e && it.endsAt > s }; total += capacity
                }
                val share = if (total > 0) used * 100 / total else 0
                when { share < 25 -> 0; share < 50 -> 1; share < 75 -> 2; else -> 3 }
            }
        }
        val reviews = r.reviews.byLocation(loc?.id ?: "", 200)
        val insight = when { utilisation < 40 -> "Utilisation is under 40%. Consider happy-hour pricing for quiet slots."; utilisation > 85 -> "You are nearly full. Consider adding a bay or raising prices at peak."; else -> "Utilisation is healthy." }
        return Overview(
            range, netNow, if (netPrev > 0) ((netNow - netPrev) * 100 / netPrev).toInt() else null, bookings, bookings - bookingsPrev, utilisation,
            locations.map { it.ratingAvg }.average().takeIf { !it.isNaN() } ?: 0.0, locations.sumOf { it.ratingCount }, reviews.count { it.createdAt >= from },
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), hours, heat, insight,
        )
    }

    fun reviews(actor: Actor, limit: Int): List<Review> {
        if (!actor.role.isProvider) throw forbidden()
        return r.locations.byProvider(actor.providerId!!).flatMap { r.reviews.byLocation(it.id, limit) }.sortedByDescending { it.createdAt }.take(limit)
    }
}
