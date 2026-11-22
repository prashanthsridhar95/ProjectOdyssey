package com.odyssey.server.http

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.service.*
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
                    autoAccept = req.autoAccept ?: l.autoAccept, open = req.open ?: l.open, amenities = req.amenities ?: l.amenities,
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
            val item = ctx.providers.saveItem(tenant, actor, call.ownerPin(), call.pathId(), null, r.name, r.description, r.category, r.durationMinutes, r.active, r.prices)
            call.respond(HttpStatusCode.Created, item.dto())
        }

        put("/locations/{id}/items/{itemId}") {
            val actor = call.actor(); actor.requireProvider()
            val tenant = call.tenantFor(ctx, actor)
            val r = call.receive<ItemRequest>()
            val item = ctx.providers.saveItem(tenant, actor, call.ownerPin(), call.pathId(), call.pathId("itemId"), r.name, r.description, r.category, r.durationMinutes, r.active, r.prices)
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
            call.respond(HttpStatusCode.Created, ctx.providers.addBlock(tenant, actor, call.pathId(), parseInstant(r.startsAt), parseInstant(r.endsAt), r.reason).dto())
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
            val order = ctx.orders.walkIn(tenant, actor, WalkIn(r.locationId, r.itemIds, r.variantKey, parseInstant(r.startsAt), r.customerName, r.customerPhone))
            call.respond(HttpStatusCode.Created, order.dto(tenant, actor))
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
            call.respond(ctx.admin.earnings(tenant, actor, null, null))
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
            call.respond(ctx.admin.dashboard(call.tenantFor(ctx, actor)))
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
            call.respond(p.dto(ctx.repos.users.byId(p.ownerUserId), ctx.repos.locations.byProvider(p.id)))
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
            call.respond(ctx.repos.users.byRole(actor.tenantId, Role.CUSTOMER, 500).map { CustomerDto(it.id, it.name, it.phone, it.active) })
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
            call.respond(ctx.admin.runPayouts(tenant, actor).map { it.dto(names[it.providerId]) })
        }

        get("/billing") {
            val actor = call.actor(); actor.require(Role.ADMIN, Role.FINANCE)
            val tenant = call.tenantFor(ctx, actor)
            val month = call.request.queryParameters["month"]?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now()
            call.respond(ctx.admin.billing(tenant, month))
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
