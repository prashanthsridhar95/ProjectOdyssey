package com.odyssey.core.service

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@Serializable
data class BillingStatement(
    val month: String, val orders: Int, val includedOrders: Int, val overageOrders: Int,
    val baseSubscriptionMinor: Long, val perOrderChargeMinor: Long, val totalMinor: Long,
)

@Serializable
data class Dashboard(
    val ordersTotal: Int, val ordersToday: Int, val gmvMinor: Long, val commissionMinor: Long,
    val cancelled: Int, val completed: Int, val providersByStatus: Map<String, Int>, val customers: Int,
)

/** Back-office: tenant settings, payouts, billing for the framework owner, dashboard numbers. */
class AdminService(private val r: Repos, private val uow: UnitOfWork, private val ledger: Ledger, private val auditor: Auditor, private val clock: Clock, private val ids: Ids) {

    fun updateConfig(tenant: Tenant, actor: Actor, config: TenantConfig): Tenant {
        if (actor.role != Role.ADMIN) throw forbidden()
        if (config.money.defaultCommissionBps !in 0..5000) throw invalid("Commission must be between 0% and 50%")
        val updated = tenant.copy(config = config)
        r.tenants.update(updated)
        auditor.log(actor, "CONFIG_UPDATED", "tenant", tenant.id)
        return updated
    }

    /** Pays each provider what is owed from completed orders that have not been paid out yet. */
    fun runPayouts(tenant: Tenant, actor: Actor): List<Payout> = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        val result = mutableListOf<Payout>()
        for (provider in r.providers.list(tenant.id)) {
            if (provider.bank == null) continue
            val orders = r.orders.settledUnpaid(provider.id)
            val amount = orders.sumOf { r.ledger.payableFor(it.id) }
            if (orders.isEmpty() || amount <= 0) continue
            val payout = Payout(ids.newId(), tenant.id, provider.id, amount, orders.size, "PROCESSED", clock.now())
            r.payouts.insert(payout)
            orders.forEach { r.orders.update(it.copy(payoutId = payout.id)) }
            ledger.payout(tenant.id, amount)
            result += payout
        }
        auditor.log(actor, "PAYOUTS_RUN", "payout", null, "${result.size} payouts")
        result
    }

    fun payouts(tenant: Tenant, providerId: Id?): List<Payout> = r.payouts.list(tenant.id, providerId)

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

    fun dashboard(tenant: Tenant): Dashboard {
        val now = clock.now()
        val dayStart = now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant()
        val flow = tenant.config.flow
        val all = r.orders.all(tenant.id, null, null, null, 5000).filter { it.source == OrderSource.APP && it.status != flow.initial && it.status != "EXPIRED" }
        val totals = r.ledger.totals(tenant.id, null, null)
        return Dashboard(
            ordersTotal = all.size,
            ordersToday = all.count { it.createdAt >= dayStart },
            gmvMinor = all.sumOf { it.totalMinor },
            commissionMinor = -(totals[Accounts.TENANT_COMMISSION] ?: 0L),
            cancelled = all.count { it.status == "CANCELLED" || it.status == "REJECTED" },
            completed = all.count { it.status == flow.completed },
            providersByStatus = r.providers.list(tenant.id).groupingBy { it.status.name }.eachCount(),
            customers = r.users.byRole(tenant.id, Role.CUSTOMER, 10_000).size,
        )
    }

    fun earnings(tenant: Tenant, actor: Actor, from: Instant?, to: Instant?): Map<String, Long> {
        if (actor.role != Role.PROVIDER_OWNER) throw forbidden()
        val orders = r.orders.byProvider(actor.providerId!!, null, null, from, to).filter { it.settled }
        val payable = orders.sumOf { r.ledger.payableFor(it.id) }
        val paidOut = r.payouts.list(tenant.id, actor.providerId).sumOf { it.amountMinor }
        return mapOf(
            "orders" to orders.size.toLong(),
            "grossMinor" to orders.sumOf { it.subtotalMinor },
            "commissionMinor" to orders.sumOf { it.commissionMinor },
            "earnedMinor" to payable,
            "paidOutMinor" to paidOut,
            "pendingMinor" to (r.orders.settledUnpaid(actor.providerId).sumOf { r.ledger.payableFor(it.id) }),
        )
    }
}
