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
        assertEquals(4, detail.items.size)
        val basic = detail.items.first { it.name == "Basic wash" }
        val start = slot(spot.id, listOf(basic.id))

        val placed = place(customer, detail, "Basic wash", start)
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val order: OrderDto = placed.body()
        assertEquals("PENDING_PAYMENT", order.status)
        val price = basic.prices.getValue("SEDAN")
        assertEquals(price, order.subtotalMinor)
        assertEquals(price + price * 18 / 100, order.totalMinor, "18% tax on the service amount")
        assertNull(order.commissionBps, "customers never see commission")

        val paid: OrderDto = pay(customer, order.id).also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.body()
        assertEquals("CONFIRMED", paid.status, "auto-accept confirms straight after payment")

        val owner = login(OWNER1)
        val inbox: List<OrderDto> = get("/v1/provider/orders", owner).body()
        val mine = inbox.single { it.id == order.id }
        assertEquals(1500, mine.commissionBps)
        assertTrue("START" in mine.actions && "NO_SHOW" in mine.actions)

        assertEquals("IN_PROGRESS", act(owner, order.id, "START").body<OrderDto>().status)
        assertEquals("COMPLETED", act(owner, order.id, "COMPLETE").body<OrderDto>().status)

        // the customer can now review once
        assertEquals(HttpStatusCode.Created, post("/v1/orders/${order.id}/review", ReviewRequest(5, "Spotless"), customer).status)
        assertEquals(HttpStatusCode.Conflict, post("/v1/orders/${order.id}/review", ReviewRequest(4, "again"), customer).status)

        // ledger balances and the commission is 15% of the service amount
        val totals = ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null)
        assertEquals(0, totals.values.sum(), "every ledger transaction balances")
        assertEquals(-(price * 15 / 100), totals.getValue("tenant_commission"))
    }

    @Test
    fun `payout covers settled orders minus commission and only once`() = withApi {
        val customer = login("+919876500002", "Ravi")
        val detail = detail(location("Sparkle").id)
        val start = slot(detail.location.id, listOf(detail.items.first { it.name == "Foam wash" }.id))
        val order: OrderDto = place(customer, detail, "Foam wash", start).body()
        pay(customer, order.id)
        val owner = login(OWNER1)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")

        val admin = login(ADMIN)
        val run: List<PayoutDto> = postEmpty("/v1/admin/payouts/run", admin).body()
        val payout = run.single()
        val sub = detail.items.first { it.name == "Foam wash" }.prices.getValue("SEDAN")
        assertEquals(sub - sub * 15 / 100, payout.amountMinor)
        assertEquals(emptyList(), postEmpty("/v1/admin/payouts/run", admin).body<List<PayoutDto>>(), "second run pays nothing")
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
        val detail = detail(location("Bubble Bay").id)
        val item = detail.items.first { it.name == "Basic wash" }
        val start = slot(detail.location.id, listOf(item.id))
        val req = WalkInRequest(detail.location.id, listOf(item.id), "HATCHBACK", start, "Walk-in Raju")
        repeat(2) { assertEquals(HttpStatusCode.Created, post("/v1/provider/walk-ins", req, owner).status) }
        assertEquals(HttpStatusCode.Conflict, post("/v1/provider/walk-ins", req, owner).status, "both bays are taken")
        assertTrue(ctx.repos.ledger.totals(ctx.tenant("carwash").id, null, null).isEmpty())
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
        val customer = login("+919876500081", "Billing")
        val detail = detail(location("Sparkle").id)
        val item = detail.items.first { it.name == "Basic wash" }
        repeat(3) { i ->
            val o: OrderDto = place(customer, detail, "Basic wash", slot(detail.location.id, listOf(item.id), skip = i * 2)).body()
            pay(customer, o.id)
        }
        val statement: com.odyssey.core.service.BillingStatement = get("/v1/admin/billing", admin).body()
        assertEquals(3, statement.orders)
        assertEquals(2, statement.overageOrders)
        val sub = item.prices.getValue("SEDAN")
        assertEquals(2 * (sub * 2 / 100), statement.perOrderChargeMinor)
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
}

private enum class PaymentStatusView { PARTIAL, OTHER;
    companion object { fun of(s: String) = if (s == "PARTIALLY_REFUNDED") PARTIAL else OTHER }
}
