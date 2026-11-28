package com.odyssey.core.service

import com.odyssey.core.config.RewardTerms
import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The pure rules for automatic promotions: when one applies, how much it takes off each line, and how to say it in words. */
object PromotionEngine {

    /** True when [p] covers an outlet at local time [at]. Pass [ignoreActive] to check only the schedule (a paused offer still honours bookings made under it). */
    fun appliesAt(p: Promotion, locationId: Id, at: LocalDateTime, ignoreActive: Boolean = false): Boolean {
        if (!ignoreActive && !p.active) return false
        if (p.locationIds.isNotEmpty() && locationId !in p.locationIds) return false
        val day = at.toLocalDate()
        if (p.startDate != null && day.isBefore(p.startDate)) return false
        if (p.endDate != null && day.isAfter(p.endDate)) return false
        if (p.days.isNotEmpty() && at.dayOfWeek !in p.days) return false
        val t = at.toLocalTime()
        if (p.startTime != null && t.isBefore(p.startTime)) return false
        if (p.endTime != null && !t.isBefore(p.endTime)) return false
        return true
    }

    fun eligible(p: Promotion, line: OrderLine, variantKey: String): Boolean =
        (p.services.isEmpty() || p.services.any { it.trim().equals(line.name.trim(), ignoreCase = true) }) &&
            (p.variantKeys.isEmpty() || variantKey in p.variantKeys)

    /** The discount on each line, never more than the line's price, capped in total at the promotion's maximum. */
    fun lineDiscounts(p: Promotion, lines: List<OrderLine>, variantKey: String): List<Long> {
        val ok = lines.map { eligible(p, it, variantKey) }
        val raw = when (p.kind) {
            PromoKind.PERCENT -> lines.mapIndexed { i, l -> if (ok[i]) bps(l.priceMinor, p.value.toInt().coerceIn(0, 10_000)) else 0L }
            PromoKind.PRICE -> lines.mapIndexed { i, l -> if (ok[i]) maxOf(0L, l.priceMinor - p.value) else 0L }
            PromoKind.FLAT -> {
                val weights = lines.mapIndexed { i, l -> if (ok[i]) l.priceMinor else 0L }
                allocate(minOf(p.value, weights.sum()), weights)
            }
        }
        val cap = p.maxDiscountMinor
        return if (cap != null && raw.sum() > cap) allocate(cap, raw) else raw
    }

    /** Splits [amount] across lines in proportion to [weights]; rounding leftovers go to the heaviest line. Never gives a line more than its weight. */
    fun allocate(amount: Long, weights: List<Long>): List<Long> {
        val total = weights.sum()
        if (amount <= 0 || total <= 0) return weights.map { 0L }
        val share = weights.map { it * minOf(amount, total) / total }.toMutableList()
        var left = minOf(amount, total) - share.sum()
        for (i in weights.indices.sortedByDescending { weights[it] }) {
            if (left <= 0) break
            val room = weights[i] - share[i]
            val add = minOf(room, left)
            share[i] += add; left -= add
        }
        return share
    }

    data class Best(val promotion: Promotion, val perLine: List<Long>) { val totalMinor get() = perLine.sum() }

    /** The single promotion that saves the customer the most at that time. Automatic promotions do not stack with each other. */
    fun best(promotions: List<Promotion>, locationId: Id, lines: List<OrderLine>, variantKey: String, at: LocalDateTime): Best? =
        promotions.asSequence()
            .filter { appliesAt(it, locationId, at) }
            .map { Best(it, lineDiscounts(it, lines, variantKey)) }
            .filter { it.totalMinor > 0 }
            .maxWithOrNull(compareBy<Best> { it.totalMinor }.thenBy { if (it.promotion.funder == Funder.VENDOR) 1 else 0 })

    // ---- words ---------------------------------------------------------------------------------------------

    fun money(minor: Long, currency: String = "INR"): String {
        val symbol = if (currency == "INR") "₹" else "$currency "
        val whole = minor / 100
        val paise = minor % 100
        return symbol + (if (paise == 0L) "%,d".format(Locale.ENGLISH, whole) else "%,.2f".format(Locale.ENGLISH, minor / 100.0))
    }

    fun percent(bpsValue: Long): String {
        val whole = bpsValue / 100
        val rest = bpsValue % 100
        return if (rest == 0L) "$whole%" else "%.2f".format(Locale.ENGLISH, bpsValue / 100.0).trimEnd('0').trimEnd('.') + "%"
    }

    /** "20% off", "₹50 off", "₹299 special price". */
    fun amountText(kind: PromoKind, value: Long, currency: String = "INR"): String = when (kind) {
        PromoKind.PERCENT -> "${percent(value)} off"
        PromoKind.FLAT -> "${money(value, currency)} off"
        PromoKind.PRICE -> "${money(value, currency)} special price"
    }

    private val shortDay = mapOf(
        DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun",
    )

    /** "every day", "every weekday", "every weekend", "every Mon–Thu", "every Mon, Wed and Fri". */
    fun daysText(days: List<DayOfWeek>): String {
        val set = days.toSortedSet()
        if (set.isEmpty() || set.size == 7) return "every day"
        val weekdays = (1..5).map { DayOfWeek.of(it) }.toSet()
        if (set == weekdays) return "every weekday"
        if (set == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) return "every weekend"
        val values = set.map { it.value }
        if (values.size >= 3 && values.last() - values.first() == values.size - 1) return "every ${shortDay[set.first()]}–${shortDay[set.last()]}"
        return "every " + list(set.map { shortDay[it]!! })
    }

    private fun clock(t: LocalTime, withSuffix: Boolean): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        val m = if (t.minute == 0) "" else ":%02d".format(t.minute)
        return "$h$m" + if (withSuffix) (if (t.hour < 12) " am" else " pm") else ""
    }

    /** "2–4 pm", "11 am–1 pm", "2:30–4 pm". */
    fun timeText(start: LocalTime, end: LocalTime): String {
        val sameHalf = (start.hour < 12) == (end.hour < 12 || end == LocalTime.MIDNIGHT)
        return if (sameHalf) "${clock(start, false)}–${clock(end, true)}" else "${clock(start, true)}–${clock(end, true)}"
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun datesText(start: LocalDate?, end: LocalDate?): String = when {
        start != null && end == start -> "on ${dayMonth.format(start)}"
        start != null && end != null -> "from ${dayMonth.format(start)} to ${dayMonth.format(end)}"
        start != null -> "from ${dayMonth.format(start)}"
        end != null -> "until ${dayMonth.format(end)}"
        else -> ""
    }

    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** The plain sentence vendors and admins see, e.g. "20% off every weekday 2–4 pm on all services". */
    fun summary(p: Promotion, config: TenantConfig? = null): String {
        val currency = config?.money?.currency ?: "INR"
        val parts = mutableListOf(amountText(p.kind, p.value, currency))
        if (p.days.isNotEmpty() || p.type == PromotionType.HAPPY_HOUR) parts += daysText(p.days)
        if (p.startTime != null && p.endTime != null) parts += timeText(p.startTime, p.endTime)
        datesText(p.startDate, p.endDate).takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += if (p.services.isEmpty()) "on all services" else "on " + list(p.services)
        if (p.variantKeys.isNotEmpty()) {
            val labels = p.variantKeys.map { k -> config?.variants?.options?.firstOrNull { it.key == k }?.label ?: k }
            parts += "for " + list(labels)
        }
        var text = parts.joinToString(" ")
        if (p.maxDiscountMinor != null && p.kind == PromoKind.PERCENT) text += " (up to ${money(p.maxDiscountMinor, currency)})"
        return text
    }

    /** A short badge for lists and slot pickers: "20% off · 2–4 pm", "₹50 off today". */
    fun badge(p: Promotion, config: TenantConfig? = null): String {
        val amount = amountText(p.kind, p.value, config?.money?.currency ?: "INR")
        val time = if (p.startTime != null && p.endTime != null) timeText(p.startTime, p.endTime) else null
        return listOfNotNull(amount, time).joinToString(" · ")
    }
}

/** What an automatic promotion and a code together take off a basket, and who pays for each part. */
data class PricedBasket(
    val quote: Quote,
    val discounts: List<DiscountLine>,
    val promotion: Promotion?,
    val voucher: PromoService.Applied?,
)

/** Vendor and platform promotions: validation, storage and usage. */
class PromotionService(private val r: Repos, private val auditor: Auditor, private val ids: Ids, private val clock: Clock) {

    data class Draft(
        val type: PromotionType, val name: String, val kind: PromoKind, val value: Long, val maxDiscountMinor: Long? = null,
        val locationIds: List<Id> = emptyList(), val services: List<String> = emptyList(), val variantKeys: List<String> = emptyList(),
        val days: List<DayOfWeek> = emptyList(), val startTime: LocalTime? = null, val endTime: LocalTime? = null,
        val startDate: LocalDate? = null, val endDate: LocalDate? = null, val active: Boolean = true,
    )

    data class Usage(val bookings: Int, val discountMinor: Long)

    private fun validate(tenant: Tenant, providerId: Id?, d: Draft): Draft {
        val name = d.name.trim()
        if (name.isEmpty()) throw invalid("Give the offer a name")
        if (name.length > 80) throw invalid("Keep the name under 80 characters")
        when (d.kind) {
            PromoKind.PERCENT -> if (d.value !in 1..10_000) throw invalid("The percentage must be between 1% and 100%")
            PromoKind.FLAT -> if (d.value <= 0) throw invalid("Enter the amount off")
            PromoKind.PRICE -> if (d.value <= 0) throw invalid("Enter the special price")
        }
        if (d.maxDiscountMinor != null && d.maxDiscountMinor <= 0) throw invalid("The maximum discount must be more than zero")
        val endDate = d.endDate ?: if (d.type == PromotionType.DATE_DISCOUNT) d.startDate else null
        when (d.type) {
            PromotionType.HAPPY_HOUR -> {
                if (d.startTime == null || d.endTime == null) throw invalid("Choose the start and end time")
                if (!d.startTime.isBefore(d.endTime)) throw invalid("The end time must be after the start time")
            }
            PromotionType.DATE_DISCOUNT -> if (d.startDate == null) throw invalid("Choose the date")
        }
        if ((d.startTime == null) != (d.endTime == null)) throw invalid("Choose both a start and an end time")
        if (d.startTime != null && d.endTime != null && !d.startTime.isBefore(d.endTime)) throw invalid("The end time must be after the start time")
        if (d.startDate != null && endDate != null && endDate.isBefore(d.startDate)) throw invalid("The end date must be on or after the start date")
        val validVariants = tenant.config.variants.options.map { it.key }.toSet()
        if (d.variantKeys.any { it !in validVariants }) throw invalid("Unknown ${tenant.config.variants.name.lowercase()}")
        if (providerId != null) {
            val own = r.locations.byProvider(providerId).map { it.id }.toSet()
            if (d.locationIds.any { it !in own }) throw invalid("Pick your own ${tenant.config.labels.location.lowercase()}s")
        } else if (d.locationIds.isNotEmpty()) {
            if (d.locationIds.any { r.locations.byId(it)?.tenantId != tenant.id }) throw invalid("Unknown ${tenant.config.labels.location.lowercase()}")
        }
        return d.copy(name = name, endDate = endDate, services = d.services.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), variantKeys = d.variantKeys.distinct(), days = d.days.distinct().sorted(), locationIds = d.locationIds.distinct())
    }

    private fun gate(actor: Actor, p: Promotion) {
        when {
            actor.role.isBackoffice -> if (actor.role != Role.ADMIN) throw forbidden()
            actor.role.isProvider -> if (p.providerId != actor.providerId) throw notFound("Offer")
            else -> throw forbidden()
        }
    }

    fun get(tenant: Tenant, id: Id): Promotion = r.promotions.byId(id)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Offer")

    /** [providerId] null makes a platform-wide promotion (admin only). */
    fun create(tenant: Tenant, actor: Actor, providerId: Id?, draft: Draft): Promotion {
        if (providerId == null && actor.role != Role.ADMIN) throw forbidden()
        if (providerId != null && (!actor.role.isProvider || actor.providerId != providerId)) throw forbidden()
        val d = validate(tenant, providerId, draft)
        if (d.endDate != null && d.endDate.isBefore(LocalDate.now(ZoneId.of("Asia/Kolkata")))) throw invalid("That date has already passed")
        val now = clock.now()
        val p = Promotion(
            ids.newId(), tenant.id, providerId, d.type, d.name, d.kind, d.value, d.maxDiscountMinor, d.locationIds, d.services, d.variantKeys,
            d.days, d.startTime, d.endTime, d.startDate, d.endDate, d.active, actor.name.ifBlank { actor.role.name }, now, now,
        )
        r.promotions.save(p)
        auditor.log(actor, "PROMOTION_CREATED", "promotion", p.id, PromotionEngine.summary(p, tenant.config))
        return p
    }

    fun update(tenant: Tenant, actor: Actor, id: Id, draft: Draft): Promotion {
        val existing = get(tenant, id)
        gate(actor, existing)
        val d = validate(tenant, existing.providerId, draft)
        val p = existing.copy(
            type = d.type, name = d.name, kind = d.kind, value = d.value, maxDiscountMinor = d.maxDiscountMinor, locationIds = d.locationIds, services = d.services,
            variantKeys = d.variantKeys, days = d.days, startTime = d.startTime, endTime = d.endTime, startDate = d.startDate, endDate = d.endDate, active = d.active,
            updatedAt = clock.now(),
        )
        r.promotions.save(p)
        auditor.log(actor, "PROMOTION_UPDATED", "promotion", p.id, PromotionEngine.summary(p, tenant.config))
        return p
    }

    fun setActive(tenant: Tenant, actor: Actor, id: Id, active: Boolean): Promotion {
        val existing = get(tenant, id)
        gate(actor, existing)
        val p = existing.copy(active = active, updatedAt = clock.now())
        r.promotions.save(p)
        auditor.log(actor, if (active) "PROMOTION_RESUMED" else "PROMOTION_PAUSED", "promotion", p.id)
        return p
    }

    fun delete(tenant: Tenant, actor: Actor, id: Id) {
        val existing = get(tenant, id)
        gate(actor, existing)
        r.promotions.delete(existing.id)
        auditor.log(actor, "PROMOTION_DELETED", "promotion", existing.id, existing.name)
    }

    /** Bookings and money given away, per promotion id. */
    fun usage(tenant: Tenant): Map<Id, Usage> =
        r.orders.discounted(tenant.id).filter { it.promotionId != null }.groupBy { it.promotionId!! }
            .mapValues { (id, os) -> Usage(os.size, os.sumOf { o -> o.discounts.filter { it.kind == "PROMOTION" && it.ref == id }.sumOf { it.amountMinor } }) }

    /** The best deal to show on a location card: one running now, or else the next one in the coming week. */
    fun dealFor(tenant: Tenant, location: Location, now: Instant): Pair<Promotion, Boolean>? {
        val zone = ZoneId.of(location.timezone)
        val local = LocalDateTime.ofInstant(now, zone)
        val offers = r.promotions.activeFor(tenant.id, location.providerId).filter { it.locationIds.isEmpty() || location.id in it.locationIds }
        if (offers.isEmpty()) return null
        offers.firstOrNull { PromotionEngine.appliesAt(it, location.id, local) }?.let { return it to true }
        val today = local.toLocalDate()
        for (d in 0L..6L) {
            val day = today.plusDays(d)
            val hit = offers.firstOrNull { p ->
                val probe = day.atTime(p.startTime ?: location.openTime)
                (d > 0 || probe.isAfter(local)) && PromotionEngine.appliesAt(p, location.id, probe)
            }
            if (hit != null) return hit to false
        }
        return null
    }
}

/** Vouchers and coupon codes, including referral codes typed at checkout. */
class PromoService(private val r: Repos, private val referrals: ReferralService, private val clock: Clock) {
    /** [kind] is VOUCHER or REFERRAL. A REFERRAL is a friend's code that becomes a welcome voucher when the booking is placed. */
    data class Applied(val code: String, val discountMinor: Long, val description: String, val funder: Funder = Funder.PLATFORM, val kind: String = "VOUCHER")

    private fun amount(kind: PromoKind, value: Long, max: Long?, base: Long): Long {
        val raw = when (kind) { PromoKind.FLAT -> value; PromoKind.PERCENT -> bps(base, value.toInt()); PromoKind.PRICE -> 0 }
        return minOf(raw, max ?: Long.MAX_VALUE, base).coerceAtLeast(0)
    }

    /** Checks a code for a basket worth [subtotalMinor] (after any automatic promotion) at [providerId], and works out the discount. */
    fun apply(tenant: Tenant, userId: Id?, code: String, subtotalMinor: Long, providerId: Id? = null): Applied {
        val now = clock.now()
        val normalized = code.trim().uppercase()
        val promo = r.promos.byCode(tenant.id, normalized)
        if (promo == null) {
            val terms = referrals.welcomeTermsFor(tenant, userId, normalized) ?: throw invalid("That code is not valid")
            if (subtotalMinor < terms.minSubtotalMinor) throw invalid("Spend at least ${PromotionEngine.money(terms.minSubtotalMinor)} to use a friend's code")
            val kind = runCatching { PromoKind.valueOf(terms.kind) }.getOrDefault(PromoKind.FLAT)
            return Applied(normalized, amount(kind, terms.value, terms.maxDiscountMinor, subtotalMinor), "Welcome offer from a friend", Funder.PLATFORM, "REFERRAL")
        }
        if (!promo.active || promo.kind == PromoKind.PRICE) throw invalid("That code is not valid")
        if (promo.ownerUserId != null && promo.ownerUserId != userId) throw invalid("That code is not valid")
        if (promo.startsAt != null && now < promo.startsAt) throw invalid("That code is not active yet")
        if (promo.expiresAt != null && now > promo.expiresAt) throw invalid("That code has expired")
        if (promo.providerId != null && promo.providerId != providerId) {
            val name = r.providers.byId(promo.providerId)?.name ?: "one ${tenant.config.labels.provider.lowercase()}"
            throw invalid("${promo.code} works only at $name")
        }
        if (subtotalMinor < promo.minSubtotalMinor) throw invalid("Spend at least ${PromotionEngine.money(promo.minSubtotalMinor, tenant.config.money.currency)} to use ${promo.code}")
        if (promo.usageLimit != null && r.orders.countPromoUses(tenant.id, promo.code, null) >= promo.usageLimit) throw invalid("That code has been fully used")
        if (userId != null && r.orders.countPromoUses(tenant.id, promo.code, userId) >= promo.perUserLimit) throw invalid("You have already used ${promo.code}")
        val description = promo.description.ifBlank { PromotionEngine.amountText(promo.kind, promo.value, tenant.config.money.currency) }
        return Applied(promo.code, amount(promo.kind, promo.value, promo.maxDiscountMinor, subtotalMinor), description, promo.fundedBy, "VOUCHER")
    }

    /** Personal vouchers the customer can still use. */
    fun walletFor(tenant: Tenant, userId: Id): List<PromoCode> {
        val now = clock.now()
        return r.promos.byOwner(userId).filter { p ->
            p.tenantId == tenant.id && p.active && (p.expiresAt == null || p.expiresAt > now) && r.orders.countPromoUses(tenant.id, p.code, userId) < p.perUserLimit
        }
    }
}

/** Refer a friend: each customer has a code; a new customer who uses it gets a welcome voucher, and the friend gets one after the first completed booking. */
class ReferralService(private val r: Repos, private val notifier: Notifier, private val auditor: Auditor, private val ids: Ids, private val clock: Clock) {
    private val dead = setOf("EXPIRED", "CANCELLED", "REJECTED")

    fun codeFor(tenant: Tenant, user: User): ReferralCode {
        r.referrals.codeFor(user.id)?.let { return it }
        val stem = user.name.uppercase().filter { it in 'A'..'Z' }.take(5).ifEmpty { "RINSE" }
        repeat(20) {
            val code = stem + ids.newCode().filter { it.isLetterOrDigit() }.take(3).uppercase()
            if (r.referrals.byCode(tenant.id, code) == null && r.promos.byCode(tenant.id, code) == null) {
                val rc = ReferralCode(tenant.id, user.id, code, clock.now())
                r.referrals.saveCode(rc)
                return rc
            }
        }
        throw conflict("busy", "Could not make a code right now. Try again")
    }

    /** Has this customer already booked? A friend's code is only for someone new. */
    private fun hasBooked(tenant: Tenant, userId: Id) =
        r.orders.byCustomer(userId).any { it.status !in dead && it.status != tenant.config.flow.initial }

    /** The welcome voucher terms when [code] is a friend's referral code this customer may use, null when it is not a referral code. Throws when it is one but cannot be used. */
    fun welcomeTermsFor(tenant: Tenant, userId: Id?, code: String): RewardTerms? {
        val rc = r.referrals.byCode(tenant.id, code) ?: return null
        val cfg = tenant.config.referral
        if (!cfg.enabled) throw invalid("Referral codes are paused right now")
        if (userId == null) throw invalid("Sign in to use a friend's code")
        if (rc.userId == userId) throw invalid("That is your own code. Share it with a friend instead")
        if (r.referrals.byReferee(userId) != null) throw invalid("You have already used a friend's code")
        if (hasBooked(tenant, userId)) throw invalid("Friend codes are for your first booking")
        return cfg.welcome
    }

    private fun issue(tenant: Tenant, owner: Id, prefix: String, terms: RewardTerms, source: String, description: String): PromoCode {
        val now = clock.now()
        var code: String
        do { code = prefix + ids.newCode().uppercase() } while (r.promos.byCode(tenant.id, code) != null)
        val kind = runCatching { PromoKind.valueOf(terms.kind) }.getOrDefault(PromoKind.FLAT).takeIf { it != PromoKind.PRICE } ?: PromoKind.FLAT
        val promo = PromoCode(
            tenant.id, code, kind, terms.value, terms.maxDiscountMinor, terms.minSubtotalMinor, now, now.plus(Duration.ofDays(terms.validDays.toLong().coerceAtLeast(1))),
            1, 1, true, description, null, Funder.PLATFORM, owner, source, now,
        )
        r.promos.save(promo)
        return promo
    }

    /** Uses a friend's code: records the referral and gives the new customer a welcome voucher. */
    fun redeem(tenant: Tenant, customer: User, code: String): PromoCode {
        val normalized = code.trim().uppercase()
        val terms = welcomeTermsFor(tenant, customer.id, normalized) ?: throw invalid("That is not a referral code")
        val rc = r.referrals.byCode(tenant.id, normalized)!!
        val voucher = issue(tenant, customer.id, "W", terms, "REFERRAL_WELCOME", "Welcome offer: " + PromotionEngine.amountText(PromoKind.valueOf(terms.kind), terms.value, tenant.config.money.currency))
        r.referrals.insert(Referral(ids.newId(), tenant.id, rc.userId, customer.id, normalized, ReferralStatus.PENDING, voucher.code, null, clock.now()))
        notifier.send(tenant.id, customer.id, "Welcome voucher added", "${voucher.description}. Use ${voucher.code} on your first booking.", null)
        return voucher
    }

    /** Called when a booking completes. Rewards the friend who referred this customer, once. */
    fun onCompleted(tenant: Tenant, order: Order) {
        val customerId = order.customerId ?: return
        val referral = r.referrals.byReferee(customerId)?.takeIf { it.status == ReferralStatus.PENDING && it.tenantId == tenant.id } ?: return
        val terms = tenant.config.referral.reward
        val friend = r.users.byId(customerId)?.name ?: "Your friend"
        val voucher = issue(tenant, referral.referrerId, "R", terms, "REFERRAL_REWARD", "Thanks for referring $friend: " + PromotionEngine.amountText(PromoKind.valueOf(terms.kind), terms.value, tenant.config.money.currency))
        r.referrals.update(referral.copy(status = ReferralStatus.REWARDED, rewardVoucher = voucher.code, rewardedAt = clock.now()))
        notifier.send(tenant.id, referral.referrerId, "You earned a voucher", "$friend finished their first booking. Use ${voucher.code} on your next one.", null)
        auditor.log(Actor.system(tenant.id), "REFERRAL_REWARDED", "referral", referral.id, voucher.code)
    }

    data class Stats(val invited: Int, val rewarded: Int, val pending: Int)

    fun statsFor(userId: Id): Stats {
        val mine = r.referrals.byReferrer(userId)
        return Stats(mine.size, mine.count { it.status == ReferralStatus.REWARDED }, mine.count { it.status == ReferralStatus.PENDING })
    }
}
