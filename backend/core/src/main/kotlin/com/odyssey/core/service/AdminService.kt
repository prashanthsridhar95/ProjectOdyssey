package com.odyssey.core.service

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

@Serializable
data class BillingStatement(
    val month: String, val orders: Int, val includedOrders: Int, val overageOrders: Int,
    val baseSubscriptionMinor: Long, val perOrderChargeMinor: Long, val totalMinor: Long,
)

@Serializable data class DayPoint(val date: String, val gmvMinor: Long)
@Serializable data class AlertItem(val kind: String, val title: String, val meta: String)
@Serializable
data class VendorRow(
    val providerId: String, val name: String, val bookings: Int, val gmvMinor: Long, val utilisationPct: Int,
    val ratingAvg: Double, val cancelPct: Double, val noShowPct: Double,
)

@Serializable
data class Dashboard(
    val range: String, val gmvMinor: Long, val gmvDeltaPct: Int? = null, val bookings: Int, val bookingsPerDay: Int, val takeRateBps: Int,
    val utilisationPct: Int, val cancellationPct: Double, val noShowPct: Double, val vendorsLive: Int, val vendorsInReview: Int,
    val series: List<DayPoint>, val alerts: List<AlertItem>, val topVendors: List<VendorRow>, val customers: Int = 0,
)

@Serializable
data class BatchRow(
    val providerId: String, val name: String, val bookings: Int, val serviceMinor: Long, val commissionMinor: Long, val gatewayFeeMinor: Long,
    val netMinor: Long, val account: String? = null, val status: String, val payoutId: String? = null, val failureReason: String? = null,
)

@Serializable data class Batch(val from: String, val to: String, val vendors: Int, val netMinor: Long, val commissionMinor: Long, val rows: List<BatchRow>)

@Serializable
data class CustomerRow(val id: String, val name: String, val phone: String, val bookings: Int, val spendMinor: Long, val lastBooking: String? = null, val active: Boolean)

@Serializable
data class OutlierRow(val itemId: String, val providerId: String, val locationName: String, val vendor: String, val item: String, val variant: String, val priceMinor: Long, val medianMinor: Long, val reason: String)

@Serializable data class RulePreview(val serviceMinor: Long, val commissionMinor: Long, val gatewayFeeMinor: Long, val payoutMinor: Long)

/** Back-office: tenant settings, payouts, billing for the framework owner, dashboard numbers, moderation, commission rules. */
class AdminService(private val r: Repos, private val uow: UnitOfWork, private val ledger: Ledger, private val auditor: Auditor, private val clock: Clock, private val ids: Ids) {

    private val zone = ZoneId.of("Asia/Kolkata")

    fun updateConfig(tenant: Tenant, actor: Actor, config: TenantConfig): Tenant {
        if (actor.role != Role.ADMIN) throw forbidden()
        if (config.money.defaultCommissionBps !in 0..5000) throw invalid("Commission must be between 0% and 50%")
        val updated = tenant.copy(config = config)
        r.tenants.update(updated)
        auditor.log(actor, "CONFIG_UPDATED", "tenant", tenant.id)
        return updated
    }

    // ---- payout batches ----

    private val ifsc = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")

    private fun unpaid(providerId: Id, from: Instant?, to: Instant?) = r.orders.settledUnpaid(providerId).filter { (from == null || it.startsAt >= from) && (to == null || it.startsAt < to) }

    fun batch(tenant: Tenant, from: Instant?, to: Instant?): Batch {
        val lastPayouts = r.payouts.list(tenant.id, null).groupBy { it.providerId }
        val rows = r.providers.list(tenant.id).mapNotNull { p ->
            val orders = unpaid(p.id, from, to)
            val failed = lastPayouts[p.id]?.firstOrNull { it.status == "FAILED" }
            if (orders.isEmpty() && failed == null) return@mapNotNull null
            val sums = r.ledger.sumByAccount(orders.map { it.id })
            val status = when { failed != null -> "FAILED"; p.payoutHold -> "HELD"; p.bank == null -> "NO_BANK"; else -> "READY" }
            BatchRow(
                p.id, p.name, orders.size, orders.sumOf { it.subtotalMinor - it.vendorDiscountMinor }, orders.sumOf { it.commissionMinor }, -(sums[Accounts.GATEWAY_FEE] ?: 0L), -(sums[Accounts.PROVIDER_PAYABLE] ?: 0L),
                p.bank?.let { "${it.ifsc.take(4)} ${it.accountNumberMasked}" }, status, failed?.id, failed?.failureReason,
            )
        }
        val ready = rows.filter { it.status == "READY" }
        return Batch(from?.toString() ?: "", to?.toString() ?: "", ready.size, ready.sumOf { it.netMinor }, ready.sumOf { it.commissionMinor }, rows)
    }

    /** Pays each provider what is owed from completed orders not yet paid out. Held vendors wait; a bank that rejects the transfer leaves a failed payout to retry. */
    fun runPayouts(tenant: Tenant, actor: Actor, from: Instant? = null, to: Instant? = null): List<Payout> = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        val result = mutableListOf<Payout>()
        for (provider in r.providers.list(tenant.id)) {
            if (provider.payoutHold || provider.bank == null) continue
            val payout = payProvider(tenant, provider, from, to, null) ?: continue
            result += payout
        }
        auditor.log(actor, "PAYOUTS_RUN", "payout", null, "${result.count { it.status == "PROCESSED" }} paid, ${result.count { it.status == "FAILED" }} failed")
        result
    }

    private fun payProvider(tenant: Tenant, provider: Provider, from: Instant?, to: Instant?, existing: Payout?): Payout? {
        val orders = unpaid(provider.id, from, to)
        if (orders.isEmpty()) return null
        val amount = orders.sumOf { r.ledger.payableFor(it.id) }
        if (amount <= 0) return null
        val bank = provider.bank
        val reason = if (bank == null || !ifsc.matches(bank.ifsc.uppercase())) "Bank rejected the IFSC code" else null
        val base = existing ?: Payout(ids.newId(), tenant.id, provider.id, amount, orders.size, "PROCESSED", clock.now(), periodFrom = from, periodTo = to)
        if (reason != null) {
            val failed = base.copy(amountMinor = amount, orderCount = orders.size, status = "FAILED", failureReason = reason)
            if (existing == null) r.payouts.insert(failed) else r.payouts.update(failed)
            return failed
        }
        val paid = base.copy(amountMinor = amount, orderCount = orders.size, status = "PROCESSED", failureReason = null, reference = "PO-" + ids.newCode(), createdAt = clock.now())
        if (existing == null) r.payouts.insert(paid) else r.payouts.update(paid)
        orders.forEach { r.orders.update(it.copy(payoutId = paid.id)) }
        ledger.payout(tenant.id, amount)
        return paid
    }

    fun retryPayout(tenant: Tenant, actor: Actor, payoutId: Id): Payout = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        val payout = r.payouts.byId(payoutId)?.takeIf { it.tenantId == tenant.id && it.status == "FAILED" } ?: throw notFound("Failed payout")
        val provider = r.providers.byId(payout.providerId) ?: throw notFound("Provider")
        val result = payProvider(tenant, provider, null, null, payout) ?: throw conflict("nothing_to_pay", "There is nothing left to pay")
        auditor.log(actor, "PAYOUT_RETRIED", "payout", payoutId, result.status)
        result
    }

    fun payouts(tenant: Tenant, providerId: Id?): List<Payout> = r.payouts.list(tenant.id, providerId)

    // ---- money views ----

    fun collections(tenant: Tenant, limit: Int) = r.payments.list(tenant.id, limit, refundedOnly = false)
    fun refunds(tenant: Tenant, limit: Int) = r.payments.list(tenant.id, limit, refundedOnly = true)
    fun ledgerEntries(tenant: Tenant, limit: Int) = r.ledger.recent(tenant.id, limit)

    /** What the framework owner invoices this tenant for a month: base subscription plus a charge per order above the threshold. */
    fun billing(tenant: Tenant, month: YearMonth): BillingStatement {
        val from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val flow = tenant.config.flow
        val unpaid = flow.states.filter { it.name == flow.initial || it.name == "EXPIRED" }.map { it.name }
        val orders = r.orders.paidOrders(tenant.id, from, to, unpaid).filter { it.source == OrderSource.APP }.sortedBy { it.createdAt }
        val cfg = tenant.config.billing
        val overage = orders.drop(cfg.includedOrders)
        val perOrder = overage.sumOf { bps(it.subtotalMinor, cfg.perOrderBps) }
        return BillingStatement(month.toString(), orders.size, cfg.includedOrders, overage.size, cfg.baseSubscriptionMinor, perOrder, cfg.baseSubscriptionMinor + perOrder)
    }

    // ---- dashboard ----

    fun dashboard(tenant: Tenant, range: String): Dashboard {
        val days = when (range) { "today" -> 1; "30d" -> 30; else -> 7 }
        val now = clock.now()
        val to = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        val from = to.minus(Duration.ofDays(days.toLong()))
        val prevFrom = from.minus(Duration.ofDays(days.toLong()))
        val flow = tenant.config.flow
        val unpaid = setOf(flow.initial, "EXPIRED")
        val all = r.orders.all(tenant.id, null, null, null, 20_000).filter { it.source == OrderSource.APP && it.status !in unpaid }
        val dead = setOf("REJECTED", "CANCELLED")
        val cur = all.filter { it.startsAt >= from && it.startsAt < to }
        val prev = all.filter { it.startsAt >= prevFrom && it.startsAt < from }
        fun gmv(os: List<Order>) = os.filter { it.status !in dead }.sumOf { it.totalMinor }
        val live = r.providers.list(tenant.id, ProviderStatus.LIVE)
        val locations = live.flatMap { r.locations.byProvider(it.id) }
        val capacityMinutes = locations.sumOf { Duration.between(it.openTime, it.closeTime).toMinutes() * it.capacity } * days
        val kept = cur.filter { it.status !in dead }
        val busy = kept.sumOf { Duration.between(it.startsAt, it.endsAt).toMinutes() }
        val service = kept.sumOf { it.subtotalMinor }
        val series = (days - 1 downTo 0).map { back ->
            val d = to.minus(Duration.ofDays(back + 1L)).atZone(zone).toLocalDate()
            DayPoint(d.toString(), kept.filter { it.startsAt.atZone(zone).toLocalDate() == d }.sumOf { it.totalMinor })
        }

        fun rowFor(p: Provider): VendorRow {
            val os = cur.filter { it.providerId == p.id }
            val locs = r.locations.byProvider(p.id)
            val cap = locs.sumOf { Duration.between(it.openTime, it.closeTime).toMinutes() * it.capacity } * days
            val kp = os.filter { it.status !in dead }
            val rated = locs.filter { it.ratingCount > 0 }
            return VendorRow(
                p.id, p.name, kp.size, kp.sumOf { it.totalMinor }, if (cap > 0) (kp.sumOf { Duration.between(it.startsAt, it.endsAt).toMinutes() } * 100 / cap).toInt().coerceIn(0, 100) else 0,
                if (rated.isEmpty()) 0.0 else rated.sumOf { it.ratingAvg * it.ratingCount } / rated.sumOf { it.ratingCount },
                if (os.isEmpty()) 0.0 else Math.round(os.count { it.status in dead } * 1000.0 / os.size) / 10.0, if (os.isEmpty()) 0.0 else Math.round(os.count { it.status == "NO_SHOW" } * 1000.0 / os.size) / 10.0,
            )
        }

        val alerts = mutableListOf<AlertItem>()
        val names = r.providers.list(tenant.id).associate { it.id to it.name }
        r.payouts.list(tenant.id, null).filter { it.status == "FAILED" }.forEach { alerts += AlertItem("Payout", "Payout failed: ${names[it.providerId]}", "${it.failureReason ?: "Failed"} · retry or hold") }
        val last14 = all.filter { it.startsAt >= now.minus(Duration.ofDays(14)) }
        live.forEach { p ->
            val os = last14.filter { it.providerId == p.id }
            if (os.size >= 5) { val pct = os.count { it.status in dead } * 100 / os.size; if (pct >= 10) alerts += AlertItem("Quality", "${p.name} flagged", "Cancel rate $pct% over 14 days · consider pause") }
        }
        val outliers = moderationOutliers(tenant)
        if (outliers.isNotEmpty()) alerts += AlertItem("Menu", "${outliers.size} price outlier${if (outliers.size > 1) "s" else ""} to review", "${outliers.first().item} above ${outliers.first().medianMinor / 100 * 2}")
        val open = r.tickets.list(tenant.id, TicketStatus.OPEN)
        if (open.isNotEmpty()) alerts += AlertItem("Support", "${open.size} open ticket${if (open.size > 1) "s" else ""}", "${open.count { it.createdAt < now.minus(Duration.ofHours(24)) }} waiting over 24 hours")
        val review = r.providers.list(tenant.id).filter { it.status == ProviderStatus.SUBMITTED || it.status == ProviderStatus.UNDER_REVIEW }
        if (review.isNotEmpty()) alerts += AlertItem("Vendor", "${review.size} vendor${if (review.size > 1) "s" else ""} awaiting review", "Oldest submitted ${Duration.between(review.minOf { it.createdAt }, now).toDays()} days ago")

        val g = gmv(cur); val gp = gmv(prev)
        return Dashboard(
            range, g, if (gp > 0) ((g - gp) * 100 / gp).toInt() else null, kept.size, kept.size / days, if (service > 0) (kept.sumOf { it.commissionMinor } * 10_000 / service).toInt() else 0,
            if (capacityMinutes > 0) (busy * 100 / capacityMinutes).toInt().coerceIn(0, 100) else 0,
            if (cur.isEmpty()) 0.0 else Math.round(cur.count { it.status in dead } * 1000.0 / cur.size) / 10.0, if (cur.isEmpty()) 0.0 else Math.round(cur.count { it.status == "NO_SHOW" } * 1000.0 / cur.size) / 10.0,
            live.size, review.size, series, alerts, live.map(::rowFor).sortedByDescending { it.gmvMinor }.take(5), r.users.byRole(tenant.id, Role.CUSTOMER, 100_000).size,
        )
    }

    // ---- customers ----

    fun customers(tenant: Tenant, query: String?): List<CustomerRow> {
        val orders = r.orders.all(tenant.id, null, null, null, 20_000).filter { it.customerId != null && it.status !in setOf(tenant.config.flow.initial, "EXPIRED") }.groupBy { it.customerId!! }
        return r.users.byRole(tenant.id, Role.CUSTOMER, 5_000)
            .filter { query.isNullOrBlank() || it.name.contains(query, true) || it.phone.contains(query) }
            .map { u -> val os = orders[u.id] ?: emptyList(); CustomerRow(u.id, u.name, u.phone, os.size, os.filter { it.status !in setOf("REJECTED", "CANCELLED") }.sumOf { it.totalMinor }, os.maxOfOrNull { it.startsAt }?.toString(), u.active) }
            .sortedByDescending { it.spendMinor }
    }

    fun setCustomerActive(tenant: Tenant, actor: Actor, userId: Id, active: Boolean) = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.SUPPORT) throw forbidden()
        val user = r.users.byId(userId)?.takeIf { it.tenantId == tenant.id && it.role == Role.CUSTOMER } ?: throw notFound("Customer")
        r.users.update(user.copy(active = active))
        auditor.log(actor, if (active) "CUSTOMER_UNBLOCKED" else "CUSTOMER_BLOCKED", "user", userId)
    }

    // ---- menu moderation ----

    /** Prices far above what other vendors charge for the same service and vehicle type. */
    fun moderationOutliers(tenant: Tenant): List<OutlierRow> {
        val items = r.catalog.all(tenant.id).filter { it.active }
        val locations = items.map { it.locationId }.distinct().mapNotNull { r.locations.byId(it) }.associateBy { it.id }
        val providers = r.providers.list(tenant.id).associateBy { it.id }
        val out = mutableListOf<OutlierRow>()
        items.groupBy { it.name.lowercase() }.forEach { (_, group) ->
            tenant.config.variants.options.forEach { v ->
                val priced = group.mapNotNull { i -> i.prices[v.key]?.let { i to it } }
                if (priced.size < 3) return@forEach
                val sorted = priced.map { it.second }.sorted()
                val median = sorted[sorted.size / 2]
                priced.filter { (i, price) -> price > median * 18 / 10 && !i.priceReviewed }.forEach { (i, price) ->
                    val l = locations[i.locationId] ?: return@forEach
                    out += OutlierRow(i.id, l.providerId, l.name, providers[l.providerId]?.name ?: "", i.name, v.label, price, median, "Above ${(price * 100 / median)}% of the median")
                }
            }
        }
        return out
    }

    fun reviewOutlier(tenant: Tenant, actor: Actor, itemId: Id, hide: Boolean) = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.SUPPORT) throw forbidden()
        val item = r.catalog.byId(itemId)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Item")
        r.catalog.update(if (hide) item.copy(active = false) else item.copy(priceReviewed = true))
        auditor.log(actor, if (hide) "ITEM_HIDDEN" else "PRICE_APPROVED", "item", itemId, item.name)
    }

    // ---- commission rules ----

    fun rules(tenant: Tenant): List<CommissionRule> = r.rules.list(tenant.id).sortedByDescending { it.effectiveFrom }

    fun addRule(tenant: Tenant, actor: Actor, scope: RuleScope, target: String, rateBps: Int, from: Instant, until: Instant?): CommissionRule = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        if (rateBps !in 0..5000) throw invalid("Commission must be between 0% and 50%")
        if (until != null && !until.isAfter(from)) throw invalid("The end date must be after the start date")
        val label = when (scope) {
            RuleScope.VENDOR -> r.providers.byId(target)?.takeIf { it.tenantId == tenant.id }?.name ?: throw notFound("Vendor")
            RuleScope.OUTLET -> r.locations.byId(target)?.takeIf { it.tenantId == tenant.id }?.let { l -> "${r.providers.byId(l.providerId)?.name ?: ""} · ${l.name}" } ?: throw notFound("Outlet")
            RuleScope.CATEGORY -> target.takeIf { it.isNotBlank() } ?: throw invalid("Name a category")
        }
        val rule = CommissionRule(ids.newId(), tenant.id, scope, target, label, rateBps, from, until, actor.name.ifBlank { actor.role.name }, clock.now())
        r.rules.insert(rule)
        auditor.log(actor, "COMMISSION_RULE_ADDED", scope.name.lowercase(), rule.id, "$label ${rateBps / 100.0}% from $from")
        rule
    }

    fun deleteRule(tenant: Tenant, actor: Actor, id: Id) = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        r.rules.delete(tenant.id, id)
        auditor.log(actor, "COMMISSION_RULE_REMOVED", "rule", id)
    }

    /** What a vendor would get from a booking of [serviceMinor] at [rateBps]. */
    fun preview(tenant: Tenant, serviceMinor: Long, rateBps: Int): RulePreview {
        val commission = bps(serviceMinor, rateBps)
        val fee = bps(serviceMinor, tenant.config.money.gatewayFeeBps)
        return RulePreview(serviceMinor, commission, fee, serviceMinor - commission - fee)
    }
}
