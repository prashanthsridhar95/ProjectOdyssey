package com.odyssey.server.http

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.service.*
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

fun Route.providerRoutes(ctx: AppContext) {
    route("/provider") {
        get("/me") {
            val actor = call.actor(); actor.requireProvider()
            val provider = ctx.repos.providers.byId(actor.providerId!!) ?: throw notFound("Provider")
            val owner = ctx.repos.users.byId(provider.ownerUserId)
            call.respond(provider.dto(owner, ctx.repos.locations.byProvider(provider.id)))
        }

        put("/documents") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val req = call.receive<DocumentsRequest>()
            val p = ctx.providers.updateDocuments(tenant, actor, req.documents.map { ProviderDocument(it.type, it.reference) })
            call.respond(p.dto())
        }

        put("/bank") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val req = call.receive<BankRequest>()
            val masked = "•••• " + req.accountNumber.takeLast(4)
            call.respond(ctx.providers.updateBank(tenant, actor, call.ownerPin(), BankDetails(req.accountName, masked, req.ifsc)).dto())
        }

        put("/owner-pin") {
            val actor = call.actor(); actor.requireProvider()
            val req = call.receive<PinRequest>()
            ctx.providers.setOwnerPin(actor, req.newPin, req.currentPin)
            call.respond(HttpStatusCode.NoContent)
        }

        /** Confirms the Owner PIN without changing anything; the apps use it to unlock gated screens. */
        post("/verify-pin") {
            val actor = call.actor(); actor.requireProvider()
            ctx.providers.verifyPin(actor, call.ownerPin())
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- locations, menu, slots ----
        post("/locations") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            call.respond(HttpStatusCode.Created, ctx.providers.addLocation(tenant, actor, call.ownerPin(), call.receive<LocationDraftDto>().toDraft()).dto())
        }

        put("/locations/{id}") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val req = call.receive<UpdateLocationRequest>()
            val updated = ctx.providers.updateLocation(tenant, actor, call.pathId()) { l ->
                l.copy(
                    name = req.name ?: l.name, address = req.address ?: l.address,
                    openTime = req.openTime?.let(LocalTime::parse) ?: l.openTime, closeTime = req.closeTime?.let(LocalTime::parse) ?: l.closeTime,
                    slotMinutes = req.slotMinutes ?: l.slotMinutes, capacity = req.capacity ?: l.capacity,
                    autoAccept = req.autoAccept ?: l.autoAccept, open = req.open ?: l.open, amenities = req.amenities ?: l.amenities, phone = req.phone ?: l.phone,
                )
            }
            call.respond(updated.dto())
        }

        get("/locations/{id}/items") {
            val actor = call.actor(); actor.requireProvider()
            val location = ctx.repos.locations.byId(call.pathId())?.takeIf { it.providerId == actor.providerId } ?: throw notFound("Location")
            call.respond(ctx.repos.catalog.byLocation(location.id, activeOnly = false).map { it.dto() })
        }

        post("/locations/{id}/items") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<ItemRequest>()
            val item = ctx.providers.saveItem(tenant, actor, call.ownerPin(), call.pathId(), null, r.name, r.description, r.category, r.durationMinutes, r.active, r.prices, r.baysUsed, r.outOfStock)
            call.respond(HttpStatusCode.Created, item.dto())
        }

        put("/locations/{id}/items/{itemId}") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<ItemRequest>()
            val item = ctx.providers.saveItem(tenant, actor, call.ownerPin(), call.pathId(), call.pathId("itemId"), r.name, r.description, r.category, r.durationMinutes, r.active, r.prices, r.baysUsed, r.outOfStock)
            call.respond(item.dto())
        }

        delete("/locations/{id}/items/{itemId}") {
            val actor = call.actor(); actor.requireProvider()
            ctx.providers.deleteItem(actor, call.ownerPin(), call.pathId(), call.pathId("itemId"))
            call.respond(HttpStatusCode.NoContent)
        }

        get("/locations/{id}/blocks") {
            val actor = call.actor(); actor.requireProvider()
            val location = ctx.repos.locations.byId(call.pathId())?.takeIf { it.providerId == actor.providerId } ?: throw notFound("Location")
            val zone = ZoneId.of(location.timezone)
            val date = parseDate(call.request.queryParameters["date"], zone)
            val from = date.atStartOfDay(zone).toInstant()
            call.respond(ctx.repos.blocks.overlapping(location.id, from, from.plusSeconds(86_400)).map { it.dto() })
        }

        post("/locations/{id}/blocks") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<BlockRequest>()
            val repeat = runCatching { Repeat.valueOf(r.repeat) }.getOrElse { throw invalid("Repeat must be NONE, DAILY or WEEKLY") }
            call.respond(HttpStatusCode.Created, ctx.providers.addBlock(tenant, actor, call.pathId(), parseInstant(r.startsAt), parseInstant(r.endsAt), r.reason, r.bays, repeat).dto())
        }

        delete("/locations/{id}/blocks/{blockId}") {
            val actor = call.actor(); actor.requireProvider()
            ctx.providers.removeBlock(actor, call.pathId(), call.pathId("blockId"))
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- bookings ----
        get("/orders") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val q = call.request.queryParameters
            val zone = q["locationId"]?.let { ctx.repos.locations.byId(it)?.timezone }?.let(ZoneId::of) ?: ZoneId.of("Asia/Kolkata")
            val (from, to) = q["date"]?.let { d ->
                val day = parseDate(d, zone)
                day.atStartOfDay(zone).toInstant() to day.plusDays(1).atStartOfDay(zone).toInstant()
            } ?: (null to null)
            val orders = ctx.repos.orders.byProvider(actor.providerId!!, q["locationId"], q["status"], from, to)
            call.respond(orders.filter { it.status != tenant.config.flow.initial && it.status != "EXPIRED" }.map { it.dto(tenant, actor) })
        }

        post("/walk-ins") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<WalkInRequest>()
            val order = ctx.orders.walkIn(tenant, actor, WalkIn(r.locationId, r.itemIds, r.variantKey, r.startsAt?.let(::parseInstant), r.customerName, r.customerPhone, r.plate, r.checkIn, r.bay))
            call.respond(HttpStatusCode.Created, order.dto(tenant, actor))
        }

        /** Counter check-in by the booking code shown on the customer's phone (typed or scanned). */
        post("/check-in") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val order = ctx.orders.checkInByCode(tenant, actor, call.receive<CheckInRequest>().code)
            call.respond(order.dto(tenant, actor))
        }

        get("/tickets") {
            val actor = call.actor(); actor.requireProvider()
            call.respond(ctx.repos.tickets.byProvider(actor.providerId!!).map { t -> t.dto(ctx.repos.orders.byId(t.orderId)) })
        }

        // ---- staff and money (owner + PIN) ----
        get("/staff") {
            val actor = call.actor(); actor.requireProvider()
            call.respond(ctx.providers.staff(actor).map { it.dto() })
        }

        post("/staff") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<StaffRequest>()
            val role = runCatching { Role.valueOf(r.role) }.getOrElse { throw invalid("Unknown role") }
            call.respond(HttpStatusCode.Created, ctx.providers.addStaff(tenant, actor, call.ownerPin(), r.name, ctx.auth.normalize(r.phone), role, r.pin).dto())
        }

        delete("/staff/{id}") {
            val actor = call.actor(); actor.requireProvider()
            ctx.providers.removeStaff(actor, call.ownerPin(), call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }

        get("/earnings") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            val (from, to) = periodOf(call.request.queryParameters["range"])
            call.respond(ctx.reports.earnings(tenant, actor, from, to))
        }

        get("/statement") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            val (from, to) = periodOf(call.request.queryParameters["range"])
            call.respondText(ctx.reports.statementCsv(tenant, actor, from, to), ContentType.Text.CSV)
        }

        get("/overview") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val q = call.request.queryParameters
            call.respond(ctx.reports.overview(tenant, actor, q["locationId"], q["range"] ?: "week"))
        }

        get("/reviews") {
            val actor = call.actor(); actor.requireProvider()
            call.respond(ctx.reports.reviews(actor, call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50).map { it.dto() })
        }

        post("/reviews/{id}/reply") {
            val actor = call.actor(); actor.requireProvider()
            call.respond(ctx.providers.replyToReview(actor, call.pathId(), call.receive<ReplyRequest>().text).dto())
        }

        put("/menu/bulk") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<BulkEditRequest>()
            val n = ctx.providers.bulkEdit(tenant, actor, call.ownerPin(), r.locationIds, r.edits.map { ProviderService.BulkEdit(it.name, it.prices, it.active, it.outOfStock) })
            call.respond(mapOf("changed" to n))
        }

        /** Everything the day-by-bay calendar draws: bookings with their bay, and closed windows with their bays. */
        get("/locations/{id}/calendar") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val location = ctx.repos.locations.byId(call.pathId())?.takeIf { it.providerId == actor.providerId } ?: throw notFound("Location")
            val zone = ZoneId.of(location.timezone)
            val q = call.request.queryParameters
            val start = parseDate(q["from"], zone)
            val days = (q["days"]?.toIntOrNull() ?: 1).coerceIn(1, 14)
            val from = start.atStartOfDay(zone).toInstant()
            val to = start.plusDays(days.toLong()).atStartOfDay(zone).toInstant()
            val dead = setOf(tenant.config.flow.initial, "EXPIRED")
            val orders = ctx.repos.orders.byProvider(actor.providerId!!, location.id, null, from, to).filter { it.status !in dead }
            call.respond(CalendarDto(location.capacity, location.slotMinutes, location.openTime.toString(), location.closeTime.toString(), orders.map { it.dto(tenant, actor) }, ctx.availability.windows(location, from, to).sortedBy { it.start }.map { it.dto() }))
        }

        get("/locations/{id}/closures") {
            val actor = call.actor(); actor.requireProvider()
            val location = ctx.repos.locations.byId(call.pathId())?.takeIf { it.providerId == actor.providerId } ?: throw notFound("Location")
            val far = Instant.now().plusSeconds(86_400L * 400)
            call.respond(ctx.repos.blocks.overlapping(location.id, Instant.now().minusSeconds(86_400), far).filter { it.repeat != Repeat.NONE || it.endsAt.isAfter(Instant.now()) }.map { it.dto() })
        }

        get("/payouts") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            ctx.providers.verifyPin(actor, call.ownerPin())
            call.respond(ctx.admin.payouts(tenant, actor.providerId).map { it.dto() })
        }
    }
}

fun Route.adminRoutes(ctx: AppContext) {
    route("/admin") {
        get("/dashboard") {
            val actor = call.actor(); actor.requireBackoffice()
            call.respond(ctx.admin.dashboard(call.tenantFor(ctx, actor), call.request.queryParameters["range"] ?: "7d"))
        }

        get("/providers") {
            val actor = call.actor(); actor.requireBackoffice()
            val status = call.request.queryParameters["status"]?.let { runCatching { ProviderStatus.valueOf(it) }.getOrNull() }
            val list = ctx.repos.providers.list(actor.tenantId, status).map { p ->
                p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id))
            }
            call.respond(list)
        }

        post("/providers") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<AdminCreateProviderRequest>()
            val p = ctx.providers.adminCreate(tenant, actor, r.name, r.ownerName, ctx.auth.normalize(r.ownerPhone), r.location.toDraft())
            call.respond(HttpStatusCode.Created, p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id)))
        }

        get("/providers/{id}") {
            val actor = call.actor(); actor.requireBackoffice()
            val p = ctx.repos.providers.byId(call.pathId())?.takeIf { it.tenantId == actor.tenantId } ?: throw notFound("Provider")
            call.respond(p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id), ctx.repos.users.byProvider(p.id).count { it.active }))
        }

        post("/providers/{id}/actions") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<ProviderActionRequest>()
            val action = runCatching { ProviderAction.valueOf(r.action) }.getOrElse { throw invalid("Unknown action ${r.action}") }
            val p = ctx.providers.transition(tenant, actor, call.pathId(), action, r.note)
            call.respond(p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id)))
        }

        put("/providers/{id}/commission") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            call.respond(ctx.providers.setCommission(tenant, actor, call.pathId(), call.receive<CommissionRequest>().bps).dto())
        }

        get("/orders") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val status = call.request.queryParameters["status"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            call.respond(ctx.repos.orders.all(tenant.id, status, null, null, limit).map { it.dto(tenant, actor) })
        }

        get("/customers") {
            val actor = call.actor(); actor.requireBackoffice()
            call.respond(ctx.admin.customers(call.tenantFor(ctx, actor), call.request.queryParameters["q"]))
        }

        put("/customers/{id}/active") {
            val actor = call.actor()
            ctx.admin.setCustomerActive(call.tenantFor(ctx, actor), actor, call.pathId(), call.receive<ActiveRequest>().active)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/payouts") {
            val actor = call.actor(); actor.require(Role.ADMIN, Role.FINANCE)
            val tenant = call.tenantFor(ctx, actor)
            val names = ctx.repos.providers.list(tenant.id).associate { it.id to it.name }
            call.respond(ctx.admin.payouts(tenant, null).map { it.dto(names[it.providerId]) })
        }

        post("/payouts/run") {
            val actor = call.actor()
            val tenant = call.tenantFor(ctx, actor)
            val names = ctx.repos.providers.list(tenant.id).associate { it.id to it.name }
            val q = call.request.queryParameters
            call.respond(ctx.admin.runPayouts(tenant, actor, q["from"]?.let(::parseInstant), q["to"]?.let(::parseInstant)).map { it.dto(names[it.providerId]) })
        }

        post("/payouts/{id}/retry") {
            val actor = call.actor()
            val tenant = call.tenantFor(ctx, actor)
            val names = ctx.repos.providers.list(tenant.id).associate { it.id to it.name }
            call.respond(ctx.admin.retryPayout(tenant, actor, call.pathId()).dto(names.values.firstOrNull()))
        }

        get("/payments/batch") {
            val actor = call.actor(); actor.require(Role.ADMIN, Role.FINANCE)
            val q = call.request.queryParameters
            call.respond(ctx.admin.batch(call.tenantFor(ctx, actor), q["from"]?.let(::parseInstant), q["to"]?.let(::parseInstant)))
        }
        get("/payments/collections") { val a = call.actor(); a.require(Role.ADMIN, Role.FINANCE); call.respond(ctx.admin.collections(call.tenantFor(ctx, a), 200).map { it.dto() }) }
        get("/payments/refunds") { val a = call.actor(); a.require(Role.ADMIN, Role.FINANCE); call.respond(ctx.admin.refunds(call.tenantFor(ctx, a), 200).map { it.dto() }) }
        get("/payments/ledger") { val a = call.actor(); a.require(Role.ADMIN, Role.FINANCE); call.respond(ctx.admin.ledgerEntries(call.tenantFor(ctx, a), 300).map { it.dto() }) }
        get("/payments/export") {
            val a = call.actor(); a.require(Role.ADMIN, Role.FINANCE)
            val rows = ctx.admin.ledgerEntries(call.tenantFor(ctx, a), 5000)
            call.respondText((listOf("time,type,account,order,amount") + rows.map { "${it.createdAt},${it.type},${it.account},${it.orderId ?: ""},${it.amountMinor / 100.0}" }).joinToString("\n"), ContentType.Text.CSV)
        }

        put("/providers/{id}/hold") {
            val actor = call.actor()
            val p = ctx.providers.setPayoutHold(call.tenantFor(ctx, actor), actor, call.pathId(), call.receive<HoldRequest>().hold)
            call.respond(p.dto())
        }

        post("/providers/{id}/documents/{type}") {
            val actor = call.actor()
            val r = call.receive<DocReviewRequest>()
            val state = runCatching { DocState.valueOf(r.state) }.getOrElse { throw invalid("State must be VERIFIED, NEEDS_FIX or PENDING") }
            val p = ctx.providers.reviewDocument(call.tenantFor(ctx, actor), actor, call.pathId(), call.pathId("type"), state, r.note)
            call.respond(p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id), ctx.repos.users.byProvider(p.id).count { it.active }))
        }

        get("/moderation/menu") { val a = call.actor(); a.requireBackoffice(); call.respond(ctx.admin.moderationOutliers(call.tenantFor(ctx, a))) }
        post("/moderation/menu/{itemId}") {
            val a = call.actor()
            ctx.admin.reviewOutlier(call.tenantFor(ctx, a), a, call.pathId("itemId"), call.receive<OutlierActionRequest>().hide)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/commission-rules") { val a = call.actor(); a.requireBackoffice(); call.respond(ctx.admin.rules(call.tenantFor(ctx, a)).map { it.dto() }) }
        post("/commission-rules") {
            val a = call.actor()
            val r = call.receive<RuleRequest>()
            val scope = runCatching { RuleScope.valueOf(r.scope) }.getOrElse { throw invalid("Scope must be OUTLET, VENDOR or CATEGORY") }
            call.respond(HttpStatusCode.Created, ctx.admin.addRule(call.tenantFor(ctx, a), a, scope, r.target, r.rateBps, parseInstant(r.effectiveFrom), r.effectiveUntil?.let(::parseInstant)).dto())
        }
        delete("/commission-rules/{id}") { val a = call.actor(); ctx.admin.deleteRule(call.tenantFor(ctx, a), a, call.pathId()); call.respond(HttpStatusCode.NoContent) }
        get("/commission-preview") {
            val a = call.actor(); a.requireBackoffice()
            val q = call.request.queryParameters
            call.respond(ctx.admin.preview(call.tenantFor(ctx, a), q["serviceMinor"]?.toLongOrNull() ?: 50_000, q["rateBps"]?.toIntOrNull() ?: 1500))
        }

        get("/billing") {
            val actor = call.actor(); actor.require(Role.ADMIN, Role.FINANCE)
            val tenant = call.tenantFor(ctx, actor)
            val month = call.request.queryParameters["month"]?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now()
            call.respond(ctx.admin.billing(tenant, month))
        }

        get("/tickets") {
            val actor = call.actor(); actor.requireBackoffice()
            val status = call.request.queryParameters["status"]?.let { runCatching { TicketStatus.valueOf(it) }.getOrNull() }
            call.respond(ctx.repos.tickets.list(actor.tenantId, status).map { t -> t.dto(ctx.repos.orders.byId(t.orderId)) })
        }

        post("/tickets/{id}/resolve") {
            val actor = call.actor(); actor.requireBackoffice()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<ResolveTicketRequest>()
            val t = ctx.support.resolve(tenant, actor, call.pathId(), r.uphold, r.refundMinor, r.note)
            call.respond(t.dto(ctx.repos.orders.byId(t.orderId)))
        }

        get("/config") {
            val actor = call.actor(); actor.requireBackoffice()
            call.respond(call.tenantFor(ctx, actor).config)
        }

        put("/config") {
            val actor = call.actor()
            val tenant = call.tenantFor(ctx, actor)
            call.respond(ctx.admin.updateConfig(tenant, actor, call.receive<TenantConfig>()).config)
        }

        get("/audit") {
            val actor = call.actor(); actor.requireBackoffice()
            call.respond(ctx.repos.audit.list(actor.tenantId, 200).map { it.dto() })
        }
    }
}

/** today / week / month as a time range ending now, in India time. */
private fun periodOf(range: String?): Pair<Instant, Instant> {
    val zone = ZoneId.of("Asia/Kolkata")
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
    val days = when (range) { "today" -> 1L; "month" -> 30L; else -> 7L }
    return end.minusSeconds(86_400 * days) to end
}
