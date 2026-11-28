package com.odyssey.core.service

import com.odyssey.core.config.Effect
import com.odyssey.core.config.Message
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class Auditor(private val repo: AuditRepo, private val ids: Ids, private val clock: Clock) {
    fun log(actor: Actor, action: String, entity: String, entityId: String?, detail: String = "") {
        repo.add(AuditEntry(ids.newId(), actor.tenantId, actor.userId, actor.role.name, action, entity, entityId, detail, clock.now()))
    }
}

data class PlaceOrder(val locationId: Id, val itemIds: List<Id>, val variantKey: String, val startsAt: Instant, val assetId: Id?, val promoCode: String? = null)
data class WalkIn(val locationId: Id, val itemIds: List<Id>, val variantKey: String, val startsAt: Instant?, val customerName: String, val customerPhone: String, val plate: String? = null, val checkIn: Boolean = true, val bay: Int? = null)

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
    private val referrals: ReferralService,
) {
    // ---- placing ---------------------------------------------------------------------------------------------

    /** Time a booking holds a bay. Services that use no bay (add-ons done alongside another service) add nothing. */
    private fun bayMinutes(lines: List<OrderLine>) = lines.filter { it.baysUsed > 0 }.sumOf { it.durationMinutes }

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
        items.firstOrNull { it.outOfStock }?.let { throw conflict("out_of_stock", "${it.name} is out of stock right now") }
        val lines = items.map { item ->
            val price = item.prices[variantKey] ?: throw invalid("${item.name} is not offered for $variantKey")
            OrderLine(item.id, item.name, price, item.durationMinutes, item.baysUsed, item.category)
        }
        return Basket(location, provider, lines, bayMinutes(lines))
    }

    /**
     * Prices a basket: the best automatic promotion for the slot (happy hour or date discount), then an optional code on what is left.
     * Vendor-funded parts lower the vendor's service amount, so commission is worked out per line on what the vendor actually charges.
     */
    private fun price(tenant: Tenant, customerId: Id?, b: Basket, variantKey: String, startsAt: Instant?, code: String?): PricedBasket {
        val subtotal = b.lines.sumOf { it.priceMinor }
        val zone = ZoneId.of(b.location.timezone)
        val best = startsAt?.let { PromotionEngine.best(r.promotions.activeFor(tenant.id, b.provider.id), b.location.id, b.lines, variantKey, LocalDateTime.ofInstant(it, zone)) }
        val promoLine = best?.perLine ?: b.lines.map { 0L }
        val voucher = code?.takeIf { it.isNotBlank() }?.let { promos.apply(tenant, customerId, it, subtotal - promoLine.sum(), b.provider.id) }
        val vendorLine = MutableList(b.lines.size) { i -> if (best?.promotion?.funder == Funder.VENDOR) promoLine[i] else 0L }
        if (voucher != null && voucher.funder == Funder.VENDOR) {
            PromotionEngine.allocate(voucher.discountMinor, b.lines.mapIndexed { i, l -> l.priceMinor - promoLine[i] }).forEachIndexed { i, v -> vendorLine[i] += v }
        }
        val platformDiscount = (if (best?.promotion?.funder == Funder.PLATFORM) best.totalMinor else 0L) + (if (voucher?.funder == Funder.PLATFORM) voucher.discountMinor else 0L)
        val resolver = CommissionResolver(r.rules.list(tenant.id), tenant.config)
        val now = clock.now()
        val commission = b.lines.withIndex().sumOf { (i, l) -> bps(l.priceMinor - vendorLine[i], resolver.rateBps(b.provider, b.location.id, l.category, now)) }
        val quote = Pricing.quote(tenant.config, b.provider, subtotal, platformDiscount, commission, vendorLine.sum())
        val discounts = buildList {
            best?.let { add(DiscountLine("PROMOTION", it.promotion.name, it.totalMinor, it.promotion.funder, it.promotion.id)) }
            voucher?.takeIf { it.discountMinor > 0 }?.let {
                add(DiscountLine(it.kind, if (it.kind == "REFERRAL") "Friend's code ${it.code}" else "Code ${it.code}", it.discountMinor, it.funder, it.code))
            }
        }
        return PricedBasket(quote, discounts, best?.promotion, voucher)
    }

    /** Price breakdown for a basket, including any promotion at [startsAt] and an optional code. Used by checkout before anything is held. */
    fun quote(tenant: Tenant, customer: User?, locationId: Id, itemIds: List<Id>, variantKey: String, promoCode: String?, startsAt: Instant? = null): PricedBasket {
        val b = basket(tenant, locationId, itemIds, variantKey)
        return price(tenant, customer?.id, b, variantKey, startsAt, promoCode)
    }

    data class SlotPrice(val priceMinor: Long, val originalMinor: Long, val deal: String?)

    /** What the chosen services cost at each slot start, before tax, so the slot picker can show happy-hour prices. Empty when a service has no price for the variant. */
    fun slotPrices(tenant: Tenant, location: Location, itemIds: List<Id>, variantKey: String?, starts: List<Instant>): Map<Instant, SlotPrice> {
        val variant = variantKey ?: tenant.config.variants.options.firstOrNull()?.key ?: return emptyMap()
        val items = r.catalog.byIds(itemIds.distinct()).filter { it.locationId == location.id }
        if (items.isEmpty() || items.any { !it.prices.containsKey(variant) }) return emptyMap()
        val lines = items.map { OrderLine(it.id, it.name, it.prices.getValue(variant), it.durationMinutes, it.baysUsed, it.category) }
        val offers = r.promotions.activeFor(tenant.id, location.providerId)
        val subtotal = lines.sumOf { it.priceMinor }
        val zone = ZoneId.of(location.timezone)
        return starts.associateWith { s ->
            val best = if (offers.isEmpty()) null else PromotionEngine.best(offers, location.id, lines, variant, LocalDateTime.ofInstant(s, zone))
            SlotPrice(subtotal - (best?.totalMinor ?: 0), subtotal, best?.let { PromotionEngine.amountText(it.promotion.kind, it.promotion.value, tenant.config.money.currency) })
        }
    }

    fun place(tenant: Tenant, customer: User, cmd: PlaceOrder): Order {
        val b = basket(tenant, cmd.locationId, cmd.itemIds, cmd.variantKey)
        val asset = cmd.assetId?.let { r.assets.byId(it)?.takeIf { a -> a.ownerUserId == customer.id } ?: throw notFound("Saved ${tenant.config.asset?.label ?: "item"}") }
        val now = clock.now()
        val slots = availability.slotsNeeded(b.location, b.durationMinutes)
        val endsAt = cmd.startsAt.plus(Duration.ofMinutes((slots * b.location.slotMinutes).toLong()))
        val priced = price(tenant, customer.id, b, cmd.variantKey, cmd.startsAt, cmd.promoCode)
        val quote = priced.quote
        return uow.run(b.location.id) {
            availability.ensureBookable(tenant, b.location, cmd.startsAt, b.durationMinutes, now)
            val bay = availability.assignBay(tenant, b.location, cmd.startsAt, endsAt, now)
            // A friend's code becomes the customer's own welcome voucher, so the referral is recorded once and the voucher frees up again if this booking lapses.
            var code = priced.voucher?.code
            var discounts = priced.discounts
            if (priced.voucher?.kind == "REFERRAL") {
                val voucher = referrals.redeem(tenant, customer, priced.voucher.code)
                code = voucher.code
                discounts = discounts.map { if (it.kind == "REFERRAL") it.copy(ref = voucher.code) else it }
            }
            val order = Order(
                id = ids.newId(), tenantId = tenant.id, code = ids.newCode(), customerId = customer.id,
                customerName = customer.name, customerPhone = customer.phone,
                providerId = b.provider.id, locationId = b.location.id, locationName = b.location.name,
                assetLabel = asset?.label, variantKey = cmd.variantKey, status = tenant.config.flow.initial,
                source = OrderSource.APP, startsAt = cmd.startsAt, endsAt = endsAt, lines = b.lines,
                subtotalMinor = quote.subtotalMinor, taxMinor = quote.taxMinor, feeMinor = quote.feeMinor, totalMinor = quote.totalMinor,
                commissionBps = quote.commissionBps, commissionMinor = quote.commissionMinor,
                holdExpiresAt = now.plus(Duration.ofMinutes(tenant.config.availability.holdMinutes.toLong())),
                createdAt = now, updatedAt = now, discountMinor = quote.discountMinor, promoCode = code, bay = bay,
                discounts = discounts, vendorDiscountMinor = quote.vendorDiscountMinor, promotionId = priced.promotion?.id,
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
        val step = b.location.slotMinutes.toLong()
        // "Now" means the slot we are in; the lead-time rule is for online customers and does not apply to someone standing here.
        val startsAt = cmd.startsAt ?: Instant.ofEpochSecond(now.epochSecond / (step * 60) * (step * 60))
        val slots = availability.slotsNeeded(b.location, b.durationMinutes)
        val endsAt = startsAt.plus(Duration.ofMinutes((slots * b.location.slotMinutes).toLong()))
        val subtotal = b.lines.sumOf { it.priceMinor }
        val created = uow.run(b.location.id) {
            availability.ensureBookable(tenant, b.location, startsAt, b.durationMinutes, startsAt.minus(Duration.ofMinutes(tenant.config.availability.leadMinutes.toLong() + 1)))
            val bay = availability.assignBay(tenant, b.location, startsAt, endsAt, now, prefer = cmd.bay) ?: throw conflict("slot_unavailable", "No bay is free then")
            val order = Order(
                id = ids.newId(), tenantId = tenant.id, code = ids.newCode(), customerId = null,
                customerName = cmd.customerName, customerPhone = cmd.customerPhone,
                providerId = b.provider.id, locationId = b.location.id, locationName = b.location.name,
                assetLabel = cmd.plate?.takeIf { it.isNotBlank() }, variantKey = cmd.variantKey, status = tenant.config.flow.accepted,
                source = OrderSource.WALK_IN, startsAt = startsAt, endsAt = endsAt, lines = b.lines,
                subtotalMinor = subtotal, taxMinor = 0, feeMinor = 0, totalMinor = subtotal,
                commissionBps = 0, commissionMinor = 0, holdExpiresAt = null, createdAt = now, updatedAt = now, bay = bay,
            )
            r.orders.insert(order)
            r.orders.addEvent(OrderEvent(order.id, null, order.status, "WALK_IN", actor.userId, null, now))
            auditor.log(actor, "ORDER_WALK_IN", "order", order.id)
            order
        }
        return if (cmd.checkIn && tenant.config.flow.transition(created.status, "CHECK_IN") != null) uow.run { apply(tenant, created, "CHECK_IN", actor, null) } else created
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
            // A time-limited price only holds inside its window, so a happy-hour booking can move only to another happy-hour slot.
            order.promotionId?.let { pid ->
                val promo = r.promotions.byId(pid)
                val local = LocalDateTime.ofInstant(newStart, ZoneId.of(location.timezone))
                if (promo == null || !PromotionEngine.appliesAt(promo, location.id, local, ignoreActive = true)) {
                    throw conflict("offer_window", "This price was for ${promo?.name ?: "an offer"}. Pick another time it covers, or cancel and book again")
                }
            }
            val duration = bayMinutes(order.lines)
            availability.ensureBookable(tenant, location, newStart, duration, now, excludeOrderId = order.id)
            val slots = availability.slotsNeeded(location, duration)
            val newEnd = newStart.plus(Duration.ofMinutes((slots * location.slotMinutes).toLong()))
            val bay = availability.assignBay(tenant, location, newStart, newEnd, now, excludeOrderId = order.id)
            val moved = order.copy(startsAt = newStart, endsAt = newEnd, updatedAt = now, reschedules = order.reschedules + 1, reminded = false, bay = bay)
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
                ledger.settled(order, tenant.config.money.gatewayFeeBps)
                updated = updated.copy(settled = true)
                if (def.effect == Effect.SETTLE) referrals.onCompleted(tenant, updated)
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
            "REJECT", "AUTO_REJECT" -> Message("Your $what was declined", "{note} You are being refunded in full.")
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

    /** Declines and refunds paid bookings the vendor did not accept in time. Run on a timer. */
    fun expireUnaccepted(): Int {
        var count = 0
        val now = clock.now()
        for (tenant in r.tenants.all()) {
            val flow = tenant.config.flow
            if (flow.transition(flow.paid, "AUTO_REJECT") == null) continue
            val cutoff = now.minus(Duration.ofMinutes(tenant.config.availability.acceptMinutes.toLong()))
            for (order in r.orders.staleIn(flow.paid, cutoff).filter { it.tenantId == tenant.id }) {
                runCatching { uow.run { apply(tenant, order, "AUTO_REJECT", Actor.system(tenant.id), "The vendor did not respond in time.") } }.onSuccess { count++ }
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
