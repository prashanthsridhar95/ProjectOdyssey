package com.odyssey.server

import com.odyssey.core.domain.*
import com.odyssey.pack.carwash.CarWashPack
import com.odyssey.server.wiring.AppContext
import org.slf4j.LoggerFactory
import java.time.LocalTime

/** Demo data for the proof of concept: one tenant (car wash), an admin, and a few live vendors around Bengaluru. */
class Seeder(private val ctx: AppContext) {
    private val log = LoggerFactory.getLogger("seed")
    private val r = ctx.repos

    companion object {
        const val ADMIN_PHONE = "+910000000001"
        const val OWNER_PIN = "123456"
        const val STAFF_PIN = "1234"
    }

    private data class Vendor(val name: String, val ownerPhone: String, val outlet: String, val address: String, val lat: Double, val lng: Double,
                              val open: String, val close: String, val bays: Int, val amenities: List<String>, val rating: Double, val reviews: Int, val priceFactor: Double)

    private val vendors = listOf(
        Vendor("Sparkle Auto Spa", "+919000000001", "Sparkle Indiranagar", "100 Feet Road, Indiranagar, Bengaluru", 12.9784, 77.6408, "08:00", "20:00", 3, listOf("Waiting lounge", "Covered bay", "Free WiFi"), 4.6, 128, 1.0),
        Vendor("Bubble Bay Wash", "+919000000002", "Bubble Bay Koramangala", "5th Block, Koramangala, Bengaluru", 12.9352, 77.6245, "07:00", "21:00", 2, listOf("Parking", "Covered bay"), 4.3, 74, 0.9),
        Vendor("Shine Street Detailers", "+919000000003", "Shine Street HSR", "Sector 2, HSR Layout, Bengaluru", 12.9116, 77.6389, "09:00", "19:00", 2, listOf("Waiting lounge", "Parking"), 4.8, 211, 1.3),
        Vendor("Whitefield Quick Wash", "+919000000004", "Quick Wash Whitefield", "ITPL Main Road, Whitefield, Bengaluru", 12.9698, 77.7500, "08:00", "20:00", 4, listOf("Parking", "Free WiFi"), 4.1, 39, 0.8),
    )

    // Base prices in paise, by vehicle type, before the vendor's price factor.
    private val menu = listOf(
        Triple("Basic wash", "Wash", 30) to mapOf("HATCHBACK" to 29900L, "SEDAN" to 34900L, "SUV" to 44900L, "BIKE" to 14900L),
        Triple("Foam wash", "Wash", 45) to mapOf("HATCHBACK" to 49900L, "SEDAN" to 59900L, "SUV" to 74900L, "BIKE" to 24900L),
        Triple("Interior vacuum", "Interior", 30) to mapOf("HATCHBACK" to 24900L, "SEDAN" to 29900L, "SUV" to 39900L),
        Triple("Full detailing", "Detailing", 120) to mapOf("HATCHBACK" to 249900L, "SEDAN" to 299900L, "SUV" to 399900L),
    )

    fun seedIfEmpty() {
        if (r.tenants.bySlug("carwash") != null) return
        log.info("Seeding demo data")
        val tenant = Tenant(ctx.ids.newId(), "carwash", "Rinse", CarWashPack.VERTICAL, CarWashPack.config)
        r.tenants.insert(tenant)
        r.users.insert(User(ctx.ids.newId(), tenant.id, ADMIN_PHONE, "Platform Admin", Role.ADMIN))
        val now = ctx.clock.now()

        vendors.forEachIndexed { index, v ->
            val owner = User(ctx.ids.newId(), tenant.id, v.ownerPhone, "${v.name} Owner", Role.PROVIDER_OWNER, pinHash = ctx.pins.hash(OWNER_PIN))
            r.users.insert(owner)
            val provider = Provider(
                ctx.ids.newId(), tenant.id, v.name, owner.id, ProviderStatus.LIVE, commissionBps = if (index == 2) 1800 else null,
                documents = listOf(ProviderDocument("GST", "GSTIN-DEMO-$index"), ProviderDocument("TRADE_LICENCE", "TL-DEMO-$index")),
                bank = BankDetails(v.name, "•••• ${1000 + index}", "HDFC0000001"), createdAt = now,
            )
            r.providers.insert(provider)
            r.users.update(owner.copy(providerId = provider.id))
            val location = Location(
                ctx.ids.newId(), tenant.id, provider.id, v.outlet, v.address, v.lat, v.lng, openTime = LocalTime.parse(v.open), closeTime = LocalTime.parse(v.close),
                capacity = v.bays, amenities = v.amenities, ratingAvg = v.rating, ratingCount = v.reviews, phone = "+9180${4000000 + index * 1111}",
            )
            r.locations.insert(location)
            menu.forEach { (info, prices) ->
                val (name, category, minutes) = info
                r.catalog.insert(
                    CatalogItem(ctx.ids.newId(), tenant.id, location.id, name, CarWashPack.config.serviceTemplates.firstOrNull { it.name == name }?.description ?: "", category, minutes, true,
                        prices.mapValues { (Math.round(it.value * v.priceFactor / 100.0) * 100) }),
                )
            }
            r.users.insert(User(ctx.ids.newId(), tenant.id, "+91900000010${index + 1}", "${v.name} Manager", Role.PROVIDER_MANAGER, provider.id, ctx.pins.hash(STAFF_PIN)))
            r.users.insert(User(ctx.ids.newId(), tenant.id, "+91900000020${index + 1}", "${v.name} Counter", Role.PROVIDER_STAFF, provider.id, ctx.pins.hash(STAFF_PIN)))
        }
        r.promos.save(PromoCode(tenant.id, "FIRSTWASH", PromoKind.FLAT, 10000, null, 30000, null, null, null, 1, true, "₹100 off your first wash"))
        r.promos.save(PromoCode(tenant.id, "WEEKEND10", PromoKind.PERCENT, 1000, 15000, 0, null, null, null, 5, true, "10% off, up to ₹150"))
        // One vendor waiting for review so the admin approval flow has something to show.
        val pendingOwner = User(ctx.ids.newId(), tenant.id, "+919000000099", "Gleam Garage Owner", Role.PROVIDER_OWNER)
        r.users.insert(pendingOwner)
        val pending = Provider(ctx.ids.newId(), tenant.id, "Gleam Garage", pendingOwner.id, ProviderStatus.SUBMITTED, documents = listOf(ProviderDocument("GST", "GSTIN-DEMO-NEW")), createdAt = now)
        r.providers.insert(pending)
        r.users.update(pendingOwner.copy(providerId = pending.id))
        r.locations.insert(Location(ctx.ids.newId(), tenant.id, pending.id, "Gleam Jayanagar", "4th Block, Jayanagar, Bengaluru", 12.9250, 77.5938))

        log.info("Demo logins (OTP ${ctx.config.devOtp}): admin $ADMIN_PHONE, vendor owners +919000000001..4 (Owner PIN $OWNER_PIN), counter staff +919000000201.. (PIN $STAFF_PIN)")
    }
}
