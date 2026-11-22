package com.odyssey.core.service

import com.odyssey.core.config.Effect
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Duration
import java.time.Instant

class Auditor(private val repo: AuditRepo, private val ids: Ids, private val clock: Clock) {
    fun log(actor: Actor, action: String, entity: String, entityId: String?, detail: String = "") {
        repo.add(AuditEntry(ids.newId(), actor.tenantId, actor.userId, actor.role.name, action, entity, entityId, detail, clock.now()))
    }
}

data class PlaceOrder(val locationId: Id, val itemIds: List<Id>, val variantKey: String, val startsAt: Instant, val assetId: Id?)
data class WalkIn(val locationId: Id, val itemIds: List<Id>, val variantKey: String, val startsAt: Instant, val customerName: String, val customerPhone: String)

/**
 * The order engine. Drives any order through the state machine defined in the tenant's [com.odyssey.core.config.OrderFlow].
 * Nothing here knows what is being ordered.
 */
class OrderService(
    private val r: Repos,
    private val uow: UnitOfWork,
    private val gateway: PaymentGateway,
    private val availability: SlotAvailability,
    private val ledger: Ledger,
    private val auditor: Auditor,
    private val clock: Clock,
    private val ids: Ids,
) {
    // ---- placing ---------------------------------------------------------------------------------------------

    private class Basket(val location: Location, val provider: Provider, val lines: List<OrderLine>, val durationMinutes: Int)

    private fun basket(tenant: Tenant, locationId: Id, itemIds: List<Id>, variantKey: String): Basket {
        val location = r.locations.byId(locationId)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Location")
        val provider = r.providers.byId(location.providerId) ?: throw notFound("Provider")
        if (provider.status != ProviderStatus.LIVE || !location.open) throw conflict("not_available", "This place is not taking bookings right now")
        if (itemIds.isEmpty()) throw invalid("Pick at least one service")
        if (tenant.config.variants.options.none { it.key == variantKey }) throw invalid("Unknown ${tenant.config.variants.name}")
        val unique = itemIds.distinct()
        val items = r.catalog.byIds(unique).filter { it.locationId == location.id && it.active }
        if (items.size != unique.size) throw invalid("One or more services are not available")
        val lines = items.map { item ->
            val price = item.prices[variantKey] ?: throw invalid("${item.name} is not offered for $variantKey")
            OrderLine(item.id, item.name, price, item.durationMinutes)
        }
        return Basket(location, provider, lines, lines.sumOf { it.durationMinutes })
    }

    fun place(tenant: Tenant, customer: User, cmd: PlaceOrder): Order {
        val b = basket(tenant, cmd.locationId, cmd.itemIds, cmd.variantKey)
        val asset = cmd.assetId?.let { r.assets.byId(it)?.takeIf { a -> a.ownerUserId == customer.id } ?: throw notFound("Saved ${tenant.config.asset?.label ?: "item"}") }
        val now = clock.now()
        val slots = availability.slotsNeeded(b.location, b.durationMinutes)
        val endsAt = cmd.startsAt.plus(Duration.ofMinutes((slots * b.location.slotMinutes).toLong()))
        val quote = Pricing.quote(tenant.config, b.provider, b.lines.sumOf { it.priceMinor })
        return uow.run(b.location.id) {
            availability.ensureBookable(tenant, b.location, cmd.startsAt, b.durationMinutes, now)
            val order = Order(
                id = ids.newId(), tenantId = tenant.id, code = ids.newCode(), customerId = customer.id,
                customerName = customer.name, customerPhone = customer.phone,
                providerId = b.provider.id, locationId = b.location.id, locationName = b.location.name,
                assetLabel = asset?.label, variantKey = cmd.variantKey, status = tenant.config.flow.initial,
                source = OrderSource.APP, startsAt = cmd.startsAt, endsAt = endsAt, lines = b.lines,
                subtotalMinor = quote.subtotalMinor, taxMinor = quote.taxMinor, feeMinor = quote.feeMinor, totalMinor = quote.totalMinor,
                commissionBps = quote.commissionBps, commissionMinor = quote.commissionMinor,
                holdExpiresAt = now.plus(Duration.ofMinutes(tenant.config.availability.holdMinutes.toLong())),
                createdAt = now, updatedAt = now,
            )
            r.orders.insert(order)
            r.orders.addEvent(OrderEvent(order.id, null, order.status, "PLACE", customer.id, null, now))
            order
        }
    }

    /** A customer standing at the counter: capacity is taken, no payment goes through the platform. */
    fun walkIn(tenant: Tenant, actor: Actor, cmd: WalkIn): Order {
        val b = basket(tenant, cmd.locationId, cmd.itemIds, cmd.variantKey)
        if (!actor.role.isProvider || actor.providerId != b.provider.id) throw forbidden()
        val now = clock.now()
        val slots = availability.slotsNeeded(b.location, b.durationMinutes)
        val endsAt = cmd.startsAt.plus(Duration.ofMinutes((slots * b.location.slotMinutes).toLong()))
        val subtotal = b.lines.sumOf { it.priceMinor }
        return uow.run(b.location.id) {
            availability.ensureBookable(tenant, b.location, cmd.startsAt, b.durationMinutes, now)
            val order = Order(
                id = ids.newId(), tenantId = tenant.id, code = ids.newCode(), customerId = null,
                customerName = cmd.customerName, customerPhone = cmd.customerPhone,
                providerId = b.provider.id, locationId = b.location.id, locationName = b.location.name,
                assetLabel = null, variantKey = cmd.variantKey, status = tenant.config.flow.accepted,
                source = OrderSource.WALK_IN, startsAt = cmd.startsAt, endsAt = endsAt, lines = b.lines,
                subtotalMinor = subtotal, taxMinor = 0, feeMinor = 0, totalMinor = subtotal,
                commissionBps = 0, commissionMinor = 0, holdExpiresAt = null, createdAt = now, updatedAt = now,
            )
            r.orders.insert(order)
            r.orders.addEvent(OrderEvent(order.id, null, order.status, "WALK_IN", actor.userId, null, now))
            auditor.log(actor, "ORDER_WALK_IN", "order", order.id)
            order
        }
    }

    // ---- paying ----------------------------------------------------------------------------------------------

    fun pay(tenant: Tenant, customer: User, orderId: Id, method: String, simulate: String?, idempotencyKey: String): Order {
        val order = load(tenant, orderId)
        if (order.customerId != customer.id) throw forbidden()
        val flow = tenant.config.flow
        if (r.payments.byOrder(order.id)?.status == PaymentStatus.CAPTURED) return order
        if (order.status != flow.initial) throw conflict("invalid_state", "This ${tenant.config.labels.order.lowercase()} can no longer be paid")
        if (order.holdExpiresAt != null && order.holdExpiresAt <= clock.now()) {
            apply(tenant, order, "EXPIRE", Actor.system(tenant.id), null)
            throw conflict("hold_expired", "Your slot hold expired. Please pick a slot again")
        }
        val result = gateway.charge(ChargeRequest(order.id, order.totalMinor, method, idempotencyKey, simulate))
        if (!result.success) throw DomainException("payment_failed", result.failureReason ?: "Payment failed", 402)
        return capture(tenant, order.id, method, result.gatewayRef)
    }

    /** Called after a successful charge, and by the gateway webhook. Safe to call twice. */
    fun capture(tenant: Tenant, orderId: Id, method: String, gatewayRef: String?): Order = uow.run {
        val order = load(tenant, orderId)
        if (r.payments.byOrder(order.id)?.status == PaymentStatus.CAPTURED) return@run order
        r.payments.insert(Payment(ids.newId(), tenant.id, order.id, order.totalMinor, 0, PaymentStatus.CAPTURED, method, gatewayRef, clock.now()))
        ledger.paymentCaptured(order)
        val system = Actor.system(tenant.id)
        var o = apply(tenant, order, "PAYMENT_CAPTURED", system, null)
        val location = r.locations.byId(o.locationId) ?: throw notFound("Location")
        if (location.autoAccept) o = apply(tenant, o, "ACCEPT", system, null)
        o
    }

    // ---- state machine ---------------------------------------------------------------------------------------

    fun act(tenant: Tenant, actor: Actor, orderId: Id, action: String, note: String?): Order = uow.run {
        val order = load(tenant, orderId)
        authorize(order, actor)
        apply(tenant, order, action, actor, note)
    }

    private fun authorize(order: Order, actor: Actor) {
        when {
            actor.role == Role.CUSTOMER -> if (order.customerId != actor.userId) throw forbidden()
            actor.role.isProvider -> if (order.providerId != actor.providerId) throw forbidden()
            actor.role.isBackoffice || actor.role == Role.SYSTEM -> Unit
            else -> throw forbidden()
        }
    }

    private fun apply(tenant: Tenant, order: Order, action: String, actor: Actor, note: String?): Order {
        val def = tenant.config.flow.transition(order.status, action)
            ?: throw conflict("invalid_transition", "Cannot $action when the ${tenant.config.labels.order.lowercase()} is ${order.status}")
        if (actor.role !in def.roles) throw forbidden("You cannot $action this ${tenant.config.labels.order.lowercase()}")
        val now = clock.now()
        var updated = order.copy(
            status = def.to, updatedAt = now, holdExpiresAt = null,
            cancellationReason = if (def.to != order.status && note != null) note else order.cancellationReason,
        )
        when (def.effect) {
            Effect.NONE -> Unit
            Effect.REFUND_FULL -> if (hasPayment(order)) { refund(order, order.totalMinor); ledger.refundedFull(order) }
            Effect.REFUND_BY_POLICY -> if (hasPayment(order)) updated = refundByPolicy(tenant, order, updated, now)
            Effect.SETTLE, Effect.SETTLE_NO_SHOW -> if (order.source == OrderSource.APP) {
                ledger.settled(order)
                updated = updated.copy(settled = true)
            }
        }
        r.orders.update(updated)
        r.orders.addEvent(OrderEvent(order.id, order.status, def.to, action, actor.userId, note, now))
        if (!def.system) auditor.log(actor, "ORDER_$action", "order", order.id, "${order.status} -> ${def.to}")
        return updated
    }

    private fun hasPayment(order: Order) = r.payments.byOrder(order.id)?.status == PaymentStatus.CAPTURED

    private fun refund(order: Order, amountMinor: Long) {
        val payment = r.payments.byOrder(order.id) ?: return
        gateway.refund(payment.gatewayRef ?: "", amountMinor)
        val status = if (amountMinor >= payment.amountMinor) PaymentStatus.REFUNDED else PaymentStatus.PARTIALLY_REFUNDED
        r.payments.update(payment.copy(refundedMinor = amountMinor, status = status))
    }

    private fun refundByPolicy(tenant: Tenant, order: Order, updated: Order, now: Instant): Order {
        if (!order.startsAt.isAfter(now)) throw conflict("too_late", "This slot has already started")
        val policy = tenant.config.cancellation
        val hoursBefore = Duration.between(now, order.startsAt).toMinutes() / 60.0
        if (hoursBefore >= policy.fullRefundHoursBefore) {
            refund(order, order.totalMinor)
            ledger.refundedFull(order)
            return updated
        }
        val retained = bps(order.totalMinor, policy.lateRetainBps)
        val refundAmount = order.totalMinor - retained
        refund(order, refundAmount)
        ledger.refundedPartial(order, refundAmount)
        return updated.copy(settled = true)
    }

    /** Releases slots held by unpaid orders. Run on a timer; also safe to run by hand. */
    fun expireHolds(): Int {
        var count = 0
        val now = clock.now()
        for (tenant in r.tenants.all()) {
            for (order in r.orders.expiredHolds(now, tenant.config.flow.initial)) {
                runCatching { uow.run { apply(tenant, order, "EXPIRE", Actor.system(tenant.id), null) } }.onSuccess { count++ }
            }
        }
        return count
    }

    // ---- queries and reviews ---------------------------------------------------------------------------------

    fun get(tenant: Tenant, actor: Actor, orderId: Id): Order {
        val order = load(tenant, orderId)
        authorize(order, actor)
        return order
    }

    fun events(orderId: Id): List<OrderEvent> = r.orders.events(orderId)

    fun mine(customerId: Id): List<Order> = r.orders.byCustomer(customerId)

    fun review(tenant: Tenant, customer: User, orderId: Id, rating: Int, comment: String): Review {
        val order = load(tenant, orderId)
        if (order.customerId != customer.id) throw forbidden()
        if (order.status != tenant.config.flow.completed) throw conflict("not_completed", "You can review once it is completed")
        if (rating !in 1..5) throw invalid("Rating must be between 1 and 5")
        if (r.reviews.byOrder(orderId) != null) throw conflict("already_reviewed", "Already reviewed")
        return uow.run {
            val review = Review(ids.newId(), tenant.id, orderId, order.locationId, customer.name, rating, comment.take(500), clock.now())
            r.reviews.insert(review)
            val location = r.locations.byId(order.locationId)!!
            val count = location.ratingCount + 1
            val avg = (location.ratingAvg * location.ratingCount + rating) / count
            r.locations.update(location.copy(ratingAvg = Math.round(avg * 100) / 100.0, ratingCount = count))
            review
        }
    }

    private fun load(tenant: Tenant, orderId: Id): Order =
        r.orders.byId(orderId)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Order")
}
