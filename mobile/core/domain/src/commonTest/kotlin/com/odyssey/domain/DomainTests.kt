package com.odyssey.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    @Test fun moneyUsesIndianGrouping() {
        assertEquals("₹299", formatMoney(29900))
        assertEquals("₹299.50", formatMoney(29950))
        assertEquals("₹1,23,456", formatMoney(12345600))
        assertEquals("-", formatMoney(null))
    }

    @Test fun percentFromBasisPoints() {
        assertEquals("15%", formatPercent(1500))
        assertEquals("12.5%", formatPercent(1250))
    }

    @Test fun timeIsShownInIndiaTime() {
        assertEquals("10:00 am", formatTime("2030-01-07T04:30:00Z"))
        assertEquals("12:30 pm", formatTime("2030-01-07T07:00:00Z"))
        assertEquals("Mon, 7 Jan", formatDay("2030-01-07T04:30:00Z"))
    }

    @Test fun phoneNumbersGetACountryCode() {
        assertEquals("+919876543210", normalizePhone("98765 43210"))
        assertEquals("+919876543210", normalizePhone("+91 98765-43210"))
        assertTrue(isValidPhone("9876543210"))
        assertTrue(!isValidPhone("12345"))
    }
}

class CheckoutTest {
    private val item = { id: String, price: Long, minutes: Int -> Item(id, id, durationMinutes = minutes, prices = mapOf("SEDAN" to price)) }

    @Test fun addsTaxAndFeeToTheServiceAmount() {
        val c = BuildCheckout()(listOf(item("a", 30000, 30), item("b", 20000, 15)), "SEDAN", MoneyConfig(taxBps = 1800, customerFeeMinor = 500))!!
        assertEquals(50000, c.subtotalMinor)
        assertEquals(9090, c.taxMinor, "18% of service plus fee")
        assertEquals(59590, c.totalMinor)
        assertEquals(45, c.durationMinutes)
    }

    @Test fun refusesAnItemNotOfferedForTheVehicleType() {
        assertNull(BuildCheckout()(listOf(item("a", 100, 10)), "SUV", MoneyConfig()))
        assertNull(BuildCheckout()(emptyList(), "SEDAN", MoneyConfig()))
    }
}

class ProgressTest {
    private fun ev(to: String, at: String) = OrderEvent(null, to, "X", null, at)

    @Test fun marksStepsReachedAndTheCurrentOne() {
        val steps = progressSteps(listOf(ev("CONFIRMED", "a"), ev("CHECKED_IN", "b")), "CHECKED_IN", emptyMap())
        assertEquals(listOf(true, true, false, false), steps.map { it.done })
        assertEquals(listOf(false, true, false, false), steps.map { it.current })
        assertEquals("b", steps[1].at)
    }

    @Test fun codeIsSplitForReading() = assertEquals("RN 4K7Q", displayCode("RN4K7Q"))
}

class RefundPreviewTest {
    private val order = Order(id = "1", code = "X", status = "CONFIRMED", statusLabel = "Confirmed", providerId = "p", locationId = "l", locationName = "L", variantKey = "SEDAN",
        startsAt = "", endsAt = "", subtotalMinor = 100000, totalMinor = 118000)
    private val policy = CancellationPolicy(fullRefundHoursBefore = 2, lateRetainBps = 5000)

    @Test fun fullRefundWhenEarly() = assertEquals(118000, refundPreview(order, policy, 5.0))
    @Test fun halfRefundWhenLate() = assertEquals(59000, refundPreview(order, policy, 1.0))
}

class SlotGroupingTest {
    private fun slot(iso: String) = Slot(iso, iso, 1, true)

    @Test fun groupsByPartOfDayInIndiaTime() {
        val grouped = groupSlots(listOf(slot("2030-01-07T03:30:00Z"), slot("2030-01-07T08:00:00Z"), slot("2030-01-07T12:30:00Z")))
        assertEquals(listOf(DayPart.MORNING, DayPart.AFTERNOON, DayPart.EVENING), grouped.keys.toList())
    }
}

class ActionQueueTest {
    private class Mem : KeyValueStore { val m = mutableMapOf<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value } }

    @Test fun queuedActionsSurviveAndSendInOrder() = kotlinx.coroutines.test.runTest {
        val kv = Mem()
        val q = ActionQueue(kv)
        q.enqueue(PendingAction("a", "CHECK_IN")); q.enqueue(PendingAction("a", "START"))
        val reopened = ActionQueue(kv)   // a new instance reads what the old one saved
        val sent = mutableListOf<String>()
        assertEquals(2, reopened.flush { sent += it.action })
        assertEquals(listOf("CHECK_IN", "START"), sent)
        assertTrue(reopened.pending().isEmpty())
    }

    @Test fun stopsAtANetworkFailureAndKeepsTheRest() = kotlinx.coroutines.test.runTest {
        val q = ActionQueue(Mem())
        q.enqueue(PendingAction("a", "CHECK_IN")); q.enqueue(PendingAction("b", "START"))
        assertEquals(0, q.flush { throw ApiException("network", "offline") })
        assertEquals(2, q.pending().size)
    }

    @Test fun dropsAnActionTheServerRefusesForGood() = kotlinx.coroutines.test.runTest {
        val q = ActionQueue(Mem())
        q.enqueue(PendingAction("a", "COMPLETE")); q.enqueue(PendingAction("b", "START"))
        val sent = mutableListOf<String>()
        q.flush { if (it.orderId == "a") throw ApiException("invalid_transition", "no", 409) else sent += it.orderId }
        assertEquals(listOf("b"), sent)
        assertTrue(q.pending().isEmpty())
    }
}
