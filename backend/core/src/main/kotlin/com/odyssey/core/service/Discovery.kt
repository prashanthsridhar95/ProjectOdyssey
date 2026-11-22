package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

enum class Sort { DISTANCE, RATING, PRICE, EARLIEST }

data class SearchQuery(
    val lat: Double, val lng: Double, val radiusKm: Double = 15.0, val sort: Sort = Sort.DISTANCE,
    val text: String? = null, val variantKey: String? = null, val maxPriceMinor: Long? = null,
    val openNow: Boolean = false, val amenities: Set<String> = emptySet(),
    val services: Set<String> = emptySet(), val minRating: Double = 0.0, val minPriceMinor: Long? = null,
)

data class LocationSummary(
    val location: Location, val providerName: String, val distanceKm: Double, val priceFromMinor: Long?,
    val nextFreeAt: Instant?, val openNow: Boolean,
)

data class LocationDetail(val summary: LocationSummary, val items: List<CatalogItem>, val reviews: List<Review>)

fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Nearby search. Uses plain distance maths for the POC; swap for PostGIS when the data grows. */
class Discovery(private val r: Repos, private val availability: SlotAvailability, private val clock: Clock) {

    fun search(tenant: Tenant, q: SearchQuery): List<LocationSummary> {
        val now = clock.now()
        val variant = q.variantKey ?: tenant.config.variants.options.firstOrNull()?.key
        val providers = r.providers.list(tenant.id, ProviderStatus.LIVE).associateBy { it.id }
        return r.locations.live(tenant.id)
            .filter { providers.containsKey(it.providerId) }
            .map { it to haversineKm(q.lat, q.lng, it.lat, it.lng) }
            .filter { (_, d) -> d <= q.radiusKm }
            .filter { (l, _) -> q.text.isNullOrBlank() || l.name.contains(q.text, true) || l.address.contains(q.text, true) || providers[l.providerId]!!.name.contains(q.text, true) }
            .filter { (l, _) -> q.amenities.all { it in l.amenities } }
            .filter { (l, _) -> l.ratingAvg >= q.minRating }
            .mapNotNull { (l, d) -> summarize(tenant, l, providers[l.providerId]!!.name, d, variant, now, q.services) }
            .filter { !q.openNow || it.openNow }
            .filter { q.maxPriceMinor == null || (it.priceFromMinor != null && it.priceFromMinor <= q.maxPriceMinor) }
            .filter { q.minPriceMinor == null || (it.priceFromMinor != null && it.priceFromMinor >= q.minPriceMinor) }
            .let { list ->
                when (q.sort) {
                    Sort.DISTANCE -> list.sortedBy { it.distanceKm }
                    Sort.RATING -> list.sortedByDescending { it.location.ratingAvg }
                    Sort.PRICE -> list.sortedBy { it.priceFromMinor ?: Long.MAX_VALUE }
                    Sort.EARLIEST -> list.sortedBy { it.nextFreeAt ?: Instant.MAX }
                }
            }
    }

    fun detail(tenant: Tenant, locationId: Id, lat: Double?, lng: Double?, variantKey: String?): LocationDetail {
        val location = r.locations.byId(locationId)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Location")
        val provider = r.providers.byId(location.providerId)?.takeIf { it.status == ProviderStatus.LIVE } ?: throw notFound("Location")
        val distance = if (lat != null && lng != null) haversineKm(lat, lng, location.lat, location.lng) else 0.0
        val variant = variantKey ?: tenant.config.variants.options.firstOrNull()?.key
        val summary = summarize(tenant, location, provider.name, distance, variant, clock.now())!!
        return LocationDetail(summary, r.catalog.byLocation(location.id, activeOnly = true), r.reviews.byLocation(location.id, 20))
    }

    /** Null when the place does not offer every requested service. */
    private fun summarize(tenant: Tenant, l: Location, providerName: String, distance: Double, variant: String?, now: Instant, services: Set<String> = emptySet()): LocationSummary? {
        val all = r.catalog.byLocation(l.id, activeOnly = true)
        val wanted = services.map { it.lowercase() }.toSet()
        val items = if (wanted.isEmpty()) all else all.filter { it.name.lowercase() in wanted }
        if (wanted.isNotEmpty() && items.map { it.name.lowercase() }.toSet() != wanted) return null
        val priced = if (variant == null) emptyList() else items.filter { it.prices.containsKey(variant) }
        val from = priced.minOfOrNull { it.prices[variant]!! }
        val shortest = priced.minOfOrNull { it.durationMinutes } ?: items.minOfOrNull { it.durationMinutes } ?: l.slotMinutes
        val next = availability.nextFree(tenant, l, shortest, now)
        val zone = ZoneId.of(l.timezone)
        val local = now.atZone(zone).toLocalTime()
        val open = l.open && !local.isBefore(l.openTime) && local.isBefore(l.closeTime)
        return LocationSummary(l, providerName, Math.round(distance * 10) / 10.0, from, next, open)
    }
}
