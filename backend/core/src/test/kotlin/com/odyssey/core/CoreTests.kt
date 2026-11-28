package com.odyssey.core

import com.odyssey.core.config.*
import com.odyssey.core.domain.*
import com.odyssey.core.ports.BlockRepo
import com.odyssey.core.ports.OrderRepo
import com.odyssey.core.service.Pricing
import com.odyssey.core.service.SlotAvailability
import com.odyssey.core.service.bps
import java.io.File
import java.time.*
import kotlin.test.*

private val flow = OrderFlow(
    initial = "PENDING", paid = "PAID", accepted = "OK", completed = "DONE",
    states = listOf(StateDef("PENDING", occupiesCapacity = true), StateDef("OK", occupiesCapacity = true), StateDef("DONE", occupiesCapacity = false, terminal = true)),
    transitions = listOf(TransitionDef(listOf("PENDING"), "ACCEPT", "OK", setOf(Role.PROVIDER_OWNER))),
)

private val config = TenantConfig(
    labels = Labels("Provider", "Location", "Order", "Item"),
    variants = VariantDimension("Size", listOf(VariantOption("S", "Small"))),
    asset = null, flow = flow,
)
private val tenant = Tenant("t1", "demo", "Demo", "test", config)
private val zone = ZoneId.of("Asia/Kolkata")
private val location = Location("l1", "t1", "p1", "Spot", "Street", 12.9, 77.6, capacity = 2, slotMinutes = 30, openTime = LocalTime.of(9, 0), closeTime = LocalTime.of(12, 0))
private val now = LocalDate.of(2030, 1, 7).atTime(6, 0).atZone(zone).toInstant()
private val day = LocalDate.of(2030, 1, 7)

private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant()

private fun order(start: Instant, end: Instant) = Order(
    "o${start}", "t1", "C", "c1", "A", "1", "p1", "l1", "Spot", null, "S", "OK", OrderSource.APP, start, end, emptyList(),
    100, 0, 0, 100, 1000, 10, null, createdAt = now, updatedAt = now,
)

private class Orders(val list: List<Order>) : OrderRepo {
    override fun occupying(locationId: Id, from: Instant, to: Instant, states: List<String>, now: Instant) =
        list.filter { it.status in states && it.startsAt < to && it.endsAt > from }
    override fun byId(id: Id) = TODO()
    override fun insert(order: Order) = TODO()
    override fun update(order: Order) = TODO()
    override fun addEvent(event: OrderEvent) = TODO()
    override fun events(orderId: Id) = TODO()
    override fun byCustomer(customerId: Id) = TODO()
    override fun byProvider(providerId: Id, locationId: Id?, status: String?, from: Instant?, to: Instant?) = TODO()
    override fun all(tenantId: Id, status: String?, from: Instant?, to: Instant?, limit: Int) = TODO()
    override fun expiredHolds(now: Instant, state: String) = TODO()
    override fun settledUnpaid(providerId: Id) = TODO()
    override fun paidOrders(tenantId: Id, from: Instant, to: Instant, unpaidStates: List<String>) = TODO()
    override fun byCode(tenantId: Id, code: String, providerId: Id?) = TODO()
    override fun countPromoUses(tenantId: Id, code: String, userId: Id?) = TODO()
    override fun dueForReminder(from: Instant, to: Instant, states: List<String>) = TODO()
    override fun staleIn(state: String, before: Instant) = TODO()
    override fun discounted(tenantId: Id) = TODO()
}

private class Blocks(val list: List<Block> = emptyList()) : BlockRepo {
    override fun overlapping(locationId: Id, from: Instant, to: Instant) = list.filter { it.startsAt < to && it.endsAt > from }
    override fun insert(block: Block) = TODO()
    override fun delete(id: Id, locationId: Id) = TODO()
}

class SlotAvailabilityTest {
    @Test
    fun `free capacity is capacity minus overlapping orders`() {
        val engine = SlotAvailability(Orders(listOf(order(at(9), at(9, 30)))), Blocks())
        val slots = engine.slots(tenant, location, day, 30, now)
        assertEquals(6, slots.size)
        assertEquals(1, slots[0].free)
        assertEquals(2, slots[1].free)
    }

    @Test
    fun `slot is unbookable when capacity is used up`() {
        val engine = SlotAvailability(Orders(listOf(order(at(9), at(9, 30)), order(at(9), at(9, 30)))), Blocks())
        assertFalse(engine.slots(tenant, location, day, 30, now)[0].bookable)
    }

    @Test
    fun `multi slot booking needs every slot free`() {
        val engine = SlotAvailability(Orders(listOf(order(at(9, 30), at(10)), order(at(9, 30), at(10)))), Blocks())
        val slots = engine.slots(tenant, location, day, 60, now)
        assertFalse(slots[0].bookable, "9:00 needs 9:00 and 9:30, and 9:30 is full")
        assertTrue(slots[2].bookable)
    }

    @Test
    fun `last slots that would run past closing are not bookable`() {
        val engine = SlotAvailability(Orders(emptyList()), Blocks())
        val slots = engine.slots(tenant, location, day, 60, now)
        assertFalse(slots.last().bookable)
        assertTrue(slots[slots.size - 2].bookable)
    }

    @Test
    fun `a block closes the slot`() {
        val block = Block("b", "t1", "l1", at(10), at(10, 30), "lunch", "u")
        val engine = SlotAvailability(Orders(emptyList()), Blocks(listOf(block)))
        val slots = engine.slots(tenant, location, day, 30, now)
        assertFalse(slots.first { it.startsAt == at(10) }.bookable)
        assertTrue(slots.first { it.startsAt == at(10, 30) }.bookable)
    }

    @Test
    fun `lead time hides slots that are too soon`() {
        val engine = SlotAvailability(Orders(emptyList()), Blocks())
        val closeToNow = at(9).minusSeconds(60 * 10)
        val slots = engine.slots(tenant, location, day, 30, closeToNow)
        assertFalse(slots[0].bookable, "9:00 is only 10 minutes away and lead time is 30")
        assertTrue(slots[2].bookable)
    }
}

class PricingTest {
    @Test
    fun `commission is on the service amount only`() {
        val q = Pricing.quote(config.copy(money = MoneyConfig(taxBps = 1800, customerFeeMinor = 1000, defaultCommissionBps = 1500)), null, 100_000)
        assertEquals(18_180, q.taxMinor, "tax covers the service plus the fee")
        assertEquals(119_180, q.totalMinor)
        assertEquals(15_000, q.commissionMinor)
    }

    @Test
    fun `a promo reduces what the customer pays after tax and never below zero`() {
        val cfg = config.copy(money = MoneyConfig(taxBps = 1800, customerFeeMinor = 2000, defaultCommissionBps = 1500))
        val q = Pricing.quote(cfg, null, 59_800, discountMinor = 10_000)
        assertEquals(11_124, q.taxMinor)
        assertEquals(62_924, q.totalMinor)
        assertEquals(10_000, q.discountMinor)
        assertEquals(0, Pricing.quote(cfg, null, 100, discountMinor = 1_000_000).totalMinor)
    }

    @Test
    fun `basis points round to the nearest minor unit`() {
        assertEquals(15, bps(100, 1500))
        assertEquals(1, bps(33, 300))
    }
}

class OrderFlowTest {
    @Test
    fun `transitions are looked up by state and action`() {
        assertNotNull(flow.transition("PENDING", "ACCEPT"))
        assertNull(flow.transition("DONE", "ACCEPT"))
    }

    @Test
    fun `only roles on the transition may use it`() {
        assertEquals(1, flow.actionsFor("PENDING", Role.PROVIDER_OWNER).size)
        assertEquals(0, flow.actionsFor("PENDING", Role.CUSTOMER).size)
    }
}

/** The framework core must never learn about web, databases or any single vertical. */
class ArchitectureTest {
    @Test
    fun `core sources import no framework or vertical code`() {
        val root = File("src/main/kotlin")
        val forbidden = listOf("io.ktor", "org.jetbrains.exposed", "java.sql", "javax.sql", "com.odyssey.pack", "com.odyssey.server")
        val vertical = listOf(Regex("\\bcar\\b", RegexOption.IGNORE_CASE), Regex("\\bvehicle\\b", RegexOption.IGNORE_CASE), Regex("carwash", RegexOption.IGNORE_CASE))
        val offences = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                when {
                    forbidden.any { trimmed.startsWith("import $it") } -> "${file.name}:${i + 1} $trimmed"
                    !isComment && vertical.any { it.containsMatchIn(line) } -> "${file.name}:${i + 1} $trimmed"
                    else -> null
                }
            }
        }.toList()
        assertTrue(offences.isEmpty(), "Core leaks: $offences")
    }
}
