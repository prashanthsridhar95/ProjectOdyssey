package com.odyssey.server.http

import com.odyssey.core.domain.*
import com.odyssey.core.service.*
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun Route.publicRoutes(ctx: AppContext) {
    get("/health") { call.respond(mapOf("status" to "ok")) }

    get("/config") { call.respond(call.publicTenant(ctx).publicConfig()) }

    post("/auth/otp") {
        val tenant = call.publicTenant(ctx)
        ctx.auth.requestOtp(tenant, call.receive<OtpRequest>().phone)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/auth/verify") {
        val tenant = call.publicTenant(ctx)
        val req = call.receive<VerifyRequest>()
        val result = ctx.auth.verifyOtp(tenant, req.phone, req.code, req.name)
        call.respond(AuthResponse(result.token.token, result.token.expiresAt.iso(), result.user.dto()))
    }

    /** Counter staff sign in with a 4-digit PIN. */
    post("/auth/pin") {
        val tenant = call.publicTenant(ctx)
        val req = call.receive<StaffLoginRequest>()
        val user = ctx.providers.staffLogin(tenant, ctx.auth.normalize(req.phone), req.pin)
        val result = ctx.auth.issueFor(user)
        call.respond(AuthResponse(result.token.token, result.token.expiresAt.iso(), user.dto()))
    }

    get("/locations") {
        val tenant = call.publicTenant(ctx)
        val p = call.request.queryParameters
        val lat = p["lat"]?.toDoubleOrNull() ?: throw invalid("lat is required")
        val lng = p["lng"]?.toDoubleOrNull() ?: throw invalid("lng is required")
        val query = SearchQuery(
            lat = lat, lng = lng, radiusKm = p["radiusKm"]?.toDoubleOrNull() ?: 15.0,
            sort = when (p["sort"]?.lowercase()) { "rating" -> Sort.RATING; "price" -> Sort.PRICE; else -> Sort.DISTANCE },
            text = p["q"], variantKey = p["variant"], maxPriceMinor = p["maxPrice"]?.toLongOrNull(),
            openNow = p["openNow"] == "true", amenities = p["amenities"]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
        call.respond(ctx.discovery.search(tenant, query).map { it.dto() })
    }

    get("/locations/{id}") {
        val tenant = call.publicTenant(ctx)
        val p = call.request.queryParameters
        val d = ctx.discovery.detail(tenant, call.pathId(), p["lat"]?.toDoubleOrNull(), p["lng"]?.toDoubleOrNull(), p["variant"])
        call.respond(LocationDetailDto(d.summary.dto(), d.items.map { it.dto() }, d.reviews.map { it.dto() }))
    }

    /** Slots for a day. Pass the chosen services as itemIds so multi-slot services are checked correctly. */
    get("/locations/{id}/availability") {
        val tenant = call.publicTenant(ctx)
        val location = ctx.repos.locations.byId(call.pathId())?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Location")
        val itemIds = call.request.queryParameters["itemIds"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val duration = if (itemIds.isEmpty()) location.slotMinutes
        else ctx.repos.catalog.byIds(itemIds).filter { it.locationId == location.id }.sumOf { it.durationMinutes }.takeIf { it > 0 } ?: throw invalid("Unknown services")
        val date = parseDate(call.request.queryParameters["date"], ZoneId.of(location.timezone))
        call.respond(ctx.availability.slots(tenant, location, date, duration, ctx.clock.now()).map { it.dto() })
    }

    /** Gateway callback. The signature is an HMAC-SHA256 of the raw body. */
    post("/webhooks/payments") {
        val body = call.receiveText()
        val signature = call.request.header("X-Signature") ?: throw DomainException("bad_signature", "Missing signature", 401)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ctx.config.webhookSecret.toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) throw DomainException("bad_signature", "Bad signature", 401)
        val event = appJson.decodeFromString(PaymentWebhook.serializer(), body)
        if (event.event == "payment.captured") {
            ctx.orders.capture(ctx.tenant(event.tenant), event.orderId, event.method, event.gatewayRef)
        }
        call.respond(HttpStatusCode.OK, mapOf("received" to true))
    }
}

fun Route.customerRoutes(ctx: AppContext) {
    get("/me") {
        val actor = call.actor()
        val user = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        call.respond(user.dto())
    }

    // ---- saved assets (vehicles for car wash) ----
    get("/assets") { call.respond(ctx.repos.assets.byOwner(call.actor().userId!!).map { it.dto() }) }

    post("/assets") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val kind = tenant.config.asset ?: throw invalid("This marketplace has no saved items")
        val req = call.receive<AssetRequest>()
        kind.fields.filter { it.required }.forEach { f ->
            val v = req.attributes[f.key]
            if (v.isNullOrBlank()) throw invalid("${f.label} is required")
            if (f.options.isNotEmpty() && v !in f.options) throw invalid("${f.label} is not valid")
        }
        val label = req.label?.takeIf { it.isNotBlank() } ?: req.attributes.values.joinToString(" · ")
        val asset = Asset(ctx.ids.newId(), tenant.id, actor.userId!!, kind.kind, label, req.attributes)
        ctx.repos.assets.insert(asset)
        call.respond(HttpStatusCode.Created, asset.dto())
    }

    delete("/assets/{id}") {
        if (!ctx.repos.assets.delete(call.pathId(), call.actor().userId!!)) throw notFound("Saved item")
        call.respond(HttpStatusCode.NoContent)
    }

    // ---- orders ----
    post("/orders") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val customer = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val req = call.receive<PlaceOrderRequest>()
        val dto = call.idempotent<OrderDto>(ctx, tenant.id, "order:place:${customer.id}") {
            ctx.orders.place(tenant, customer, PlaceOrder(req.locationId, req.itemIds, req.variantKey, parseInstant(req.startsAt), req.assetId)).dto(tenant, actor)
        }
        call.respond(HttpStatusCode.Created, dto)
    }

    post("/orders/{id}/pay") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val customer = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val req = call.receiveNullable<PayRequest>() ?: PayRequest()
        val orderId = call.pathId()
        val key = call.request.header("Idempotency-Key") ?: ctx.ids.newId()
        val dto = call.idempotent<OrderDto>(ctx, tenant.id, "order:pay:$orderId") {
            ctx.orders.pay(tenant, customer, orderId, req.method, req.simulate, key).dto(tenant, actor)
        }
        call.respond(dto)
    }

    get("/orders") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val status = call.request.queryParameters["status"]
        val list = ctx.orders.mine(actor.userId!!).filter { status == null || it.status == status }
        call.respond(list.map { it.dto(tenant, actor, reviewed = it.status == tenant.config.flow.completed && ctx.repos.reviews.byOrder(it.id) != null) })
    }

    get("/orders/{id}") {
        val actor = call.actor()
        val tenant = call.tenantFor(ctx, actor)
        val order = ctx.orders.get(tenant, actor, call.pathId())
        val reviewed = ctx.repos.reviews.byOrder(order.id) != null
        call.respond(OrderDetailDto(order.dto(tenant, actor, reviewed), ctx.orders.events(order.id).map { OrderEventDto(it.from, it.to, it.action, it.note, it.at.iso()) }))
    }

    /** One endpoint for every state change; the order flow decides who may do what. */
    post("/orders/{id}/actions") {
        val actor = call.actor()
        val tenant = call.tenantFor(ctx, actor)
        val req = call.receive<ActionRequest>()
        if (req.action in setOf("REJECT", "CANCEL_BY_PROVIDER", "ADMIN_CANCEL") && req.note.isNullOrBlank()) throw invalid("Please give a reason")
        val order = ctx.orders.act(tenant, actor, call.pathId(), req.action, req.note)
        call.respond(order.dto(tenant, actor))
    }

    post("/orders/{id}/review") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val customer = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val req = call.receive<ReviewRequest>()
        call.respond(HttpStatusCode.Created, ctx.orders.review(tenant, customer, call.pathId(), req.rating, req.comment).dto())
    }

    post("/provider/apply") {
        val actor = call.actor()
        actor.require(Role.CUSTOMER)
        val tenant = call.tenantFor(ctx, actor)
        val user = ctx.repos.users.byId(actor.userId!!) ?: throw notFound("User")
        val req = call.receive<ApplyRequest>()
        val provider = ctx.providers.apply(tenant, user, req.name, req.location.toDraft())
        call.respond(HttpStatusCode.Created, provider.dto(user.copy(role = Role.PROVIDER_OWNER), ctx.repos.locations.byProvider(provider.id)))
    }
}
