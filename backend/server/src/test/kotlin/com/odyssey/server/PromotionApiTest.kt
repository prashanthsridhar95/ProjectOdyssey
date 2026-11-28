package com.odyssey.server

import com.odyssey.core.config.ReferralConfig
import com.odyssey.core.config.RewardTerms
import com.odyssey.core.service.Accounts
import com.odyssey.core.service.bps
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

private val zone = ZoneId.of("Asia/Kolkata")

private class Api(val ctx: AppContext, val client: HttpClient) {
    private val tokens = mutableMapOf<String, String>()
    suspend fun login(phone: String, name: String? = null): String = tokens.getOrPut(phone) {
        client.post("/v1/auth/verify") { contentType(ContentType.Application.Json); setBody(VerifyRequest(phone, ctx.config.devOtp, name)) }.body<AuthResponse>().accessToken
    }
    private fun HttpRequestBuilder.auth(token: String?, pin: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        pin?.let { header("X-Owner-Pin", it) }
    }
    suspend fun get(path: String, token: String? = null): HttpResponse = client.get(path) { auth(token, null) }
    suspend inline fun <reified T> post(path: String, body: T, token: String? = null, pin: String? = null): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body); auth(token, pin) }
    suspend inline fun <reified T> put(path: String, body: T, token: String? = null, pin: String? = null): HttpResponse =
        client.put(path) { contentType(ContentType.Application.Json); setBody(body); auth(token, pin) }
    suspend fun act(token: String, orderId: String, action: String) = post("/v1/orders/$orderId/actions", ActionRequest(action), token)

    suspend fun sparkle(): LocationDetailDto {
        val list: List<LocationSummaryDto> = get("/v1/locations?lat=12.97&lng=77.6&radiusKm=30").body()
        return get("/v1/locations/${list.first { it.name.contains("Sparkle") }.id}").body()
    }
    suspend fun slots(locationId: String, itemIds: List<String>, date: LocalDate = LocalDate.now(zone).plusDays(1)): List<SlotDto> =
        get("/v1/locations/$locationId/availability?date=$date&itemIds=${itemIds.joinToString(",")}&variant=SEDAN").body()
}

private fun withApi(block: suspend Api.() -> Unit) = testApplication {
    val ctx = AppContext(AppConfig(dbUrl = "jdbc:h2:mem:p${UUID.randomUUID().toString().replace("-", "")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"))
    Seeder(ctx).seedIfEmpty()
    application { module(ctx) }
    Api(ctx, createClient { install(ContentNegotiation) { json(appJson) } }).block()
}

private const val OWNER = "+919000000001"
private const val OTHER_OWNER = "+919000000002"
private val tomorrow get() = LocalDate.now(zone).plusDays(1)

/** A happy hour on tomorrow's weekday from 10 to 12. */
private fun happyHour(name: String = "Morning deal", pct: Long = 2000, services: List<String> = emptyList()) = PromotionRequest(
    "HAPPY_HOUR", name, "PERCENT", pct, services = services, days = listOf(tomorrow.dayOfWeek.name), startTime = "10:00", endTime = "12:00",
)

private fun hourOf(iso: String) = Instant.parse(iso).atZone(zone).hour

class PromotionApiTest {

    @Test
    fun `a vendor happy hour shows on slots, prices the booking and lowers what the vendor is paid on`() = withApi {
        val owner = login(OWNER)
        val loc = sparkle()
        val item = loc.items.first { it.name == "Basic wash" }
        val price = item.prices.getValue("SEDAN")

        assertEquals(HttpStatusCode.fromValue(403), post("/v1/provider/promotions", happyHour(), owner, "000000").status, "wrong PIN")
        val created = post("/v1/provider/promotions", happyHour(), owner, Seeder.OWNER_PIN)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val promo: PromotionDto = created.body()
        assertEquals("20% off every ${tomorrow.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} 10 am–12 pm on all services", promo.summary)
        assertEquals("LIVE", promo.status)
        assertEquals("VENDOR", promo.fundedBy)

        val slots = slots(loc.location.id, listOf(item.id))
        val inside = slots.first { it.bookable && hourOf(it.startsAt) == 10 }
        val outside = slots.first { it.bookable && hourOf(it.startsAt) >= 13 }
        assertEquals(price - bps(price, 2000), inside.priceMinor)
        assertEquals(price, inside.originalPriceMinor)
        assertEquals("20% off", inside.deal)
        assertEquals(price, outside.priceMinor)
        assertNull(outside.deal)

        val list: List<LocationSummaryDto> = get("/v1/locations?lat=12.97&lng=77.6&radiusKm=30").body()
        assertEquals("20% off · 10 am–12 pm", list.first { it.id == loc.location.id }.deal)

        val customer = login("+919811100001", "Asha")
        val q: QuoteDto = post("/v1/quote", QuoteRequest(loc.location.id, listOf(item.id), "SEDAN", null, inside.startsAt), customer).body()
        assertEquals(bps(price, 2000), q.discountMinor)
        assertEquals("Morning deal", q.discounts.single().label)
        assertEquals("Morning deal", q.promotionName)

        val order: OrderDto = post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), "SEDAN", inside.startsAt), customer).body()
        val discount = bps(price, 2000)
        assertEquals(discount, order.discountMinor)
        assertNull(order.vendorDiscountMinor, "customers do not see who funds it")
        assertEquals(price - discount + 2000 + bps(price - discount + 2000, 1800), order.totalMinor, "tax is on the discounted price")
        assertEquals(HttpStatusCode.OK, post("/v1/orders/${order.id}/pay", PayRequest(), customer).status)

        val seen: List<OrderDto> = get("/v1/provider/orders", owner).body()
        val mine = seen.single { it.id == order.id }
        assertEquals(discount, mine.vendorDiscountMinor)
        assertEquals("VENDOR", mine.discounts.single().funder)
        assertEquals(bps(price - discount, 1400), mine.commissionMinor, "commission is on what the vendor actually charged")

        // Moving it out of the happy hour is refused; moving within it is fine.
        val refused = post("/v1/orders/${order.id}/reschedule", RescheduleRequest(outside.startsAt), customer)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue(refused.bodyAsText().contains("Morning deal"))
        val within = slots.first { it.bookable && hourOf(it.startsAt) == 11 }
        assertEquals(HttpStatusCode.OK, post("/v1/orders/${order.id}/reschedule", RescheduleRequest(within.startsAt), customer).status)

        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")
        val payable = -ctx.repos.ledger.forOrder(order.id).filter { it.account == Accounts.PROVIDER_PAYABLE }.sumOf { it.amountMinor }
        val gateway = bps(price - discount, ctx.repos.tenants.bySlug("carwash")!!.config.money.gatewayFeeBps)
        assertEquals(price - discount - bps(price - discount, 1400) - gateway, payable)
        assertEquals(0, ctx.repos.ledger.forOrder(order.id).sumOf { it.amountMinor })

        // Pausing takes it off the slot picker at once.
        val paused: PromotionDto = put("/v1/provider/promotions/${promo.id}/active", ActiveRequest(false), owner).body()
        assertEquals("PAUSED", paused.status)
        assertNull(slots(loc.location.id, listOf(item.id)).first { hourOf(it.startsAt) == 10 }.deal)
        val back: List<PromotionDto> = get("/v1/provider/promotions", owner).body()
        assertEquals(1, back.single { it.id == promo.id }.bookings)
    }

    @Test
    fun `only the owner sets offers, other vendors cannot touch them, and bad input is explained`() = withApi {
        val owner = login(OWNER)
        val manager = login("+919000000101")
        assertEquals(HttpStatusCode.Forbidden, post("/v1/provider/promotions", happyHour(), manager, Seeder.OWNER_PIN).status)
        val bad = post("/v1/provider/promotions", happyHour().copy(startTime = "16:00", endTime = "14:00"), owner, Seeder.OWNER_PIN)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("end time"))
        assertEquals(HttpStatusCode.BadRequest, post("/v1/provider/promotions", happyHour(pct = 12_000), owner, Seeder.OWNER_PIN).status)

        val p: PromotionDto = post("/v1/provider/promotions", happyHour(), owner, Seeder.OWNER_PIN).body()
        assertEquals(HttpStatusCode.OK, put("/v1/provider/promotions/${p.id}/active", ActiveRequest(false), manager).status, "a manager may pause")
        assertEquals(HttpStatusCode.NotFound, put("/v1/provider/promotions/${p.id}/active", ActiveRequest(true), login(OTHER_OWNER)).status)
        val edited: PromotionDto = put("/v1/provider/promotions/${p.id}", happyHour(name = "Late morning", pct = 1500), owner, Seeder.OWNER_PIN).body()
        assertEquals("Late morning", edited.name)
        assertTrue(edited.summary.startsWith("15% off"))
    }

    @Test
    fun `a platform date discount applies everywhere and is funded by the platform`() = withApi {
        val admin = login(Seeder.ADMIN_PHONE)
        val req = PromotionRequest("DATE_DISCOUNT", "Festival day", "FLAT", 5000, startDate = tomorrow.toString())
        val created = post("/v1/admin/promotions", req, admin)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("PLATFORM", created.body<PromotionDto>().fundedBy)
        val loc = sparkle()
        val item = loc.items.first { it.name == "Basic wash" }
        val price = item.prices.getValue("SEDAN")
        val slot = slots(loc.location.id, listOf(item.id)).first { it.bookable && hourOf(it.startsAt) >= 13 }
        val customer = login("+919811100002", "Ravi")
        val order: OrderDto = post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), "SEDAN", slot.startsAt), customer).body()
        assertEquals(5000, order.discountMinor)
        assertEquals(price + 2000 + bps(price + 2000, 1800) - 5000, order.totalMinor, "platform-funded, so it comes off after tax")
        assertEquals(HttpStatusCode.OK, post("/v1/orders/${order.id}/pay", PayRequest(), customer).status)
        val seen: List<OrderDto> = get("/v1/provider/orders", login(OWNER)).body()
        val line = seen.single { it.id == order.id }.discounts.single()
        assertEquals("Festival day" to "PLATFORM", line.label to line.funder)
        assertEquals(0, seen.single { it.id == order.id }.vendorDiscountMinor)

        val all: List<PromotionDto> = get("/v1/admin/promotions", admin).body()
        assertTrue(all.any { it.name == "Festival day" && it.editable })
        assertTrue(all.any { it.providerName != null }, "vendor offers are listed for the back office")
    }

    @Test
    fun `vouchers respect who funds them, where they work, and their limits`() = withApi {
        val admin = login(Seeder.ADMIN_PHONE)
        val loc = sparkle()
        val providerId = loc.location.providerId
        val voucher = PromoDto("SPARK100", "FLAT", 10_000, minSubtotalMinor = 30_000, usageLimit = 1, perUserLimit = 1, providerId = providerId, fundedBy = "VENDOR")
        assertEquals(HttpStatusCode.BadRequest, post("/v1/admin/promos", voucher.copy(providerId = null), admin).status, "a vendor-funded code needs the vendor")
        assertEquals(HttpStatusCode.Created, post("/v1/admin/promos", voucher, admin).status)

        val item = loc.items.filter { it.prices.getValue("SEDAN") >= 30_000 }.minBy { it.prices.getValue("SEDAN") }
        val price = item.prices.getValue("SEDAN")
        val slot = slots(loc.location.id, listOf(item.id)).first { it.bookable && hourOf(it.startsAt) >= 13 }
        val first = login("+919811100003", "Meera")
        val order: OrderDto = post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), "SEDAN", slot.startsAt, promoCode = "spark100"), first).body()
        assertEquals("SPARK100", order.promoCode)
        assertEquals(10_000, order.discountMinor)
        assertEquals(price - 10_000 + 2000 + bps(price - 10_000 + 2000, 1800), order.totalMinor, "vendor-funded, so it lowers the taxed price")
        assertEquals(HttpStatusCode.OK, post("/v1/orders/${order.id}/pay", PayRequest(), first).status)

        val second = login("+919811100004", "Kiran")
        val used = post("/v1/quote", QuoteRequest(loc.location.id, listOf(item.id), "SEDAN", "SPARK100"), second)
        assertEquals(HttpStatusCode.BadRequest, used.status)
        assertTrue(used.bodyAsText().contains("fully used"))

        val elsewhere: List<LocationSummaryDto> = get("/v1/locations?lat=12.97&lng=77.6&radiusKm=30").body()
        val other: LocationDetailDto = get("/v1/locations/${elsewhere.first { it.providerId != providerId }.id}").body()
        val wrong = post("/v1/quote", QuoteRequest(other.location.id, listOf(other.items.first().id), "SEDAN", "SPARKLE50"), second)
        assertTrue(wrong.bodyAsText().contains("works only at"), wrong.bodyAsText())

        val codes: List<PromoDto> = get("/v1/admin/promos", admin).body()
        val row = codes.single { it.code == "SPARK100" }
        assertEquals(1, row.uses)
        assertEquals(10_000, row.discountGivenMinor)
        val summary: DiscountSummaryDto = get("/v1/admin/discounts/summary", admin).body()
        assertEquals(10_000, summary.vendorFundedMinor)
    }

    @Test
    fun `refer a friend gives the new customer a welcome voucher and the friend one after the first completed booking`() = withApi {
        val admin = login(Seeder.ADMIN_PHONE)
        val settings = ReferralConfig(true, RewardTerms("FLAT", 7500, null, 0, 30), RewardTerms("PERCENT", 2000, 15_000, 0, 60))
        assertEquals(HttpStatusCode.OK, put("/v1/admin/referrals/settings", settings, admin).status)

        val friend = login("+919811100010", "Zoya")
        val ref: ReferralDto = get("/v1/me/referral", friend).body()
        assertTrue(ref.code.startsWith("ZOYA"))
        assertTrue(ref.shareText.contains(ref.code))
        assertEquals("₹75 off", ref.welcomeText)
        assertEquals("20% off up to ₹150", ref.rewardText)
        val own = post("/v1/quote", QuoteRequest(sparkle().location.id, listOf(sparkle().items.first().id), "SEDAN", ref.code), friend)
        assertTrue(own.bodyAsText().contains("your own code"))

        val loc = sparkle()
        val item = loc.items.first { it.name == "Basic wash" }
        val slot = slots(loc.location.id, listOf(item.id)).first { it.bookable && hourOf(it.startsAt) >= 13 }
        val newbie = login("+919811100011", "Imran")
        val q: QuoteDto = post("/v1/quote", QuoteRequest(loc.location.id, listOf(item.id), "SEDAN", ref.code.lowercase()), newbie).body()
        assertEquals(7500, q.discountMinor)
        assertEquals("REFERRAL", q.discounts.single().kind)

        val order: OrderDto = post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), "SEDAN", slot.startsAt, promoCode = ref.code), newbie).body()
        assertEquals(7500, order.discountMinor)
        assertTrue(order.promoCode!!.startsWith("W"), "the friend's code became the customer's own welcome voucher")
        assertEquals(HttpStatusCode.OK, post("/v1/orders/${order.id}/pay", PayRequest(), newbie).status)
        assertTrue(post("/v1/me/referral/redeem", RedeemRequest(ref.code), newbie).bodyAsText().contains("already used"))

        val beforeFriend: List<VoucherDto> = get("/v1/me/vouchers", friend).body()
        assertTrue(beforeFriend.isEmpty())
        val owner = login(OWNER)
        act(owner, order.id, "START"); act(owner, order.id, "COMPLETE")

        val rewards: List<VoucherDto> = get("/v1/me/vouchers", friend).body()
        val reward = rewards.single()
        assertEquals("REFERRAL_REWARD", reward.source)
        assertEquals(2000, reward.value)
        val stats: ReferralDto = get("/v1/me/referral", friend).body()
        assertEquals(1, stats.rewarded)

        // The friend's reward is a personal voucher: nobody else can use it.
        val stranger = login("+919811100012", "Dev")
        assertTrue(post("/v1/quote", QuoteRequest(loc.location.id, listOf(item.id), "SEDAN", reward.code), stranger).bodyAsText().contains("not valid"))
        val use: QuoteDto = post("/v1/quote", QuoteRequest(loc.location.id, listOf(item.id), "SEDAN", reward.code), friend).body()
        assertEquals(minOf(bps(item.prices.getValue("SEDAN"), 2000), 15_000), use.discountMinor)

        val overview: ReferralOverviewDto = get("/v1/admin/referrals", admin).body()
        assertEquals(1, overview.referrals)
        assertEquals(1, overview.rewarded)
        assertEquals(1, overview.welcomeVouchersUsed)
        assertEquals(7500, overview.discountGivenMinor)
        assertEquals("Zoya", overview.recent.single().referrerName)
    }

    @Test
    fun `a customer who already booked cannot use a friend's code`() = withApi {
        val friend = login("+919811100020", "Neha")
        val code = get("/v1/me/referral", friend).body<ReferralDto>().code
        val loc = sparkle()
        val item = loc.items.first { it.name == "Basic wash" }
        val slot = slots(loc.location.id, listOf(item.id)).first { it.bookable && hourOf(it.startsAt) >= 13 }
        val regular = login("+919811100021", "Arjun")
        val order: OrderDto = post("/v1/orders", PlaceOrderRequest(loc.location.id, listOf(item.id), "SEDAN", slot.startsAt), regular).body()
        post("/v1/orders/${order.id}/pay", PayRequest(), regular)
        val res = post("/v1/me/referral/redeem", RedeemRequest(code), regular)
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("first booking"))
    }
}
