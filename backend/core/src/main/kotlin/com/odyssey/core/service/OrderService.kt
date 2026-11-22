package com.odyssey.core.service

import com.odyssey.core.config.Effect
import com.odyssey.core.config.Message
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class Auditor(private val repo: AuditRepo, private val ids: Ids, private val clock: Clock) {
    fun log(actor: Actor, action: String, entity: String, entityId: String?, detail: String = "") {
        repo.add(AuditEntry(ids.newId(), actor.tenantId, actor.userId, actor.role.name, action, entity, entityId, detail, clock.now()))
    }
}

data class PlaceOrder(val locationId: Id, val itemIds: List<Id>, val variantKey: String, val startsAt: Instant, val assetId: Id?, val promoCode: String? = null)
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
    private val promos: PromoService,
    private val notifier: Notifier,
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

    /** Price breakdown for a basket, including an optional promo. Used by checkout before anything is held. */
    fun quote(tenant: Tenant, customer: User?, locationId: Id, itemIds: List<Id>, variantKey: String, promoCode: String?): Pair<Quote, PromoService.Applied?> {
        val b = basket(tenant, locationId, itemIds, variantKey)
        val subtotal = b.lines.sumOf { it.priceMinor }
        val applied = promoCode?.takeIf { it.isNotBlank() }?.let { promos.apply(tenant, customer?.id, it, subtotal) }
        return Pricing.quote(tenant.config, b.provider, subtotal, applied?.discountMinor ?: 0) to applied
    }

    fun place(tenant: Tenant, customer: User, cmd: PlaceOrder): Order {
        val b = basket(tenant, cmd.locationId, cmd.itemIds, cmd.variantKey)
        val asset = cmd.assetId?.let { r.assets.byId(it)?.takeIf { a -> a.ownerUserId == customer.id } ?: throw notFound("Saved ${tenant.config.asset?.label ?: "item"}") }
        val now = clock.now()
        val slots = availability.slotsNeeded(b.location, b.durationMinutes)
        val endsAt = cmd.startsAt.plus(Duration.ofMinutes((slots * b.location.slotMinutes).toLong()))
        val subtotal = b.lines.sumOf { it.priceMinor }
        val applied = cmd.promoCode?.takeIf { it.isNotBlank() }?.let { promos.apply(tenant, customer.id, it, subtotal) }
        val quote = Pricing.quote(tenant.config, b.provider, subtotal, applied?.discountMinor ?: 0)
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
                createdAt = now, updatedAt = now, discountMinor = quote.discountMinor, promoCode = applied?.code,
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
        val now = clock.now()
        if (order.holdExpiresAt != null && order.holdExpiresAt <= now) {
            apply(tenant, order, "EXPIRE", Actor.system(tenant.id), null)
            throw conflict("hold_expired", "Your slot hold expired. Please pick a slot again")
        }
        val result = gateway.charge(ChargeRequest(order.id, order.totalMinor, method, idempotencyKey, simulate))
        if (result.pending) return markPending(tenant, order, method, result.gatewayRef)
        if (!result.success) throw DomainException("payment_failed", result.failureReason ?: "Payment failed", 402)
        return capture(tenant, order.id, method, result.gatewayRef)
    }

    /** The bank has not answered yet. Keep the slot held a little longer and let the customer check again. */
    private fun markPending(tenant: Tenant, order: Order, method: String, gatewayRef: String?): Order = uow.run {
        val now = clock.now()
        val existing = r.payments.byOrder(order.id)
        if (existing == null) r.payments.insert(Payment(ids.newId(), tenant.id, order.id, order.totalMinor, 0, PaymentStatus.PENDING, method, gatewayRef, now))
        val held = order.copy(holdExpiresAt = now.plus(Duration.ofMinutes(tenant.config.availability.holdMinutes.toLong())), updatedAt = now)
        r.orders.update(held)
        held.copy(paymentPending = true)
    }

    /** Asks the gateway what happened to a pending payment. */
    fun checkPayment(tenant: Tenant, customer: User, orderId: Id): Order {
        val order = load(tenant, orderId)
        if (order.customerId != customer.id) throw forbidden()
        val payment = r.payments.byOrder(order.id) ?: return order
        return when (payment.status) {
            PaymentStatus.CAPTURED -> order
            PaymentStatus.PENDING -> {
                val result = gateway.status(payment.gatewayRef ?: "")
                when {
                    result.pending -> order.copy(paymentPending = true)
                    result.success -> capture(tenant, order.id, payment.method, payment.gatewayRef)
                    else -> { r.payments.update(payment.copy(status = PaymentStatus.FAILED)); throw DomainException("payment_failed", result.failureReason ?: "Payment failed", 402) }
                }
            }
            else -> order
        }
    }

    /** Called after a successful charge, and by the gateway webhook. Safe to call twice. */
    fun capture(tenant: Tenant, orderId: Id, method: String, gatewayRef: String?): Order = uow.run {
        val order = load(tenant, orderId)
        val existing = r.payments.byOrder(order.id)
        if (existing?.status == PaymentStatus.CAPTURED) return@run order
        if (existing == null) r.payments.insert(Payment(ids.newId(), tenant.id, order.id, order.totalMinor, 0, PaymentStatus.CAPTURED, method, gatewayRef, clock.now()))
        else r.payments.update(existing.copy(status = PaymentStatus.CAPTURED))
        ledger.paymentCaptured(order)
        val system = Actor.system(tenant.id)
        var o = apply(tenant, order, "PAYMENT_CAPTURED", system, null)
        notifier.sendToProvider(tenant.id, o.providerId, "New ${tenant.config.labels.order.lowercase()}", "${whenText(o)} · ${o.customerName} · ${o.lines.joinToString { it.name }}", o.id)
        val location = r.locations.byId(o.locationId) ?: throw notFound("Location")
        if (location.autoAccept) o = apply(tenant, o, "ACCEPT", system, null)
        o
    }

    // ---- rescheduling ----------------------------------------------------------------------------------------

    /** Moves a paid booking to another slot. Allowed until the free-cancellation cutoff, at most twice. */
    fun reschedule(tenant: Tenant, customer: User, orderId: Id, newStart: Instant): Order {
        val first = load(tenant, orderId)
        if (first.customerId != customer.id) throw forbidden()
        return uow.run(first.locationId) {
            val order = load(tenant, orderId)
            val flow = tenant.config.flow
            val now = clock.now()
            if (order.status != flow.paid && order.status != flow.accepted) throw conflict("invalid_state", "This ${tenant.config.labels.order.lowercase()} can no longer be moved")
            val cutoff = order.startsAt.minus(Duration.ofHours(tenant.config.cancellation.fullRefundHoursBefore.toLong()))
            if (now.isAfter(cutoff)) throw conflict("too_late", "You can move it up to ${tenant.config.cancellation.fullRefundHoursBefore} hours before it starts")
            if (order.reschedules >= 2) throw conflict("limit_reached", "This ${tenant.config.labels.order.lowercase()} was already moved twice")
            val location = r.locations.byId(order.locationId) ?: throw notFound("Location")
            val duration = order.lines.sumOf { it.durationMinutes }
            availability.ensureBookable(tenant, location, newStart, duration, now, excludeOrderId = order.id)
            val slots = availability.slotsNeeded(location, duration)
            val moved = order.copy(startsAt = newStart, endsAt = newStart.plus(Duration.ofMinutes((slots * location.slotMinutes).toLong())), updatedAt = now, reschedules = order.reschedules + 1, reminded = false)
            r.orders.update(moved)
            r.orders.addEvent(OrderEvent(order.id, order.status, order.status, "RESCHEDULE", customer.id, "Moved to ${whenText(moved)}", now))
            notifier.sendToProvider(tenant.id, order.providerId, "${tenant.config.labels.order} moved", "${order.customerName} moved to ${whenText(moved)}", order.id)
            moved
        }
    }

    // ---- state machine ---------------------------------------------------------------------------------------

    fun act(tenant: Tenant, actor: Actor, orderId: Id, action: String, note: String?): Order = uow.run {
        val order = load(tenant, orderId)
        authorize(order, actor)
        apply(tenant, order, action, actor, note)
    }

    /** Counter check-in by booking code, for the scan and type-the-code flows. */
    fun checkInByCode(tenant: Tenant, actor: Actor, code: String): Order = uow.run {
        if (!actor.role.isProvider) throw forbidden()
        val normalized = code.filter { it.isLetterOrDigit() }.uppercase()
        val order = r.orders.byCode(tenant.id, normalized, actor.providerId) ?: throw notFound("Booking with that code")
        apply(tenant, order, "CHECK_IN", actor, null)
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
        tellCustomer(tenant, updated, action, note)
        return updated
    }

    /** Messages the customer gets as the booking moves along. Wording comes from the tenant's templates, with neutral defaults. */
    private fun tellCustomer(tenant: Tenant, o: Order, action: String, note: String?) {
        val customerId = o.customerId ?: return
        val what = tenant.config.labels.order.lowercase()
        val fallback = when (action) {
            "ACCEPT" -> Message("Your $what is confirmed", "{location}, {when}. Show code {code}.")
            "REJECT" -> Message("Your $what was declined", "{note} You are being refunded in full.")
            "CANCEL_BY_PROVIDER", "ADMIN_CANCEL" -> Message("Your $what was cancelled", "{note} You are being refunded in full.")
            "CHECK_IN" -> Message("Checked in", "{location} has checked you in.")
            "START" -> Message("Work has started", "{services} has started.")
            "COMPLETE" -> Message("Your $what is complete", "{location} has finished. Thank you!")
            "NO_SHOW" -> Message("Marked as a no-show", "You did not arrive for your slot at {location}.")
            else -> return
        }
        val m = tenant.config.messages[action] ?: fallback
        notifier.send(tenant.id, customerId, render(m.title, o, note), render(m.body, o, note), o.id)
    }

    private fun render(template: String, o: Order, note: String?): String {
        val loc = r.locations.byId(o.locationId)
        val whenText = whenText(o)
        return template.replace("{location}", o.locationName).replace("{address}", loc?.address ?: "").replace("{when}", whenText).replace("{time}", whenText.substringAfter(", "))
            .replace("{code}", o.code).replace("{asset}", o.assetLabel ?: "").replace("{services}", o.lines.joinToString { it.name }).replace("{note}", note ?: "").trim()
    }

    private fun whenText(o: Order): String {
        val zone = ZoneId.of(r.locations.byId(o.locationId)?.timezone ?: "Asia/Kolkata")
        return DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(zone).format(o.startsAt)
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

    /** Sends a reminder about an hour before each confirmed booking. Run on a timer. */
    fun sendReminders(): Int {
        var count = 0
        val now = clock.now()
        for (tenant in r.tenants.all()) {
            val due = r.orders.dueForReminder(now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(75)), listOf(tenant.config.flow.accepted))
            for (o in due.filter { it.tenantId == tenant.id && it.customerId != null }) {
                val m = tenant.config.messages["REMINDER"] ?: Message("Reminder: {services} at {time}", "{location}. Show code {code}.")
                notifier.send(tenant.id, o.customerId!!, render(m.title, o, null), render(m.body, o, null), o.id)
                r.orders.update(o.copy(reminded = true)); count++
            }
        }
        return count
    }

    // ---- queries and reviews ---------------------------------------------------------------------------------

    fun get(tenant: Tenant, actor: Actor, orderId: Id): Order {
        val order = load(tenant, orderId)
        authorize(order, actor)
        return order.copy(paymentPending = r.payments.byOrder(order.id)?.status == PaymentStatus.PENDING)
    }

    fun events(orderId: Id): List<OrderEvent> = r.orders.events(orderId)

    fun mine(customerId: Id): List<Order> = r.orders.byCustomer(customerId)

    fun review(tenant: Tenant, customer: User, orderId: Id, rating: Int, comment: String, tags: List<String> = emptyList()): Review {
        val order = load(tenant, orderId)
        if (order.customerId != customer.id) throw forbidden()
        if (order.status != tenant.config.flow.completed) throw conflict("not_completed", "You can review once it is completed")
        if (rating !in 1..5) throw invalid("Rating must be between 1 and 5")
        if (r.reviews.byOrder(orderId) != null) throw conflict("already_reviewed", "Already reviewed")
        return uow.run {
            val review = Review(ids.newId(), tenant.id, orderId, order.locationId, customer.name, rating, comment.take(500), clock.now(), tags.take(8))
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
