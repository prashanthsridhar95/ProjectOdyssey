package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.BlockRepo
import com.odyssey.core.ports.OrderRepo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stretch of closed time. [bays] null means every bay is closed. */
data class Window(val start: Instant, val end: Instant, val bays: Set<Int>?, val reason: String = "", val repeat: Repeat = Repeat.NONE)

object BlockWindows {
    /** Turns a block, repeating or not, into the concrete windows that touch [from, to). Repeats recur at the same local time. */
    fun expand(b: Block, from: Instant, to: Instant, zone: ZoneId): List<Window> {
        val bays = b.bays?.toSet()
        if (b.repeat == Repeat.NONE) return if (b.startsAt < to && b.endsAt > from) listOf(Window(b.startsAt, b.endsAt, bays, b.reason, b.repeat)) else emptyList()
        val startLocal = b.startsAt.atZone(zone)
        val length = Duration.between(b.startsAt, b.endsAt)
        var day: LocalDate = maxOf(startLocal.toLocalDate(), from.atZone(zone).toLocalDate().minusDays(1))
        val last = to.atZone(zone).toLocalDate()
        val out = mutableListOf<Window>()
        while (!day.isAfter(last)) {
            if (b.repeat == Repeat.DAILY || day.dayOfWeek == startLocal.dayOfWeek) {
                val s = day.atTime(startLocal.toLocalTime()).atZone(zone).toInstant()
                if (s < to && s.plus(length) > from) out += Window(s, s.plus(length), bays, b.reason, b.repeat)
            }
            day = day.plusDays(1)
        }
        return out
    }
}

/**
 * Availability strategy: time slots with a capacity (bays, chairs, tables...).
 * Free capacity of a slot = capacity - closed bays - orders holding it. A block with no bays listed closes the slot entirely.
 * A booking that needs several consecutive slots is bookable only when all of them have capacity.
 */
class SlotAvailability(private val orders: OrderRepo, private val blocks: BlockRepo) {

    fun slotsNeeded(location: Location, durationMinutes: Int): Int =
        maxOf(1, Math.ceilDiv(durationMinutes, location.slotMinutes))

    fun windows(location: Location, from: Instant, to: Instant): List<Window> {
        val zone = ZoneId.of(location.timezone)
        return blocks.overlapping(location.id, from, to).flatMap { BlockWindows.expand(it, from, to, zone) }
    }

    fun slots(tenant: Tenant, location: Location, date: LocalDate, durationMinutes: Int, now: Instant, excludeOrderId: Id? = null): List<Slot> {
        val zone = ZoneId.of(location.timezone)
        val step = Duration.ofMinutes(location.slotMinutes.toLong())
        val opens = date.atTime(location.openTime).atZone(zone).toInstant()
        val closes = date.atTime(location.closeTime).atZone(zone).toInstant()
        if (!location.open || !closes.isAfter(opens)) return emptyList()

        val needed = slotsNeeded(location, durationMinutes)
        val occupying = orders.occupying(location.id, opens, closes, tenant.config.flow.occupying(), now).filter { it.id != excludeOrderId }
        val closed = windows(location, opens, closes)

        fun freeAt(start: Instant): Int {
            val end = start.plus(step)
            val ws = closed.filter { it.start < end && it.end > start }
            if (ws.any { it.bays == null }) return 0
            val blockedBays = ws.flatMap { it.bays!! }.toSet().size
            val used = occupying.count { it.startsAt < end && it.endsAt > start }
            return maxOf(0, location.capacity - blockedBays - used)
        }

        val cfg = tenant.config.availability
        val earliest = now.plus(Duration.ofMinutes(cfg.leadMinutes.toLong()))
        val latest = now.plus(Duration.ofDays(cfg.windowDays.toLong()))
        val result = mutableListOf<Slot>()
        var start = opens
        while (start < closes) {
            val end = start.plus(step.multipliedBy(needed.toLong()))
            val fits = end <= closes
            var free = if (fits) Int.MAX_VALUE else 0
            if (fits) {
                for (i in 0 until needed) free = minOf(free, freeAt(start.plus(step.multipliedBy(i.toLong()))))
            }
            val bookable = fits && free > 0 && start >= earliest && start <= latest
            result += Slot(start, start.plus(step), if (fits) free else 0, bookable)
            start = start.plus(step)
        }
        return result
    }

    /** Throws when the requested interval cannot be booked. Call inside a unit of work holding the location lock. */
    fun ensureBookable(tenant: Tenant, location: Location, startsAt: Instant, durationMinutes: Int, now: Instant, excludeOrderId: Id? = null) {
        val zone = ZoneId.of(location.timezone)
        val date = startsAt.atZone(zone).toLocalDate()
        val slot = slots(tenant, location, date, durationMinutes, now, excludeOrderId).firstOrNull { it.startsAt == startsAt }
            ?: throw conflict("slot_unavailable", "That time is not a valid slot")
        if (!slot.bookable) throw conflict("slot_unavailable", "That slot is no longer available")
    }

    /** The lowest-numbered bay that is free for the whole interval, or null when none is. Pass [prefer] to ask for a specific bay. */
    fun assignBay(tenant: Tenant, location: Location, startsAt: Instant, endsAt: Instant, now: Instant, excludeOrderId: Id? = null, prefer: Int? = null): Int? {
        val taken = orders.occupying(location.id, startsAt, endsAt, tenant.config.flow.occupying(), now)
            .filter { it.id != excludeOrderId }.mapNotNull { it.bay }.toSet()
        val closed = windows(location, startsAt, endsAt)
        if (closed.any { it.bays == null }) return null
        val blocked = closed.flatMap { it.bays!! }.toSet()
        val free = (1..location.capacity).filter { it !in taken && it !in blocked }
        return if (prefer != null && prefer in free) prefer else free.firstOrNull()
    }

    /** First bookable slot start within the booking window, or null. */
    fun nextFree(tenant: Tenant, location: Location, durationMinutes: Int, now: Instant): Instant? {
        val zone = ZoneId.of(location.timezone)
        val today = now.atZone(zone).toLocalDate()
        for (d in 0..tenant.config.availability.windowDays) {
            val hit = slots(tenant, location, today.plusDays(d.toLong()), durationMinutes, now).firstOrNull { it.bookable }
            if (hit != null) return hit.startsAt
        }
        return null
    }
}
