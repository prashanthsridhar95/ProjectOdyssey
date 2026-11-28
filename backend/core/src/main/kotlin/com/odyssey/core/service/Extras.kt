package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Instant

/** Stores an in-app notification and hands a push to the sender. */
class Notifier(private val r: Repos, private val push: PushSender, private val ids: Ids, private val clock: Clock) {
    fun send(tenantId: Id, userId: Id, title: String, body: String, orderId: Id?) {
        r.notifications.insert(Notification(ids.newId(), tenantId, userId, title, body, orderId, false, clock.now()))
        runCatching { push.send(userId, title, body) }
    }

    fun sendToProvider(tenantId: Id, providerId: Id, title: String, body: String, orderId: Id?) {
        r.users.byProvider(providerId).filter { it.active }.forEach { send(tenantId, it.id, title, body, orderId) }
    }
}

/** Problem reports from customers and how the back office resolves them. */
class SupportService(
    private val r: Repos, private val uow: UnitOfWork, private val gateway: PaymentGateway, private val ledger: Ledger,
    private val auditor: Auditor, private val notifier: Notifier, private val ids: Ids, private val clock: Clock,
) {
    val categories = listOf("QUALITY", "DAMAGE", "WAIT", "CHARGE")

    fun report(tenant: Tenant, customer: User, orderId: Id, category: String, description: String, photos: List<String>): Ticket {
        val order = r.orders.byId(orderId)?.takeIf { it.tenantId == tenant.id && it.customerId == customer.id } ?: throw notFound("Order")
        if (order.status == tenant.config.flow.initial || order.status == "EXPIRED") throw conflict("not_paid", "Nothing to report yet")
        if (category !in categories) throw invalid("Choose what happened")
        if (description.isBlank()) throw invalid("Describe the problem")
        if (photos.size > 3 || photos.any { it.length > 600_000 }) throw invalid("Up to 3 photos, each under about 400 KB")
        val ticket = Ticket(ids.newId(), tenant.id, order.id, customer.id, order.providerId, category, description.take(1000), photos, TicketStatus.OPEN, null, 0, clock.now())
        r.tickets.insert(ticket)
        notifier.sendToProvider(tenant.id, order.providerId, "Customer reported a problem", "Booking ${order.code}: ${description.take(80)}", order.id)
        return ticket
    }

    /** UPHELD refunds [refundMinor] (up to what was paid) from the vendor's share; REJECTED closes it. */
    fun resolve(tenant: Tenant, actor: Actor, ticketId: Id, uphold: Boolean, refundMinor: Long, note: String): Ticket = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.SUPPORT) throw forbidden()
        val ticket = r.tickets.byId(ticketId)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Ticket")
        if (ticket.status != TicketStatus.OPEN) throw conflict("already_resolved", "This report is already resolved")
        val order = r.orders.byId(ticket.orderId) ?: throw notFound("Order")
        var refunded = 0L
        if (uphold) {
            val payment = r.payments.byOrder(order.id) ?: throw conflict("no_payment", "There is no payment to refund")
            refunded = refundMinor.coerceIn(0, payment.amountMinor - payment.refundedMinor)
            if (refunded <= 0) throw invalid("Enter a refund amount")
            if (!order.settled) throw conflict("not_settled", "Wait until the booking is completed before refunding a dispute")
            gateway.refund(payment.gatewayRef ?: "", refunded)
            val total = payment.refundedMinor + refunded
            r.payments.update(payment.copy(refundedMinor = total, status = if (total >= payment.amountMinor) PaymentStatus.REFUNDED else PaymentStatus.PARTIALLY_REFUNDED))
            ledger.disputeRefund(order, refunded)
        }
        val updated = ticket.copy(status = if (uphold) TicketStatus.UPHELD else TicketStatus.REJECTED, resolutionNote = note, refundMinor = refunded, resolvedAt = clock.now())
        r.tickets.update(updated)
        auditor.log(actor, if (uphold) "TICKET_UPHELD" else "TICKET_REJECTED", "ticket", ticketId, note)
        notifier.send(tenant.id, ticket.userId, if (uphold) "Your report was upheld" else "About your report", if (uphold) "We refunded ${refunded / 100}. $note" else note, order.id)
        updated
    }
}
