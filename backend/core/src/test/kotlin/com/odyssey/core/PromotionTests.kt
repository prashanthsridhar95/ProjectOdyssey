package com.odyssey.core

import com.odyssey.core.config.*
import com.odyssey.core.domain.*
import com.odyssey.core.ports.Clock
import com.odyssey.core.ports.Ids
import com.odyssey.core.ports.LedgerRepo
import com.odyssey.core.service.*
import java.time.DayOfWeek.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.*

private val now = Instant.parse("2026-11-30T06:00:00Z")
// 30 Nov 2026 is a Monday.
private val monday = LocalDate.of(2026, 11, 30)

private fun promo(
    kind: PromoKind = PromoKind.PERCENT, value: Long = 2000, type: PromotionType = PromotionType.HAPPY_HOUR,
    days: List<java.time.DayOfWeek> = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), start: LocalTime? = LocalTime.of(14, 0), end: LocalTime? = LocalTime.of(16, 0),
    startDate: LocalDate? = null, endDate: LocalDate? = null, services: List<String> = emptyList(), variants: List<String> = emptyList(),
    max: Long? = null, providerId: Id? = "p1", locations: List<Id> = emptyList(), active: Boolean = true, name: String = "Happy hour",
) = Promotion(
    "x-$name-$value", "t", providerId, type, name, kind, value, max, locations, services, variants, days, start, end, startDate, endDate, active, "test", now,
)

private val baseConfig = TenantConfig(
    labels = Labels("Vendor", "Outlet", "Booking", "Service"),
    variants = VariantDimension("Vehicle type", listOf(VariantOption("SEDAN", "Sedan"), VariantOption("SUV", "SUV"))),
    asset = null,
    flow = OrderFlow(initial = "P", paid = "PAID", accepted = "OK", completed = "DONE", states = listOf(StateDef("P", occupiesCapacity = true)), transitions = emptyList()),
)

private fun line(name: String, price: Long, category: String = "Wash") = OrderLine("id-$name", name, price, 30, 1, category)

class PromotionScheduleTest {
    private val p = promo()

    @Test
    fun `happy hour covers its weekdays from the start time up to but not including the end time`() {
        assertTrue(PromotionEngine.appliesAt(p, "l1", monday.atTime(14, 0)))
        assertTrue(PromotionEngine.appliesAt(p, "l1", monday.atTime(15, 59)))
        assertFalse(PromotionEngine.appliesAt(p, "l1", monday.atTime(16, 0)), "the end time is exclusive")
        assertFalse(PromotionEngine.appliesAt(p, "l1", monday.atTime(13, 30)))
        assertFalse(PromotionEngine.appliesAt(p, "l1", monday.plusDays(4).atTime(14, 30)), "Friday is not chosen")
    }

    @Test
    fun `paused offers do not apply, unless only the schedule is being checked`() {
        val paused = p.copy(active = false)
        assertFalse(PromotionEngine.appliesAt(paused, "l1", monday.atTime(14, 30)))
        assertTrue(PromotionEngine.appliesAt(paused, "l1", monday.atTime(14, 30), ignoreActive = true))
    }

    @Test
    fun `a date range includes both ends and outlets can be limited`() {
        val d = promo(type = PromotionType.DATE_DISCOUNT, days = emptyList(), start = null, end = null, startDate = monday, endDate = monday.plusDays(2), locations = listOf("l1"))
        assertTrue(PromotionEngine.appliesAt(d, "l1", monday.atTime(8, 0)))
        assertTrue(PromotionEngine.appliesAt(d, "l1", monday.plusDays(2).atTime(19, 30)))
        assertFalse(PromotionEngine.appliesAt(d, "l1", monday.plusDays(3).atTime(8, 0)))
        assertFalse(PromotionEngine.appliesAt(d, "l1", monday.minusDays(1).atTime(8, 0)))
        assertFalse(PromotionEngine.appliesAt(d, "l2", monday.atTime(8, 0)), "another outlet")
    }
}

class PromotionAmountTest {
    private val lines = listOf(line("Foam wash", 59_900), line("Tyre shine", 9_900, "Add-on"))

    @Test
    fun `percent off applies per line and only to chosen services and vehicle types`() {
        assertEquals(listOf(11_980L, 1_980L), PromotionEngine.lineDiscounts(promo(), lines, "SEDAN"))
        assertEquals(listOf(11_980L, 0L), PromotionEngine.lineDiscounts(promo(services = listOf("foam wash")), lines, "SEDAN"), "service names match without case")
        assertEquals(listOf(0L, 0L), PromotionEngine.lineDiscounts(promo(variants = listOf("SUV")), lines, "SEDAN"))
    }

    @Test
    fun `flat off is shared across eligible lines and never exceeds them`() {
        val flat = PromotionEngine.lineDiscounts(promo(PromoKind.FLAT, 10_000), lines, "SEDAN")
        assertEquals(10_000L, flat.sum())
        assertTrue(flat[0] > flat[1])
        assertEquals(listOf(9_900L), PromotionEngine.lineDiscounts(promo(PromoKind.FLAT, 50_000), listOf(line("Tyre shine", 9_900)), "SEDAN"), "never below zero")
    }

    @Test
    fun `a special price takes each chosen service down to that price`() {
        assertEquals(listOf(20_000L, 0L), PromotionEngine.lineDiscounts(promo(PromoKind.PRICE, 39_900), lines, "SEDAN"), "the cheaper add-on is already below the price")
    }

    @Test
    fun `the maximum discount caps the total`() {
        val capped = PromotionEngine.lineDiscounts(promo(PromoKind.PERCENT, 5000, max = 10_000), lines, "SEDAN")
        assertEquals(10_000L, capped.sum())
    }

    @Test
    fun `the best single offer wins and offers do not stack`() {
        val small = promo(value = 1000, name = "Ten")
        val big = promo(value = 2500, name = "Quarter")
        val closed = promo(value = 9000, name = "Morning", start = LocalTime.of(8, 0), end = LocalTime.of(9, 0))
        val best = PromotionEngine.best(listOf(small, big, closed), "l1", lines, "SEDAN", monday.atTime(14, 30))!!
        assertEquals("Quarter", best.promotion.name)
        assertEquals(PromotionEngine.lineDiscounts(big, lines, "SEDAN").sum(), best.totalMinor)
        assertNull(PromotionEngine.best(listOf(small, big), "l1", lines, "SEDAN", monday.atTime(17, 0)))
    }

    @Test
    fun `allocation keeps every unit`() {
        val parts = PromotionEngine.allocate(1001, listOf(3, 3, 3))
        assertEquals(1001L.coerceAtMost(9), parts.sum())
        assertEquals(100L, PromotionEngine.allocate(100, listOf(333, 333, 334)).sum())
    }
}

class PromotionWordsTest {
    @Test
    fun `sentences read the way a vendor would say them`() {
        assertEquals("20% off every Mon–Thu 2–4 pm on all services", PromotionEngine.summary(promo()))
        assertEquals("20% off every weekday 2–4 pm on all services", PromotionEngine.summary(promo(days = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY))))
        assertEquals("₹50 off every weekend 11 am–1 pm on Foam wash and Interior clean",
            PromotionEngine.summary(promo(PromoKind.FLAT, 5000, days = listOf(SATURDAY, SUNDAY), start = LocalTime.of(11, 0), end = LocalTime.of(13, 0), services = listOf("Foam wash", "Interior clean"))))
        assertEquals("₹150 off on 25 Dec on all services",
            PromotionEngine.summary(promo(PromoKind.FLAT, 15_000, PromotionType.DATE_DISCOUNT, emptyList(), null, null, LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 25))))
        assertEquals("15% off from 10 Oct to 15 Oct on all services (up to ₹200)",
            PromotionEngine.summary(promo(PromoKind.PERCENT, 1500, PromotionType.DATE_DISCOUNT, emptyList(), null, null, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 15), max = 20_000)))
        assertEquals("12.5% off every day 2:30–4 pm on all services", PromotionEngine.summary(promo(value = 1250, days = emptyList(), start = LocalTime.of(14, 30))))
        assertEquals("20% off · 2–4 pm", PromotionEngine.badge(promo()))
    }

    @Test
    fun `vehicle types are named from the vertical`() {
        assertTrue(PromotionEngine.summary(promo(variants = listOf("SUV", "SEDAN")), baseConfig).endsWith("for SUV and Sedan"))
    }
}

class DiscountMoneyTest {
    private val cfg = baseConfig.copy(money = MoneyConfig(taxBps = 1800, customerFeeMinor = 2000, defaultCommissionBps = 1500, gatewayFeeBps = 200))

    @Test
    fun `a vendor-funded discount lowers tax and commission, a platform one only what the customer pays`() {
        val vendor = Pricing.quote(cfg, null, 100_000, vendorDiscountMinor = 20_000)
        assertEquals(bps(80_000 + 2000, 1800), vendor.taxMinor)
        assertEquals(12_000, vendor.commissionMinor, "15% of what the vendor actually charges")
        assertEquals(80_000 + 2000 + vendor.taxMinor, vendor.totalMinor)
        assertEquals(20_000, vendor.discountMinor)
        assertEquals(20_000, vendor.vendorDiscountMinor)

        val platform = Pricing.quote(cfg, null, 100_000, discountMinor = 20_000)
        assertEquals(bps(102_000, 1800), platform.taxMinor)
        assertEquals(15_000, platform.commissionMinor)
        assertEquals(100_000 + 2000 + platform.taxMinor - 20_000, platform.totalMinor)
        assertEquals(0, platform.vendorDiscountMinor)
    }

    private class Captured : LedgerRepo {
        val entries = mutableListOf<LedgerEntry>()
        override fun post(entries: List<LedgerEntry>) { this.entries += entries }
        override fun payableFor(orderId: Id) = TODO()
        override fun totals(tenantId: Id, from: Instant?, to: Instant?) = TODO()
        override fun forOrder(orderId: Id) = TODO()
        override fun sumByAccount(orderIds: List<Id>) = TODO()
        override fun recent(tenantId: Id, limit: Int) = TODO()
    }

    private val ids = object : Ids { var n = 0; override fun newId() = "id${n++}"; override fun newCode() = "CODE${n++}" }
    private val clock = object : Clock { override fun now() = now }

    @Test
    fun `settling splits each discount to whoever funded it and balances`() {
        // ₹1,000 of service: the vendor's happy hour takes ₹200 off, then a platform voucher ₹100.
        val q = Pricing.quote(cfg, null, 100_000, discountMinor = 10_000, vendorDiscountMinor = 20_000)
        val order = Order(
            "o1", "t", "C", "u", "Asha", "+91", "p1", "l1", "Outlet", null, "SEDAN", "IN_PROGRESS", OrderSource.APP, now, now,
            listOf(line("Foam wash", 100_000)), q.subtotalMinor, q.taxMinor, q.feeMinor, q.totalMinor, q.commissionBps, q.commissionMinor, null,
            createdAt = now, updatedAt = now, discountMinor = q.discountMinor, vendorDiscountMinor = q.vendorDiscountMinor,
        )
        assertEquals(30_000, order.discountMinor)
        assertEquals(10_000, order.platformDiscountMinor)
        val repo = Captured()
        val gatewayFee = Ledger(repo, ids, clock).settled(order, 200)
        assertEquals(bps(80_000, 200), gatewayFee, "the gateway fee is on what the vendor charged")
        val by = repo.entries.groupBy { it.account }.mapValues { (_, e) -> e.sumOf { it.amountMinor } }
        assertEquals(0, repo.entries.sumOf { it.amountMinor })
        assertEquals(-(80_000 - 12_000 - gatewayFee), by[Accounts.PROVIDER_PAYABLE], "the vendor earns its discounted price less commission and the gateway fee")
        assertEquals(-(12_000 - 10_000L), by[Accounts.TENANT_COMMISSION], "the platform voucher comes out of commission")
        assertEquals(order.providerNetMinor - gatewayFee, -by[Accounts.PROVIDER_PAYABLE]!!)
    }
}

class SlotTimeTest {
    @Test
    fun `local time is what decides the window`() {
        val p = promo()
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val slot = monday.atTime(14, 0).atZone(zone).toInstant()
        assertTrue(PromotionEngine.appliesAt(p, "l1", LocalDateTime.ofInstant(slot, zone)))
        assertFalse(PromotionEngine.appliesAt(p, "l1", LocalDateTime.ofInstant(slot, java.time.ZoneOffset.UTC)), "08:30 UTC is outside 2–4 pm")
    }
}
