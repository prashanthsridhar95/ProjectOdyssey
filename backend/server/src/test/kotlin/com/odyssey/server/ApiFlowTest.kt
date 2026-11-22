package com.odyssey.server

import com.odyssey.server.http.*
import com.odyssey.server.wiring.AppConfig
import com.odyssey.server.wiring.AppContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

private const val OWNER1 = "+919000000001" // Sparkle, 3 bays, auto-accept
private const val OWNER2 = "+919000000002" // Bubble Bay, 2 bays
private const val ADMIN = Seeder.ADMIN_PHONE
private const val IST = "Asia/Kolkata"

private fun newContext(): AppContext {
    val ctx = AppContext(AppConfig(dbUrl = "jdbc:h2:mem:t${UUID.randomUUID().toString().replace("-", "")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"))
    Seeder(ctx).seedIfEmpty()
    return ctx
}

private fun withApi(block: suspend ApiScope.() -> Unit) = testApplication {
    val ctx = newContext()
    application { module(ctx) }
    val client = createClient { install(ContentNegotiation) { json(appJson) } }
    ApiScope(ctx, client).block()
}

private class ApiScope(val ctx: AppContext, val client: HttpClient) {
    private val tokens = mutableMapOf<String, String>()

    suspend fun login(phone: String, name: String? = null): String = tokens.getOrPut(phone) {
        val res = client.post("/v1/auth/verify") { contentType(ContentType.Application.Json); setBody(VerifyRequest(phone, "123456", name)) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        res.body<AuthResponse>().accessToken
    }

    suspend fun get(path: String, token: String? = null, pin: String? = null): HttpResponse = client.get(path) { auth(token, pin) }
    suspend inline fun <reified T> post(path: String, body: T, token: String? = null, pin: String? = null, idem: String? = null): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body); auth(token, pin); idem?.let { header("Idempotency-Key", it) } }
    suspend inline fun <reified T> put(path: String, body: T, token: String? = null, pin: String? = null): HttpResponse =
        client.put(path) { contentType(ContentType.Application.Json); setBody(body); auth(token, pin) }
    suspend fun postEmpty(path: String, token: String? = null): HttpResponse = client.post(path) { auth(token, null) }

    private fun HttpRequestBuilder.auth(token: String?, pin: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        pin?.let { header("X-Owner-Pin", it) }
    }

    suspend fun locations(): List<LocationSummaryDto> = get("/v1/locations?lat=12.97&lng=77.6&radiusKm=30").body()
    suspend fun location(name: String) = locations().first { it.name.contains(name) }
    suspend fun detail(id: String): LocationDetailDto = get("/v1/locations/$id").body()

    /** First bookable slot tomorrow at or after the given hour. */
    suspend fun slot(locationId: String, itemIds: List<String>, fromHour: Int = 10, skip: Int = 0): String {
        val tomorrow = LocalDate.now(ZoneId.of(IST)).plusDays(1)
        val slots: List<SlotDto> = get("/v1/locations/$locationId/availability?date=$tomorrow&itemIds=${itemIds.joinToString(",")}").body()
        return slots.filter { it.bookable && Instant.parse(it.startsAt).atZone(ZoneId.of(IST)).hour >= fromHour }[skip].startsAt
    }

    suspend fun place(token: String, loc: LocationDetailDto, itemName: String, startsAt: String, variant: String = "SEDAN", idem: String? = null): HttpResponse {
        val item = loc.items.first { it.name == itemName }
        return post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), variant, startsAt), token, idem = idem)
    }

    suspend fun delete(path: String, token: String? = null): HttpResponse = client.delete(path) { auth(token, null) }
    suspend fun pay(token: String, orderId: String, simulate: String? = null): HttpResponse = post("/v1/orders/$orderId/pay", PayRequest("UPI", simulate), token)
    suspend fun act(token: String, orderId: String, action: String, note: String? = null): HttpResponse = post("/v1/orders/$orderId/actions", ActionRequest(action, note), token)
}

class ApiFlowTest {

    @Test
    fun `public config describes the car wash vertical without commercial terms`() = withApi {
        val cfg: PublicConfig = get("/v1/config").body()
        assertEquals("Vendor", cfg.config.labels.provider)
        assertEquals("Vehicle type", cfg.config.variants.name)
        assertEquals(0, cfg.config.money.defaultCommissionBps)
        assertEquals(0, cfg.config.billing.includedOrders)
    }

    @Test
    fun `customer discovers books pays and the vendor completes it`() = withApi {
        val customer = login("+919876500001", "Asha")
        val found = locations()
        assertEquals(4, found.size, "only LIVE vendors are listed")
        assertTrue(found.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm }, "sorted by distance")

        val spot = location("Sparkle")
        val detail = detail(spot.id)
        assertEquals(9, detail.items.size)
        val basic = detail.items.first { it.name == "Basic wash" }
        val start = slot(spot.id, listOf(basic.id))

        val placed = place(customer, detail, "Basic wash", start)
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val order: OrderDto = placed.body()
        assertEquals("PENDING_PAYMENT", order.status)
        val price = basic.prices.getValue("SEDAN")
        assertEquals(price, order.subtotalMinor)
        val fee = 2000L
        assertEquals(price + fee + Math.round((price + fee) * 0.18), order.totalMinor, "18% tax on the service plus the convenience fee")
        assertNull(order.commissionBps, "customers never see commission")

        val paid: OrderDto = pay(customer, order.id).also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.body()
        assertEquals("CONFIRMED", paid.status, "auto-accept confirms straight after payment")

        val owner = login(OWNER1)
        val inbox: List<OrderDto> = get("/v1/provider/orders", owner).body()
        val mine = inbox.single { it.id == order.id }
        assertEquals(1400, mine.commissionBps, "the outlet rule for Sparkle Indiranagar applies")
        assertTrue("START" in mine.actions && "NO_SHOW" in mine.actions)

        assertEquals("IN_PROGRESS", act(owner, order.id, "START").body<OrderDto>().status)
        assertEquals("COMPLETED", act(owner, order.id, "COMPLETE").body<OrderDto>().status)

        // the customer can now review once
        assertEquals(HttpStatusCode.Created, post("/v1/orders/${order.id}/review", ReviewRequest(5, "Spotless"), customer).status)
        assertEquals(HttpStatusCode.Conflict, post("/v1/orders/${order.id}/review", ReviewRequest(4, "again"), customer).status)

        // ledger balances and the commission is 15% of the service amount
        val sums = ctx.repos.ledger.sumByAccount(listOf(order.id))
        assertEquals(0, ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).values.sum(), "every ledger transaction balances")
        assertEquals(-Math.round(price * 0.14), sums.getValue("tenant_commission"))
        assertEquals(-Math.round(price * 0.013), sums.getValue("gateway_fee"), "the gateway's cut comes out of the vendor's share")
    }

    @Test
    fun `payout covers settled orders minus commission and fee, and only once`() = withApi {
        val customer = login("+919876500002", "Ravi")
        val detail = detail(location("Sparkle").id)
        val start = slot(detail.location.id, listOf(detail.items.first { it.name == "Foam wash" }.id))
        val order: OrderDto = place(customer, detail, "Foam wash", start).body()
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")

        val admin = login(ADMIN)
        val run: List<PayoutDto> = postEmpty("/v1/admin/payouts/run", admin).body()
        val payout = run.first { it.providerName == "Sparkle Auto Spa" }
        val paid = ctx.repos.orders.byId(order.id)!!
        assertEquals(payout.id, paid.payoutId)
        val owed = ctx.repos.orders.byProvider(paid.providerId, null, null, null, null).filter { it.payoutId == payout.id }.sumOf { ctx.repos.ledger.payableFor(it.id) }
        assertEquals(owed, payout.amountMinor)
        val sub = detail.items.first { it.name == "Foam wash" }.prices.getValue("SEDAN")
        assertEquals(sub - Math.round(sub * 0.14) - Math.round(sub * 0.013), ctx.repos.ledger.payableFor(order.id))
        assertEquals(emptyList(), postEmpty("/v1/admin/payouts/run", admin).body<List<PayoutDto>>(), "a second run pays nothing")
    }

    @Test
    fun `two customers cannot take the last bay`() = withApi {
        val detail = detail(location("Bubble Bay").id) // 2 bays
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val tokens = listOf("+919876500011", "+919876500012", "+919876500013").map { login(it, "C") }
        val results = tokens.map { place(it, detail, "Basic wash", start).status }
        assertEquals(2, results.count { it == HttpStatusCode.Created })
        assertEquals(1, results.count { it == HttpStatusCode.Conflict })
    }

    @Test
    fun `retrying a booking with the same idempotency key returns the same booking`() = withApi {
        val customer = login("+919876500021", "Meera")
        val detail = detail(location("Sparkle").id)
        val start = slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))
        val a: OrderDto = place(customer, detail, "Basic wash", start, idem = "key-1").body()
        val b: OrderDto = place(customer, detail, "Basic wash", start, idem = "key-1").body()
        assertEquals(a.id, b.id)
        assertEquals(1, ctx.repos.orders.byCustomer(a.let { ctx.repos.orders.byId(it.id)!!.customerId!! }).size)
    }

    @Test
    fun `a declined payment keeps the hold and a later retry succeeds`() = withApi {
        val customer = login("+919876500031", "Kiran")
        val detail = detail(location("Sparkle").id)
        val start = slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))
        val order: OrderDto = place(customer, detail, "Basic wash", start).body()
        val declined = pay(customer, order.id, simulate = "fail")
        assertEquals(HttpStatusCode.PaymentRequired, declined.status)
        assertEquals("payment_failed", declined.body<ErrorBody>().code)
        assertEquals("CONFIRMED", pay(customer, order.id).body<OrderDto>().status)
    }

    @Test
    fun `cancelling early refunds in full and cancelling late keeps half`() = withApi {
        val customer = login("+919876500041", "Neha")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }

        // tomorrow is far more than 2 hours away -> full refund
        val early: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id))).body()
        pay(customer, early.id)
        assertEquals("CANCELLED", act(customer, early.id, "CANCEL_BY_CUSTOMER").body<OrderDto>().status)
        assertEquals(early.totalMinor, ctx.repos.payments.byOrder(early.id)!!.refundedMinor)

        // an order starting in under 2 hours keeps 50%: move its start time to simulate it
        val late: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id), skip = 3)).body()
        pay(customer, late.id)
        val stored = ctx.repos.orders.byId(late.id)!!
        ctx.repos.orders.update(stored.copy(startsAt = Instant.now().plusSeconds(3600), endsAt = Instant.now().plusSeconds(5400)))
        assertEquals("CANCELLED", act(customer, late.id, "CANCEL_BY_CUSTOMER").body<OrderDto>().status)
        val payment = ctx.repos.payments.byOrder(late.id)!!
        assertEquals(late.totalMinor / 2, payment.refundedMinor)
        assertEquals(PaymentStatusView.PARTIAL, PaymentStatusView.of(payment.status.name))
        assertEquals(0, ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).values.sum())
    }

    @Test
    fun `vendor cancel and reject need a reason and refund in full`() = withApi {
        val customer = login("+919876500051", "Dev")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id))).body()
        pay(customer, order.id)
        val owner = login(OWNER1)
        assertEquals(HttpStatusCode.BadRequest, act(owner, order.id, "CANCEL_BY_PROVIDER").status, "reason is required")
        assertEquals("CANCELLED", act(owner, order.id, "CANCEL_BY_PROVIDER", "Machine broken").body<OrderDto>().status)
        assertEquals(order.totalMinor, ctx.repos.payments.byOrder(order.id)!!.refundedMinor)
        assertEquals(HttpStatusCode.Forbidden, act(login(OWNER2), order.id, "START").status, "other vendors cannot touch it")
    }

    @Test
    fun `price changes need the owner PIN and five wrong PINs lock it`() = withApi {
        val owner = login(OWNER1)
        val me: ProviderDto = get("/v1/provider/me", owner).body()
        val loc = me.locations.single()
        val item: ItemDto = get("/v1/provider/locations/${loc.id}/items", owner).body<List<ItemDto>>().first { it.name == "Basic wash" }
        val newPrices = item.prices + ("SEDAN" to 39900L)
        val req = ItemRequest(item.name, item.description, item.category, item.durationMinutes, true, newPrices)

        assertEquals(HttpStatusCode.Forbidden, put("/v1/provider/locations/${loc.id}/items/${item.id}", req, owner).status, "a price change without the PIN is refused")
        assertEquals(HttpStatusCode.OK, put("/v1/provider/locations/${loc.id}/items/${item.id}", req, owner, Seeder.OWNER_PIN).status)

        // turning a service off is not a price change, so it needs no PIN
        assertEquals(HttpStatusCode.OK, put("/v1/provider/locations/${loc.id}/items/${item.id}", req.copy(active = false), owner).status)

        repeat(5) { assertEquals(HttpStatusCode.Forbidden, get("/v1/provider/earnings", owner, "000000").status) }
        assertEquals(HttpStatusCode.fromValue(423), get("/v1/provider/earnings", owner, Seeder.OWNER_PIN).status, "locked even with the right PIN")
    }

    @Test
    fun `counter staff sign in with a PIN but cannot change prices or see earnings`() = withApi {
        val res = post("/v1/auth/pin", StaffLoginRequest("+919000000201", Seeder.STAFF_PIN))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val staff = res.body<AuthResponse>()
        assertEquals("PROVIDER_STAFF", staff.user.role)
        assertEquals(HttpStatusCode.Unauthorized, post("/v1/auth/pin", StaffLoginRequest("+919000000201", "9999")).status)
        assertEquals(HttpStatusCode.Forbidden, get("/v1/provider/earnings", staff.accessToken, Seeder.OWNER_PIN).status)
        val loc = get("/v1/provider/me", staff.accessToken).body<ProviderDto>().locations.single()
        val block = BlockRequest(Instant.now().plusSeconds(86400).toString(), Instant.now().plusSeconds(90000).toString(), "x")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/provider/locations/${loc.id}/blocks", block, staff.accessToken).status)
    }

    @Test
    fun `blocking a slot hides it and is refused when bookings exist`() = withApi {
        val customer = login("+919876500061", "Zoya")
        val owner = login(OWNER1)
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val s = Instant.parse(start)
        val block = BlockRequest(s.toString(), s.plusSeconds(1800).toString(), "Maintenance")
        val created = post("/v1/provider/locations/${detail.location.id}/blocks", block, owner)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val tomorrow = LocalDate.now(ZoneId.of(IST)).plusDays(1)
        val slots: List<SlotDto> = get("/v1/locations/${detail.location.id}/availability?date=$tomorrow&itemIds=${item.id}").body()
        assertFalse(slots.first { it.startsAt == start }.bookable)
        assertEquals(HttpStatusCode.Conflict, place(customer, detail, "Basic wash", start).status)

        // a booking elsewhere makes a block over it impossible
        val other = slot(detail.location.id, listOf(item.id), skip = 4)
        val order: OrderDto = place(customer, detail, "Basic wash", other).body()
        pay(customer, order.id)
        val o = Instant.parse(other)
        val clash = post("/v1/provider/locations/${detail.location.id}/blocks", BlockRequest(o.toString(), o.plusSeconds(1800).toString(), "x"), owner)
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertEquals("has_bookings", clash.body<ErrorBody>().code)
    }

    @Test
    fun `walk-ins take capacity and never touch the ledger`() = withApi {
        val owner = login(OWNER2)
        val before = ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null)
        val detail = detail(location("Bubble Bay").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val req = WalkInRequest(detail.location.id, listOf(item.id), "HATCHBACK", start, "Walk-in Raju")
        repeat(2) { assertEquals(HttpStatusCode.Created, post("/v1/provider/walk-ins", req, owner).status) }
        assertEquals(HttpStatusCode.Conflict, post("/v1/provider/walk-ins", req, owner).status, "both bays are taken")
        val after = ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null)
        assertEquals(before, after, "walk-ins move no money through the platform")
    }

    @Test
    fun `admin onboards a vendor through the verification workflow and sets commission`() = withApi {
        val admin = login(ADMIN)
        val create = post(
            "/v1/admin/providers",
            AdminCreateProviderRequest("Foam Fiesta", "Sunil", "+919111111111", LocationDraftDto("Foam Fiesta MG Road", "MG Road, Bengaluru", 12.975, 77.606)),
            admin,
        )
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val provider = create.body<ProviderDto>()
        assertEquals("UNDER_REVIEW", provider.status)
        assertEquals(4, locations().size, "not listed until live")

        assertEquals(HttpStatusCode.Conflict, post("/v1/admin/providers/${provider.id}/actions", ProviderActionRequest("GO_LIVE"), admin).status, "must be approved first")
        assertEquals("APPROVED", post("/v1/admin/providers/${provider.id}/actions", ProviderActionRequest("APPROVE", "Docs verified"), admin).body<ProviderDto>().status)
        assertEquals(1200, put("/v1/admin/providers/${provider.id}/commission", CommissionRequest(1200), admin).body<ProviderDto>().commissionBps)
        assertEquals("LIVE", post("/v1/admin/providers/${provider.id}/actions", ProviderActionRequest("GO_LIVE"), admin).body<ProviderDto>().status)
        assertEquals(5, locations().size)
        assertEquals("SUSPENDED", post("/v1/admin/providers/${provider.id}/actions", ProviderActionRequest("SUSPEND", "Complaints"), admin).body<ProviderDto>().status)
        assertEquals(4, locations().size, "suspended vendors disappear from discovery")

        val audit: List<AuditDto> = get("/v1/admin/audit", admin).body()
        assertTrue(audit.any { it.action == "COMMISSION_SET" })
        assertEquals(HttpStatusCode.Forbidden, get("/v1/admin/dashboard", login("+919876500071", "Nope")).status)
    }

    @Test
    fun `billing charges the base subscription and a percentage per order above the threshold`() = withApi {
        val admin = login(ADMIN)
        val tenant = ctx.tenant("carwash")
        ctx.admin.updateConfig(tenant, com.odyssey.core.domain.Actor(null, com.odyssey.core.domain.Role.ADMIN, tenant.id), tenant.config.copy(billing = com.odyssey.core.config.BillingConfig(1_000_000, 1, 200)))
        val before: com.odyssey.core.service.BillingStatement = get("/v1/admin/billing", admin).body()
        val customer = login("+919876500081", "Billing")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        repeat(3) { i ->
            val o: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id), skip = i * 2)).body()
            pay(customer, o.id)
        }
        val statement: com.odyssey.core.service.BillingStatement = get("/v1/admin/billing", admin).body()
        assertEquals(3, statement.orders - before.orders)
        assertEquals(3, statement.overageOrders - before.overageOrders)
        val sub = item.prices.getValue("SEDAN")
        assertEquals(3 * Math.round(sub * 0.02), statement.perOrderChargeMinor - before.perOrderChargeMinor)
        assertEquals(1_000_000 + statement.perOrderChargeMinor, statement.totalMinor)
    }

    @Test
    fun `payment webhook confirms an order and rejects a bad signature`() = withApi {
        val customer = login("+919876500091", "Hook")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        val body = appJson.encodeToString(PaymentWebhook.serializer(), PaymentWebhook("payment.captured", "carwash", order.id, "gw_1"))
        val bad = client.post("/v1/webhooks/payments") { header("X-Signature", "deadbeef"); setBody(body) }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("dev-webhook-secret".toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        repeat(2) { assertEquals(HttpStatusCode.OK, client.post("/v1/webhooks/payments") { header("X-Signature", sig); setBody(body) }.status) }
        assertEquals("CONFIRMED", get("/v1/orders/${order.id}", customer).body<OrderDetailDto>().order.status)
        assertEquals(1, ctx.repos.ledger.forOrder(order.id).count { it.account == "gateway" }, "a repeated webhook posts the ledger once")
    }

    @Test
    fun `expired holds release the slot`() = withApi {
        val customer = login("+919876500101", "Late")
        val detail = detail(location("Bubble Bay").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val a: OrderDto = place(customer, detail, "Basic wash", start).body()
        val b: OrderDto = place(login("+919876500102", "Other"), detail, "Basic wash", start).body()
        assertEquals(HttpStatusCode.Conflict, place(login("+919876500103", "Third"), detail, "Basic wash", start).status, "two unpaid holds fill both bays")
        ctx.repos.orders.update(ctx.repos.orders.byId(a.id)!!.copy(holdExpiresAt = Instant.now().minusSeconds(10)))
        assertEquals(1, ctx.orders.expireHolds())
        assertEquals("EXPIRED", get("/v1/orders/${a.id}", customer).body<OrderDetailDto>().order.status)
        assertEquals(HttpStatusCode.Created, place(login("+919876500103", "Third"), detail, "Basic wash", start).status)
        assertNotNull(b)
    }

    @Test
    fun `saving a vehicle and unauthenticated access`() = withApi {
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/orders").status)
        val customer = login("+919876500111", "Garage")
        val bad = post("/v1/assets", AssetRequest(null, mapOf("vehicleType" to "TANK", "plate" to "KA01")), customer)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val ok = post("/v1/assets", AssetRequest(null, mapOf("vehicleType" to "SEDAN", "plate" to "KA01AB1234")), customer)
        assertEquals(HttpStatusCode.Created, ok.status)
        assertEquals(1, get("/v1/assets", customer).body<List<AssetDto>>().size)
    }

    // ---- customer features from the Rinse design ----

    @Test
    fun `a promo code discounts the booking, funds it from commission and can be used once`() = withApi {
        val customer = login("+919876501001", "Promo")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val price = item.prices.getValue("SEDAN")
        val q: QuoteDto = post("/v1/quote", QuoteRequest(detail.location.id, listOf(item.id), "SEDAN", "firstwash"), customer).body()
        assertEquals(10_000, q.discountMinor)
        assertEquals(price + 2000 + Math.round((price + 2000) * 0.18) - 10_000, q.totalMinor)

        val start = slot(detail.location.id, listOf(item.id))
        val placed = post("/v1/orders", PlaceOrderRequest(detail.location.id, listOf(item.id), "SEDAN", start, null, "FIRSTWASH"), customer)
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val order: OrderDto = placed.body()
        assertEquals(q.totalMinor, order.totalMinor)
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")
        assertEquals(0, ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).values.sum(), "ledger balances with a promo")

        val again = post("/v1/quote", QuoteRequest(detail.location.id, listOf(item.id), "SEDAN", "FIRSTWASH"), customer)
        assertEquals(HttpStatusCode.BadRequest, again.status, "one use per customer")
        val bike = detail.items.first { it.name == "Basic wash" }
        val cheap = post("/v1/quote", QuoteRequest(detail.location.id, listOf(bike.id), "BIKE", "FIRSTWASH"), login("+919876501002", "Cheap"))
        assertEquals(HttpStatusCode.BadRequest, cheap.status, "minimum spend applies")
        assertEquals(HttpStatusCode.BadRequest, post("/v1/quote", QuoteRequest(detail.location.id, listOf(item.id), "SEDAN", "NOPE"), login("+919876501003", "X")).status)
    }

    @Test
    fun `a booking can be moved twice before the cutoff and not a third time`() = withApi {
        val customer = login("+919876501011", "Mover")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id))).body()
        pay(customer, order.id)
        var current = order.startsAt
        repeat(2) { i ->
            val target = slot(detail.location.id, listOf(item.id), skip = 3 + i * 2)
            val res = post("/v1/orders/${order.id}/reschedule", RescheduleRequest(target), customer)
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            val moved = res.body<OrderDto>()
            assertEquals(Instant.parse(target), Instant.parse(moved.startsAt))
            assertEquals(i + 1, moved.reschedules)
            current = moved.startsAt
        }
        val third = post("/v1/orders/${order.id}/reschedule", RescheduleRequest(slot(detail.location.id, listOf(item.id), skip = 9)), customer)
        assertEquals("limit_reached", third.body<ErrorBody>().code)
        assertNotEquals(order.startsAt, current)
        val owner = login(OWNER1)
        assertTrue(ctx.repos.notifications.list(ctx.repos.users.byPhone(ctx.tenant("carwash").id, OWNER1)!!.id, 20).any { it.title.contains("moved") }, "the vendor is told")
        assertNotNull(owner)
    }

    @Test
    fun `a slow bank leaves the payment pending until the customer checks again`() = withApi {
        val customer = login("+919876501021", "Slow")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        val pending: OrderDto = pay(customer, order.id, simulate = "pending").body()
        assertEquals("PENDING_PAYMENT", pending.status)
        assertTrue(pending.paymentPending)
        val first: OrderDto = postEmpty("/v1/orders/${order.id}/payment/check", customer).body()
        assertTrue(first.paymentPending && first.status == "PENDING_PAYMENT", "still waiting on the first check")
        val second: OrderDto = postEmpty("/v1/orders/${order.id}/payment/check", customer).body()
        assertEquals("CONFIRMED", second.status)
        assertEquals(1, ctx.repos.ledger.forOrder(order.id).count { it.account == "gateway" })
    }

    @Test
    fun `counter check-in by code moves the booking through checked in and in progress`() = withApi {
        val customer = login("+919876501031", "Checkin")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        pay(customer, order.id)
        val staff = post("/v1/auth/pin", StaffLoginRequest("+919000000201", Seeder.STAFF_PIN)).body<AuthResponse>().accessToken
        val scanned = post("/v1/provider/check-in", CheckInRequest(" ${order.code.lowercase()} "), staff)
        assertEquals(HttpStatusCode.OK, scanned.status, scanned.bodyAsText())
        assertEquals("CHECKED_IN", scanned.body<OrderDto>().status)
        assertEquals(HttpStatusCode.NotFound, post("/v1/provider/check-in", CheckInRequest("ZZZZZZ"), staff).status)
        assertEquals("IN_PROGRESS", act(staff, order.id, "START").body<OrderDto>().status)
        val detailView: OrderDetailDto = get("/v1/orders/${order.id}", customer).body()
        assertEquals(listOf("PENDING_PAYMENT", "PAID", "CONFIRMED", "CHECKED_IN", "IN_PROGRESS"), detailView.events.map { it.to })
        assertNotNull(detailView.order.locationPhone)
    }

    @Test
    fun `customers get notified as the booking progresses and the vendor hears about new bookings`() = withApi {
        val customer = login("+919876501041", "Notified")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")
        val inbox: List<NotificationDto> = get("/v1/notifications", customer).body()
        assertEquals(listOf("Your car is ready", "Wash in progress", "You're booked"), inbox.map { it.title })
        assertTrue(inbox.all { !it.read })
        postEmpty("/v1/notifications/read", customer)
        assertTrue(get("/v1/notifications", customer).body<List<NotificationDto>>().all { it.read })
        assertTrue(get("/v1/notifications", owner).body<List<NotificationDto>>().any { it.title.startsWith("New") })
    }

    @Test
    fun `favourites and saved places`() = withApi {
        val customer = login("+919876501051", "Fav")
        val spot = location("Sparkle")
        assertEquals(HttpStatusCode.NoContent, client.put("/v1/favorites/${spot.id}") { header(HttpHeaders.Authorization, "Bearer $customer") }.status)
        assertEquals(listOf(spot.id), get("/v1/favorites?lat=12.97&lng=77.6", customer).body<List<LocationSummaryDto>>().map { it.id })
        delete("/v1/favorites/${spot.id}", customer)
        assertTrue(get("/v1/favorites", customer).body<List<LocationSummaryDto>>().isEmpty())
        val places: List<SavedPlaceDto> = put("/v1/me/places", SavedPlaceDto("Home", "12th Main, Indiranagar", 12.97, 77.64), customer).body()
        assertEquals("Home", places.single().label)
        assertEquals("Office", put("/v1/me/places", SavedPlaceDto("Office", "MG Road", 12.97, 77.6), customer).body<List<SavedPlaceDto>>().first { it.label == "Office" }.label)
    }

    @Test
    fun `search filters by service, rating and sorts by earliest slot`() = withApi {
        val q = "lat=12.97&lng=77.6&radiusKm=30"
        val top = get("/v1/locations?$q&minRating=4.7", null).body<List<LocationSummaryDto>>()
        assertEquals(listOf("Shine Street HSR"), top.map { it.name })
        assertEquals(4, get("/v1/locations?$q&services=Foam wash", null).body<List<LocationSummaryDto>>().size)
        assertEquals(0, get("/v1/locations?$q&services=Rocket polish", null).body<List<LocationSummaryDto>>().size)
        assertEquals(4, get("/v1/locations?$q&sort=earliest", null).body<List<LocationSummaryDto>>().size)
        val cheap = get("/v1/locations?$q&variant=SEDAN&maxPrice=30000", null).body<List<LocationSummaryDto>>()
        assertTrue(cheap.all { (it.priceFromMinor ?: Long.MAX_VALUE) <= 30000 })
    }

    @Test
    fun `a problem report can be upheld with a partial refund taken from the vendor payable`() = withApi {
        val customer = login("+919876501061", "Reporter")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        assertEquals(HttpStatusCode.Conflict, post("/v1/orders/${order.id}/report", ReportRequest("QUALITY", "x"), customer).status, "nothing to report before payment")
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")
        assertEquals(HttpStatusCode.BadRequest, post("/v1/orders/${order.id}/report", ReportRequest("OTHER", "x"), customer).status)
        val created = post("/v1/orders/${order.id}/report", ReportRequest("QUALITY", "Rear bumper still muddy", listOf("data:image/png;base64,AAAA")), customer)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val ticket = created.body<TicketDto>()
        assertEquals("OPEN", ticket.status)
        assertTrue(get("/v1/provider/tickets", owner).body<List<TicketDto>>().any { it.id == ticket.id })

        val admin = login(ADMIN)
        assertTrue(get("/v1/admin/tickets?status=OPEN", admin).body<List<TicketDto>>().any { it.id == ticket.id })
        val payableBefore = ctx.repos.ledger.payableFor(order.id)
        val resolved = post("/v1/admin/tickets/${ticket.id}/resolve", ResolveTicketRequest(true, 10_000, "Refunded part of the wash"), admin)
        assertEquals(HttpStatusCode.OK, resolved.status, resolved.bodyAsText())
        assertEquals("UPHELD", resolved.body<TicketDto>().status)
        assertEquals(payableBefore - 10_000, ctx.repos.ledger.payableFor(order.id))
        assertEquals(10_000, ctx.repos.payments.byOrder(order.id)!!.refundedMinor)
        assertEquals(0, ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).values.sum())
        assertEquals(HttpStatusCode.Conflict, post("/v1/admin/tickets/${ticket.id}/resolve", ResolveTicketRequest(false, 0, "again"), admin).status)
        assertTrue(get("/v1/notifications", customer).body<List<NotificationDto>>().any { it.title.contains("upheld") })
    }

    @Test
    fun `review tags are stored and admin promos can be created and switched off`() = withApi {
        val customer = login("+919876501071", "Tagger")
        val detail = detail(location("Sparkle").id)
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")
        val review = post("/v1/orders/${order.id}/review", ReviewRequest(4, "Good", listOf("On time", "Spotless exterior")), customer)
        assertEquals(listOf("On time", "Spotless exterior"), review.body<ReviewDto>().tags)

        val admin = login(ADMIN)
        assertEquals(HttpStatusCode.Created, post("/v1/admin/promos", PromoDto("SAVE50", "FLAT", 5000, minSubtotalMinor = 10000, description = "₹50 off"), admin).status)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/admin/promos", PromoDto("BAD", "PERCENT", 20000), admin).status, "over 100% is refused")
        val item = detail.items.first { it.name == "Basic wash" }
        assertEquals(5000, post("/v1/quote", QuoteRequest(detail.location.id, listOf(item.id), "SEDAN", "save50"), customer).body<QuoteDto>().discountMinor)
        put("/v1/admin/promos/SAVE50/active", ActiveRequest(false), admin)
        assertEquals(HttpStatusCode.BadRequest, post("/v1/quote", QuoteRequest(detail.location.id, listOf(item.id), "SEDAN", "SAVE50"), customer).status)
    }

    // ---- vendor and admin features from the Rinse design ----

    @Test
    fun `bookings get bays, blocked bays shrink capacity and a block cannot cover a booking on its bay`() = withApi {
        val owner = login(OWNER1)
        val customer = login("+919876502001", "Bays")
        val detail = detail(location("Sparkle").id) // 3 bays
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val s = Instant.parse(start)
        val closeBays = post("/v1/provider/locations/${detail.location.id}/blocks", BlockRequest(s.toString(), s.plusSeconds(1800).toString(), "Servicing", listOf(1, 2)), owner)
        assertEquals(HttpStatusCode.Created, closeBays.status, closeBays.bodyAsText())
        val order: OrderDto = place(customer, detail, "Basic wash", start).body()
        assertEquals(3, order.bay, "only bay 3 is open")
        assertEquals(HttpStatusCode.Conflict, place(login("+919876502002", "Two"), detail, "Basic wash", start).status, "the last bay is gone")
        pay(customer, order.id)
        val clash = post("/v1/provider/locations/${detail.location.id}/blocks", BlockRequest(s.toString(), s.plusSeconds(1800).toString(), "x", listOf(3)), owner)
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ErrorBody>().message.startsWith("Bay 3 has a booking"), clash.body<ErrorBody>().message)
    }

    @Test
    fun `a daily closure repeats and shows in availability and the closures list`() = withApi {
        val owner = login(OWNER1)
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val d1 = LocalDate.now(ZoneId.of(IST)).plusDays(2)
        val from = d1.atTime(13, 0).atZone(ZoneId.of(IST)).toInstant()
        val created = post("/v1/provider/locations/${detail.location.id}/blocks", BlockRequest(from.toString(), from.plusSeconds(3600).toString(), "Lunch", null, "DAILY"), owner)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        for (offset in 0..2) {
            val day = d1.plusDays(offset.toLong())
            val slots: List<SlotDto> = get("/v1/locations/${detail.location.id}/availability?date=$day&itemIds=${item.id}").body()
            val one = slots.first { Instant.parse(it.startsAt) == day.atTime(13, 0).atZone(ZoneId.of(IST)).toInstant() }
            assertFalse(one.bookable, "lunch is closed on $day")
        }
        assertTrue(get("/v1/provider/locations/${detail.location.id}/closures", owner).body<List<BlockDto>>().any { it.repeat == "DAILY" && it.reason == "Lunch" })
    }

    @Test
    fun `add-ons use no bay time and out-of-stock services cannot be booked`() = withApi {
        val customer = login("+919876502011", "Addon")
        val detail = detail(location("Sparkle").id)
        val basic = detail.items.first { it.name == "Basic wash" }
        val under = detail.items.first { it.name == "Underbody wash" }
        val start = slot(detail.location.id, listOf(basic.id))
        val res = post("/v1/orders", PlaceOrderRequest(detail.location.id, listOf(basic.id, under.id), "SEDAN", start), customer)
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val order = res.body<OrderDto>()
        assertEquals(1800, java.time.Duration.between(Instant.parse(order.startsAt), Instant.parse(order.endsAt)).seconds, "the add-on does not hold the bay longer")
        assertEquals(2, order.lines.size)

        val bubble = detail(location("Bubble Bay").id)
        val ceramic = bubble.items.first { it.name == "Ceramic spray" }
        val basicBubble = bubble.items.first { it.name == "Basic wash" }
        val oos = post("/v1/orders", PlaceOrderRequest(bubble.location.id, listOf(basicBubble.id, ceramic.id), "SEDAN", slot(bubble.location.id, listOf(basicBubble.id))), customer)
        assertEquals("out_of_stock", oos.body<ErrorBody>().code)
    }

    @Test
    fun `a paid booking the vendor does not accept in time is declined and refunded`() = withApi {
        val owner = login(OWNER1)
        val detail = detail(location("Sparkle").id)
        put("/v1/provider/locations/${detail.location.id}", UpdateLocationRequest(autoAccept = false), owner)
        val customer = login("+919876502021", "Waiter")
        val order: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(detail.items.first { it.name == "Basic wash" }.id))).body()
        val paid: OrderDto = pay(customer, order.id).body()
        assertEquals("PAID", paid.status)
        assertNotNull(paid.acceptBy)
        assertEquals(0, ctx.orders.expireUnaccepted(), "not yet due")
        ctx.repos.orders.update(ctx.repos.orders.byId(order.id)!!.copy(updatedAt = Instant.now().minusSeconds(11 * 60)))
        assertEquals(1, ctx.orders.expireUnaccepted())
        val view: OrderDetailDto = get("/v1/orders/${order.id}", customer).body()
        assertEquals("REJECTED", view.order.status)
        assertEquals(order.totalMinor, ctx.repos.payments.byOrder(order.id)!!.refundedMinor)
        assertEquals(0, ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).values.sum())
    }

    @Test
    fun `a walk-in with a plate is checked in straight away and the owner can sign in at the lock screen with the Owner PIN`() = withApi {
        val owner = login(OWNER1)
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val res = post("/v1/provider/walk-ins", WalkInRequest(detail.location.id, listOf(item.id), "HATCHBACK", slot(detail.location.id, listOf(item.id)), "Raju", plate = "KA 53 E 2210"), owner)
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val o = res.body<OrderDto>()
        assertEquals("CHECKED_IN", o.status)
        assertEquals("KA 53 E 2210", o.assetLabel)
        assertNotNull(o.bay)
        val pinLogin = post("/v1/auth/pin", StaffLoginRequest(OWNER1, Seeder.OWNER_PIN))
        assertEquals(HttpStatusCode.OK, pinLogin.status, pinLogin.bodyAsText())
        assertEquals("PROVIDER_OWNER", pinLogin.body<AuthResponse>().user.role)
        assertEquals(HttpStatusCode.Unauthorized, post("/v1/auth/pin", StaffLoginRequest(OWNER1, "000000")).status)
    }

    @Test
    fun `owner overview, earnings with fees and the CSV statement agree with the ledger`() = withApi {
        val owner = login(OWNER1)
        val overview: com.odyssey.core.service.Overview = get("/v1/provider/overview?range=week", owner).body()
        assertTrue(overview.netMinor > 0 && overview.bookings > 0)
        assertEquals(7, overview.heatmap.size); assertEquals(13, overview.heatmap.first().size)
        assertTrue(overview.heatmap.flatten().all { it in 0..3 })

        assertEquals(HttpStatusCode.Forbidden, get("/v1/provider/earnings?range=month", owner).status, "earnings need the Owner PIN")
        val e: com.odyssey.core.service.EarningsReport = get("/v1/provider/earnings?range=month", owner, Seeder.OWNER_PIN).body()
        assertEquals(e.serviceMinor - e.commissionMinor - e.gatewayFeeMinor, e.netMinor, "net = service - commission - gateway fee")
        assertTrue(e.gatewayFeeMinor > 0 && e.commissionBps in 1000..1500)
        assertNotNull(e.bankMasked)
        val csv = get("/v1/provider/statement?range=month", owner, Seeder.OWNER_PIN).bodyAsText()
        assertTrue(csv.lines().first().startsWith("code,start,customer"))
        assertEquals(e.orders, csv.lines().size - 1)
    }

    @Test
    fun `bulk menu edits need the PIN for prices only and vendors can reply to reviews`() = withApi {
        val owner = login(OWNER1)
        val loc = get("/v1/provider/me", owner).body<ProviderDto>().locations.single()
        val items: List<ItemDto> = get("/v1/provider/locations/${loc.id}/items", owner).body()
        val tyre = items.first { it.name == "Tyre shine" }
        val noPin = put("/v1/provider/menu/bulk", BulkEditRequest(listOf(loc.id), listOf(BulkEditItem("Tyre shine", prices = mapOf("SEDAN" to 12_000L)))), owner)
        assertEquals(HttpStatusCode.Forbidden, noPin.status)
        val stock = put("/v1/provider/menu/bulk", BulkEditRequest(listOf(loc.id), listOf(BulkEditItem("Tyre shine", outOfStock = true))), owner)
        assertEquals(HttpStatusCode.OK, stock.status, stock.bodyAsText())
        assertTrue(get("/v1/provider/locations/${loc.id}/items", owner).body<List<ItemDto>>().first { it.id == tyre.id }.outOfStock)
        assertEquals(HttpStatusCode.OK, put("/v1/provider/menu/bulk", BulkEditRequest(listOf(loc.id), listOf(BulkEditItem("Tyre shine", prices = mapOf("SEDAN" to 12_000L)))), owner, Seeder.OWNER_PIN).status)

        val reviews: List<ReviewDto> = get("/v1/provider/reviews?limit=5", owner).body()
        assertTrue(reviews.isNotEmpty())
        val replied = post("/v1/provider/reviews/${reviews.first().id}/reply", ReplyRequest("Thank you, see you again"), owner)
        assertEquals("Thank you, see you again", replied.body<ReviewDto>().reply)
    }

    @Test
    fun `commission rules pick the most specific rate and previews match the design`() = withApi {
        val admin = login(ADMIN)
        val customer = login("+919876502031", "Rules")
        val shine = detail(location("Shine Street").id)
        val a: OrderDto = place(login("+919876502032", "S"), shine, "Basic wash", slot(shine.location.id, listOf(shine.items.first { it.name == "Basic wash" }.id))).body()
        assertEquals(1200, ctx.repos.orders.byId(a.id)!!.commissionBps, "vendor rule (12%) beats the legacy vendor rate")
        val bubble = detail(location("Bubble Bay").id)
        val full = bubble.items.first { it.name == "Full detailing" }
        val d = post("/v1/orders", PlaceOrderRequest(bubble.location.id, listOf(full.id), "SEDAN", slot(bubble.location.id, listOf(full.id))), customer).body<OrderDto>()
        assertEquals(1000, ctx.repos.orders.byId(d.id)!!.commissionBps, "the Detailing category rule applies")
        val basic = bubble.items.first { it.name == "Basic wash" }
        assertEquals(1500, ctx.repos.orders.byId(post("/v1/orders", PlaceOrderRequest(bubble.location.id, listOf(basic.id), "SEDAN", slot(bubble.location.id, listOf(basic.id), skip = 6)), customer).body<OrderDto>().id)!!.commissionBps, "the default applies")

        val rule = post("/v1/admin/commission-rules", RuleRequest("VENDOR", ctx.repos.providers.list(ctx.tenant("carwash").id).first { it.name.startsWith("Bubble") }.id, 800, Instant.now().plusSeconds(86400 * 30).toString()), admin)
        assertEquals(HttpStatusCode.Created, rule.status, rule.bodyAsText())
        val later: OrderDto = post("/v1/orders", PlaceOrderRequest(bubble.location.id, listOf(basic.id), "HATCHBACK", slot(bubble.location.id, listOf(basic.id), skip = 8)), login("+919876502033", "Later")).body()
        assertEquals(1500, ctx.repos.orders.byId(later.id)!!.commissionBps, "a rule that starts next month does not apply yet")
        assertEquals(4, get("/v1/admin/commission-rules", admin).body<List<RuleDto>>().size)
        val preview: com.odyssey.core.service.RulePreview = get("/v1/admin/commission-preview?serviceMinor=50000&rateBps=1200", admin).body()
        assertEquals(listOf(6000L, 650L, 43350L), listOf(preview.commissionMinor, preview.gatewayFeeMinor, preview.payoutMinor))
        assertEquals(HttpStatusCode.BadRequest, post("/v1/admin/commission-rules", RuleRequest("VENDOR", "x", 6000, Instant.now().toString()), admin).status)
    }

    @Test
    fun `payout batches skip held vendors, fail on a bad IFSC and succeed on retry`() = withApi {
        val admin = login(ADMIN)
        val owner = login(OWNER1)
        val tenant = ctx.tenant("carwash")
        val sparkle = ctx.repos.providers.list(tenant.id).first { it.name.startsWith("Sparkle") }
        var batch: com.odyssey.core.service.Batch = get("/v1/admin/payments/batch", admin).body()
        assertEquals("READY", batch.rows.first { it.providerId == sparkle.id }.status)
        assertTrue(batch.netMinor > 0 && batch.vendors >= 4)

        put("/v1/admin/providers/${sparkle.id}/hold", HoldRequest(true), admin)
        batch = get("/v1/admin/payments/batch", admin).body()
        assertEquals("HELD", batch.rows.first { it.providerId == sparkle.id }.status)
        assertTrue(postEmpty("/v1/admin/payouts/run", admin).body<List<PayoutDto>>().none { it.providerId == sparkle.id }, "held vendors are skipped")
        put("/v1/admin/providers/${sparkle.id}/hold", HoldRequest(false), admin)

        assertEquals(HttpStatusCode.OK, put("/v1/provider/bank", BankRequest("Sparkle", "123456781000", "BADIFSC"), owner, Seeder.OWNER_PIN).status)
        val failed = postEmpty("/v1/admin/payouts/run", admin).body<List<PayoutDto>>().first { it.providerId == sparkle.id }
        assertEquals("FAILED", failed.status)
        assertEquals("FAILED", get("/v1/admin/payments/batch", admin).body<com.odyssey.core.service.Batch>().rows.first { it.providerId == sparkle.id }.status)
        assertTrue(get("/v1/admin/dashboard?range=30d", admin).body<com.odyssey.core.service.Dashboard>().alerts.any { it.kind == "Payout" })

        assertEquals(HttpStatusCode.OK, put("/v1/provider/bank", BankRequest("Sparkle", "123456781000", "HDFC0000001"), owner, Seeder.OWNER_PIN).status)
        val retry = postEmpty("/v1/admin/payouts/${failed.id}/retry", admin)
        assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
        assertEquals("PROCESSED", retry.body<PayoutDto>().status)
        assertEquals(0, ctx.repos.ledger.totals(tenant.id, null, null).values.sum())
    }

    @Test
    fun `dashboard, customers, moderation and document review`() = withApi {
        val admin = login(ADMIN)
        val d: com.odyssey.core.service.Dashboard = get("/v1/admin/dashboard?range=30d", admin).body()
        assertEquals(30, d.series.size)
        assertTrue(d.gmvMinor > 0 && d.bookings > 0 && d.vendorsLive == 4 && d.vendorsInReview == 1)
        assertTrue(d.topVendors.size in 1..5 && d.topVendors.zipWithNext().all { (a, b) -> a.gmvMinor >= b.gmvMinor })
        assertTrue(d.alerts.map { it.kind }.containsAll(listOf("Support", "Vendor")))
        assertEquals(1, get("/v1/admin/dashboard?range=today", admin).body<com.odyssey.core.service.Dashboard>().series.size)

        val customers: List<com.odyssey.core.service.CustomerRow> = get("/v1/admin/customers", admin).body()
        val top = customers.first()
        assertTrue(top.bookings > 0 && top.spendMinor > 0)
        assertEquals(HttpStatusCode.NoContent, put("/v1/admin/customers/${top.id}/active", ActiveRequest(false), admin).status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/auth/verify") { contentType(ContentType.Application.Json); setBody(VerifyRequest(top.phone, "123456")) }.status, "a blocked customer cannot sign in")

        // an unusually high price shows up for review and can be approved
        val owner = login("+919000000003")
        val loc = get("/v1/provider/me", owner).body<ProviderDto>().locations.single()
        val foam = get("/v1/provider/locations/${loc.id}/items", owner).body<List<ItemDto>>().first { it.name == "Foam wash" }
        put("/v1/provider/locations/${loc.id}/items/${foam.id}", ItemRequest(foam.name, foam.description, foam.category, foam.durationMinutes, true, foam.prices + ("HATCHBACK" to 500_000L)), owner, Seeder.OWNER_PIN)
        val flagged: List<com.odyssey.core.service.OutlierRow> = get("/v1/admin/moderation/menu", admin).body()
        val hit = flagged.first { it.itemId == foam.id }
        assertEquals("Hatchback", hit.variant)
        post("/v1/admin/moderation/menu/${foam.id}", OutlierActionRequest(false), admin)
        assertTrue(get("/v1/admin/moderation/menu", admin).body<List<com.odyssey.core.service.OutlierRow>>().none { it.itemId == foam.id })

        val pending = ctx.repos.providers.list(ctx.tenant("carwash").id).first { it.name == "Gleam Garage" }
        val reviewed = post("/v1/admin/providers/${pending.id}/documents/GST", DocReviewRequest("NEEDS_FIX", "Number does not match"), admin).body<ProviderDto>()
        assertEquals("NEEDS_FIX", reviewed.documents.first { it.type == "GST" }.state)
        assertEquals(HttpStatusCode.NotFound, post("/v1/admin/providers/${pending.id}/documents/NOPE", DocReviewRequest("VERIFIED"), admin).status)
    }
}

private enum class PaymentStatusView { PARTIAL, OTHER;
    companion object { fun of(s: String) = if (s == "PARTIALLY_REFUNDED") PARTIAL else OTHER }
}
