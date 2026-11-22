package com.odyssey.core.service

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.ports.Clock
import com.odyssey.core.ports.Ids
import com.odyssey.core.ports.LedgerRepo

data class Quote(val subtotalMinor: Long, val taxMinor: Long, val feeMinor: Long, val totalMinor: Long, val commissionBps: Int, val commissionMinor: Long, val discountMinor: Long = 0)

fun bps(amountMinor: Long, bps: Int): Long = Math.round(amountMinor * bps / 10_000.0)

object Pricing {
    /** Commission is taken on the service amount only, never on tax or customer fees. */
    fun quote(config: TenantConfig, provider: Provider?, subtotalMinor: Long, discountMinor: Long = 0): Quote {
        val commissionBps = provider?.commissionBps ?: config.money.defaultCommissionBps
        val fee = config.money.customerFeeMinor
        // Tax applies to the service plus the convenience fee; a promo reduces what the customer pays after tax.
        val tax = bps(subtotalMinor + fee, config.money.taxBps)
        val discount = minOf(discountMinor, subtotalMinor + fee + tax)
        return Quote(subtotalMinor, tax, fee, subtotalMinor + tax + fee - discount, commissionBps, bps(subtotalMinor, commissionBps), discount)
    }
}

/** Ledger accounts. Positive = debit, negative = credit; every transaction sums to zero. */
object Accounts {
    const val GATEWAY = "gateway"
    const val ESCROW = "escrow"
    const val PROVIDER_PAYABLE = "provider_payable"
    const val TENANT_COMMISSION = "tenant_commission"
    const val TAX_PAYABLE = "tax_payable"
    const val CUSTOMER_FEE = "customer_fee"
    const val BANK = "bank"
}

class Ledger(private val repo: LedgerRepo, private val ids: Ids, private val clock: Clock) {
    fun post(tenantId: Id, orderId: Id?, type: String, lines: List<Pair<String, Long>>) {
        val nonZero = lines.filter { it.second != 0L }
        require(nonZero.sumOf { it.second } == 0L) { "Unbalanced ledger transaction $type: $lines" }
        if (nonZero.isEmpty()) return
        val txn = ids.newId()
        val now = clock.now()
        repo.post(nonZero.map { LedgerEntry(ids.newId(), tenantId, orderId, txn, type, it.first, it.second, now) })
    }

    fun paymentCaptured(o: Order) = post(o.tenantId, o.id, "PAYMENT_CAPTURED", listOf(Accounts.GATEWAY to o.totalMinor, Accounts.ESCROW to -o.totalMinor))

    /** Completed or no-show: the provider earns the service amount minus commission; tax and fee go to their accounts. */
    fun settled(o: Order) = post(
        o.tenantId, o.id, "SETTLED",
        listOf(
            Accounts.ESCROW to o.totalMinor,
            Accounts.PROVIDER_PAYABLE to -(o.subtotalMinor - o.commissionMinor),
            // A promo is funded by the tenant, so it comes out of the tenant's commission, not the vendor's share.
            Accounts.TENANT_COMMISSION to -(o.commissionMinor - o.discountMinor),
            Accounts.TAX_PAYABLE to -o.taxMinor,
            Accounts.CUSTOMER_FEE to -o.feeMinor,
        ),
    )

    fun refundedFull(o: Order) = post(o.tenantId, o.id, "REFUND_FULL", listOf(Accounts.ESCROW to o.totalMinor, Accounts.GATEWAY to -o.totalMinor))

    /** Late cancellation: the customer gets [refundMinor] back; the retained part is shared between provider and tenant. */
    fun refundedPartial(o: Order, refundMinor: Long): Long {
        val retained = o.totalMinor - refundMinor
        val commission = bps(retained, o.commissionBps)
        post(
            o.tenantId, o.id, "REFUND_PARTIAL",
            listOf(
                Accounts.ESCROW to o.totalMinor,
                Accounts.GATEWAY to -refundMinor,
                Accounts.PROVIDER_PAYABLE to -(retained - commission),
                Accounts.TENANT_COMMISSION to -commission,
            ),
        )
        return retained - commission
    }

    /** A dispute upheld after the booking was settled: the customer is refunded and the vendor's payable is reduced. */
    fun disputeRefund(o: Order, amountMinor: Long) = post(o.tenantId, o.id, "DISPUTE_REFUND", listOf(Accounts.PROVIDER_PAYABLE to amountMinor, Accounts.GATEWAY to -amountMinor))

    fun payout(tenantId: Id, amountMinor: Long) = post(tenantId, null, "PAYOUT", listOf(Accounts.PROVIDER_PAYABLE to amountMinor, Accounts.BANK to -amountMinor))
}
