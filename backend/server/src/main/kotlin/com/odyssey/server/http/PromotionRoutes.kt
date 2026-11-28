package com.odyssey.server.http

import com.odyssey.core.config.ReferralConfig
import com.odyssey.core.domain.*
import com.odyssey.core.service.*
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ---- DTOs ---------------------------------------------------------------------------------------------------

/** A happy hour or date discount. Days are MONDAY..SUNDAY; times are HH:mm; dates are yyyy-MM-dd. PERCENT value is basis points, FLAT and PRICE are minor units. */
@Serializable
data class PromotionRequest(
    val type: String, val name: String, val kind: String, val value: Long, val maxDiscountMinor: Long? = null,
    val locationIds: List<String> = emptyList(), val services: List<String> = emptyList(), val variantKeys: List<String> = emptyList(),
    val days: List<String> = emptyList(), val startTime: String? = null, val endTime: String? = null,
    val startDate: String? = null, val endDate: String? = null, val active: Boolean = true,
)

/** [status] is LIVE, SCHEDULED, PAUSED or ENDED. [editable] is false for platform offers shown to a vendor. */
@Serializable
data class PromotionDto(
    val id: String, val providerId: String? = null, val providerName: String? = null, val type: String, val name: String, val kind: String, val value: Long,
    val maxDiscountMinor: Long? = null, val locationIds: List<String>, val services: List<String>, val variantKeys: List<String>, val days: List<String>,
    val startTime: String? = null, val endTime: String? = null, val startDate: String? = null, val endDate: String? = null, val active: Boolean,
    val summary: String, val badge: String, val status: String, val fundedBy: String, val editable: Boolean, val bookings: Int = 0, val discountGivenMinor: Long = 0,
    val createdAt: String, val updatedAt: String,
)

@Serializable data class VoucherDto(val code: String, val description: String, val kind: String, val value: Long, val maxDiscountMinor: Long? = null, val minSubtotalMinor: Long = 0, val expiresAt: String? = null, val source: String)
@Serializable data class RedeemRequest(val code: String)

@Serializable
data class ReferralDto(
    val enabled: Boolean, val code: String, val shareText: String, val welcomeText: String, val rewardText: String,
    val invited: Int, val rewarded: Int, val pending: Int, val usedFriendCode: Boolean,
)

@Serializable data class ReferralRowDto(val id: String, val referrerName: String, val refereeName: String, val code: String, val status: String, val createdAt: String, val rewardedAt: String? = null)

@Serializable
data class ReferralOverviewDto(
    val settings: ReferralConfig, val referrals: Int, val rewarded: Int, val pending: Int,
    val welcomeVouchersUsed: Int, val rewardVouchersUsed: Int, val discountGivenMinor: Long, val recent: List<ReferralRowDto>,
)

/** Discounts on live bookings, split by who paid for them. */
@Serializable
data class DiscountSummaryDto(
    val bookings: Int, val totalMinor: Long, val vendorFundedMinor: Long, val platformFundedMinor: Long,
    val promotionsMinor: Long, val vouchersMinor: Long, val referralsMinor: Long,
)

// ---- mapping ------------------------------------------------------------------------------------------------

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

fun Promotion.status(today: LocalDate = LocalDate.now(IST)): String {
    val end = endDate
    val start = startDate
    return when {
        end != null && end.isBefore(today) -> "ENDED"
        !active -> "PAUSED"
        start != null && start.isAfter(today) -> "SCHEDULED"
        else -> "LIVE"
    }
}

fun Promotion.dto(tenant: Tenant, providerName: String? = null, editable: Boolean = true, usage: PromotionService.Usage? = null) = PromotionDto(
    id, providerId, providerName, type.name, name, kind.name, value, maxDiscountMinor, locationIds, services, variantKeys, days.map { it.name },
    startTime?.toString(), endTime?.toString(), startDate?.toString(), endDate?.toString(), active,
    PromotionEngine.summary(this, tenant.config), PromotionEngine.badge(this, tenant.config), status(), funder.name, editable,
    usage?.bookings ?: 0, usage?.discountMinor ?: 0, createdAt.iso(), updatedAt.iso(),
)

private fun PromotionRequest.toDraft() = PromotionService.Draft(
    type = runCatching { PromotionType.valueOf(type) }.getOrElse { throw invalid("Type must be HAPPY_HOUR or DATE_DISCOUNT") },
    name = name,
    kind = runCatching { PromoKind.valueOf(kind) }.getOrElse { throw invalid("Kind must be PERCENT, FLAT or PRICE") },
    value = value, maxDiscountMinor = maxDiscountMinor, locationIds = locationIds, services = services, variantKeys = variantKeys,
    days = days.map { d -> runCatching { DayOfWeek.valueOf(d.uppercase()) }.getOrElse { throw invalid("Unknown day $d") } },
    startTime = startTime?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrElse { throw invalid("Times look like 14:00") } },
    endTime = endTime?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrElse { throw invalid("Times look like 16:00") } },
    startDate = startDate?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw invalid("Dates look like 2026-12-25") } },
    endDate = endDate?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw invalid("Dates look like 2026-12-25") } },
    active = active,
)

private fun PromoCode.voucher() = VoucherDto(code, description, kind.name, value, maxDiscountMinor, minSubtotalMinor, expiresAt?.iso(), source)

private fun terms(t: com.odyssey.core.config.RewardTerms, currency: String): String {
    val kind = runCatching { PromoKind.valueOf(t.kind) }.getOrDefault(PromoKind.FLAT)
    val base = PromotionEngine.amountText(kind, t.value, currency)
    val max = t.maxDiscountMinor
    val cap = if (kind == PromoKind.PERCENT && max != null) " up to ${PromotionEngine.money(max, currency)}" else ""
    val min = if (t.minSubtotalMinor > 0) " on bookings over ${PromotionEngine.money(t.minSubtotalMinor, currency)}" else ""
    return base + cap + min
}

// ---- routes -------------------------------------------------------------------------------------------------

fun Route.promotionRoutes(ctx: AppContext) {
    // ---- customers: vouchers and refer a friend ----

    get("/me/vouchers") {
        val actor = call.actor(); actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        call.respond(ctx.promos.walletFor(tenant, actor.userId!!).map { it.voucher() })
    }

    get("/me/referral") {
        val actor = call.actor(); actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val user = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val code = ctx.referrals.codeFor(tenant, user)
        val stats = ctx.referrals.statsFor(user.id)
        val cfg = tenant.config.referral
        val currency = tenant.config.money.currency
        val welcome = terms(cfg.welcome, currency)
        call.respond(ReferralDto(
            cfg.enabled, code.code,
            "Get $welcome on your first booking with ${tenant.name}. Sign up and use my code ${code.code}.",
            welcome, terms(cfg.reward, currency), stats.invited, stats.rewarded, stats.pending, ctx.repos.referrals.byReferee(user.id) != null,
        ))
    }

    post("/me/referral/redeem") {
        val actor = call.actor(); actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val user = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val code = call.receive<RedeemRequest>().code
        val voucher = ctx.uow.run { ctx.referrals.redeem(tenant, user, code) }
        call.respond(HttpStatusCode.Created, voucher.voucher())
    }

    // ---- vendors: their own happy hours and date discounts ----

    route("/provider/promotions") {
        /** The vendor's own offers, then platform offers that also apply at its outlets (read-only). */
        get {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val usage = ctx.promotions.usage(tenant)
            val own = ctx.repos.promotions.byProvider(actor.providerId!!).map { it.dto(tenant, null, actor.role != Role.PROVIDER_STAFF, usage[it.id]) }
            val platform = ctx.repos.promotions.list(tenant.id).filter { it.providerId == null && it.status() != "ENDED" }.map { it.dto(tenant, tenant.name, false) }
            call.respond(own + platform)
        }

        /** Changing prices is owner-only and PIN gated, like editing the menu. */
        post {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            val p = ctx.promotions.create(tenant, actor, actor.providerId, call.receive<PromotionRequest>().toDraft())
            call.respond(HttpStatusCode.Created, p.dto(tenant))
        }

        put("/{id}") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            call.respond(ctx.promotions.update(tenant, actor, call.pathId(), call.receive<PromotionRequest>().toDraft()).dto(tenant))
        }

        /** Pause or resume. Managers may do this without the PIN: it only switches an offer the owner already set up. */
        put("/{id}/active") {
            val actor = call.actor(); actor.require(Role.PROVIDER_OWNER, Role.PROVIDER_MANAGER)
            val tenant = call.tenantFor(ctx, actor)
            call.respond(ctx.promotions.setActive(tenant, actor, call.pathId(), call.receive<ActiveRequest>().active).dto(tenant))
        }

        delete("/{id}") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            ctx.promotions.delete(tenant, actor, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ---- back office: platform offers, vouchers, referrals, usage ----

    route("/admin") {
        get("/promotions") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val usage = ctx.promotions.usage(tenant)
            val names = ctx.repos.providers.list(tenant.id).associate { it.id to it.name }
            call.respond(ctx.repos.promotions.list(tenant.id).map { it.dto(tenant, it.providerId?.let(names::get), it.providerId == null && actor.role == Role.ADMIN, usage[it.id]) })
        }

        post("/promotions") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            call.respond(HttpStatusCode.Created, ctx.promotions.create(tenant, actor, null, call.receive<PromotionRequest>().toDraft()).dto(tenant))
        }

        put("/promotions/{id}") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            call.respond(ctx.promotions.update(tenant, actor, call.pathId(), call.receive<PromotionRequest>().toDraft()).dto(tenant))
        }

        /** Admins can pause any offer, including a vendor's (for example one that breaks the rules). */
        put("/promotions/{id}/active") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            call.respond(ctx.promotions.setActive(tenant, actor, call.pathId(), call.receive<ActiveRequest>().active).dto(tenant))
        }

        delete("/promotions/{id}") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            val p = ctx.promotions.get(tenant, call.pathId())
            if (p.providerId != null) throw forbidden("Pause a vendor's offer instead of deleting it")
            ctx.promotions.delete(tenant, actor, p.id)
            call.respond(HttpStatusCode.NoContent)
        }

        /** Vouchers and coupons, without the personal referral vouchers. */
        get("/promos") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val names = ctx.repos.providers.list(tenant.id).associate { it.id to it.name }
            val used = ctx.repos.orders.discounted(tenant.id).filter { it.promoCode != null }.groupBy { it.promoCode!! }
            call.respond(ctx.repos.promos.list(tenant.id).filter { it.ownerUserId == null }.sortedByDescending { it.createdAt }.map { p ->
                val orders = used[p.code].orEmpty()
                p.dto(p.providerId?.let(names::get), orders.size, orders.sumOf { o -> o.discounts.filter { it.ref == p.code }.sumOf { it.amountMinor } })
            })
        }

        post("/promos") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            val p = call.receive<PromoDto>()
            val kind = runCatching { PromoKind.valueOf(p.kind) }.getOrNull()?.takeIf { it != PromoKind.PRICE } ?: throw invalid("Kind must be FLAT or PERCENT")
            val code = p.code.trim().uppercase()
            if (!code.matches(Regex("^[A-Z0-9-]{3,30}$"))) throw invalid("Codes are 3 to 30 letters, digits or dashes")
            if (p.value <= 0) throw invalid("Give the discount")
            if (kind == PromoKind.PERCENT && p.value > 10_000) throw invalid("A percentage cannot be over 100%")
            if (p.usageLimit != null && p.usageLimit < 1) throw invalid("The total limit must be at least 1")
            if (p.perUserLimit < 1) throw invalid("Each customer must be able to use it at least once")
            val funder = runCatching { Funder.valueOf(p.fundedBy) }.getOrElse { throw invalid("Funded by must be VENDOR or PLATFORM") }
            if (funder == Funder.VENDOR && p.providerId == null) throw invalid("Pick the vendor who pays for this code")
            if (p.providerId != null && ctx.repos.providers.byId(p.providerId)?.tenantId != tenant.id) throw invalid("Unknown vendor")
            val starts = p.startsAt?.takeIf { it.isNotBlank() }?.let(::parseInstant)
            val expires = p.expiresAt?.takeIf { it.isNotBlank() }?.let(::parseInstant)
            if (starts != null && expires != null && !expires.isAfter(starts)) throw invalid("The end date must be after the start date")
            if (ctx.repos.referrals.byCode(tenant.id, code) != null) throw invalid("A customer's referral code already uses $code")
            val existing = ctx.repos.promos.byCode(tenant.id, code)
            if (existing?.ownerUserId != null) throw invalid("That code is taken")
            val promo = PromoCode(
                tenant.id, code, kind, p.value, p.maxDiscountMinor?.takeIf { it > 0 }, p.minSubtotalMinor.coerceAtLeast(0), starts, expires, p.usageLimit, p.perUserLimit, p.active,
                p.description.trim().take(200), p.providerId, funder, null, "ADMIN", existing?.createdAt ?: ctx.clock.now(),
            )
            ctx.repos.promos.save(promo)
            ctx.auditor.log(actor, "PROMO_SAVED", "promo", promo.code, "${PromotionEngine.amountText(kind, p.value, tenant.config.money.currency)}, funded by ${funder.name.lowercase()}")
            call.respond(HttpStatusCode.Created, promo.dto(p.providerId?.let { ctx.repos.providers.byId(it)?.name }))
        }

        put("/promos/{code}/active") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val promo = ctx.repos.promos.byCode(actor.tenantId, call.pathId("code").uppercase()) ?: throw notFound("Promo")
            val active = call.receive<ActiveRequest>().active
            ctx.repos.promos.save(promo.copy(active = active))
            ctx.auditor.log(actor, if (active) "PROMO_RESUMED" else "PROMO_PAUSED", "promo", promo.code)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/referrals") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val all = ctx.repos.referrals.list(tenant.id)
            val orders = ctx.repos.orders.discounted(tenant.id)
            val welcome = all.mapNotNull { it.welcomeVoucher }.toSet()
            val reward = all.mapNotNull { it.rewardVoucher }.toSet()
            val referralOrders = orders.filter { it.promoCode in welcome || it.promoCode in reward }
            fun name(id: String) = ctx.repos.users.byId(id)?.name ?: "Customer"
            call.respond(ReferralOverviewDto(
                tenant.config.referral, all.size, all.count { it.status == ReferralStatus.REWARDED }, all.count { it.status == ReferralStatus.PENDING },
                referralOrders.count { it.promoCode in welcome }, referralOrders.count { it.promoCode in reward },
                referralOrders.sumOf { o -> o.discounts.filter { it.ref == o.promoCode }.sumOf { it.amountMinor } },
                all.take(50).map { ReferralRowDto(it.id, name(it.referrerId), name(it.refereeId), it.code, it.status.name, it.createdAt.iso(), it.rewardedAt?.iso()) },
            ))
        }

        put("/referrals/settings") {
            val actor = call.actor(); actor.require(Role.ADMIN)
            val tenant = call.tenantFor(ctx, actor)
            val s = call.receive<ReferralConfig>()
            listOf(s.welcome, s.reward).forEach { t ->
                val kind = runCatching { PromoKind.valueOf(t.kind) }.getOrNull()?.takeIf { it != PromoKind.PRICE } ?: throw invalid("Reward kind must be FLAT or PERCENT")
                if (t.value <= 0) throw invalid("Each reward needs a value")
                if (kind == PromoKind.PERCENT && t.value > 10_000) throw invalid("A percentage cannot be over 100%")
                if (t.validDays !in 1..365) throw invalid("Vouchers last between 1 and 365 days")
            }
            val updated = ctx.admin.updateConfig(tenant, actor, tenant.config.copy(referral = s))
            ctx.auditor.log(actor, "REFERRAL_SETTINGS", "tenant", tenant.id, if (s.enabled) "on" else "off")
            call.respond(updated.config.referral)
        }

        get("/discounts/summary") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val orders = ctx.repos.orders.discounted(tenant.id)
            val lines = orders.flatMap { it.discounts }
            call.respond(DiscountSummaryDto(
                orders.size, orders.sumOf { it.discountMinor }, orders.sumOf { it.vendorDiscountMinor }, orders.sumOf { it.platformDiscountMinor },
                lines.filter { it.kind == "PROMOTION" }.sumOf { it.amountMinor }, lines.filter { it.kind == "VOUCHER" }.sumOf { it.amountMinor }, lines.filter { it.kind == "REFERRAL" }.sumOf { it.amountMinor },
            ))
        }
    }
}
