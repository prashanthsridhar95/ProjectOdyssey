package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.BlockRepo
import com.odyssey.core.ports.OrderRepo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Availability strategy: time slots with a capacity (bays, chairs, tables...).
 * Free capacity of a slot = capacity - orders holding it. A block closes the slot entirely.
 * A booking that needs several consecutive slots is bookable only when all of them have capacity.
 */
class SlotAvailability(private val orders: OrderRepo, private val blocks: BlockRepo) {

    fun slotsNeeded(location: Location, durationMinutes: Int): Int =
        maxOf(1, Math.ceilDiv(durationMinutes, location.slotMinutes))

    fun slots(tenant: Tenant, location: Location, date: LocalDate, durationMinutes: Int, now: Instant): List<Slot> {
        val zone = ZoneId.of(location.timezone)
        val step = Duration.ofMinutes(location.slotMinutes.toLong())
        val opens = date.atTime(location.openTime).atZone(zone).toInstant()
        val closes = date.atTime(location.closeTime).atZone(zone).toInstant()
        if (!location.open || !closes.isAfter(opens)) return emptyList()

        val needed = slotsNeeded(location, durationMinutes)
        val occupying = orders.occupying(location.id, opens, closes, tenant.config.flow.occupying(), now)
        val closed = blocks.overlapping(location.id, opens, closes)

        fun freeAt(start: Instant): Int {
            val end = start.plus(step)
            if (closed.any { it.startsAt < end && it.endsAt > start }) return 0
            val used = occupying.count { it.startsAt < end && it.endsAt > start }
            return maxOf(0, location.capacity - used)
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
    fun ensureBookable(tenant: Tenant, location: Location, startsAt: Instant, durationMinutes: Int, now: Instant) {
        val zone = ZoneId.of(location.timezone)
        val date = startsAt.atZone(zone).toLocalDate()
        val slot = slots(tenant, location, date, durationMinutes, now).firstOrNull { it.startsAt == startsAt }
            ?: throw conflict("slot_unavailable", "That time is not a valid slot")
        if (!slot.bookable) throw conflict("slot_unavailable", "That slot is no longer available")
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
