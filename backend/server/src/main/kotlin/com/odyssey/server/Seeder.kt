package com.odyssey.server

import com.odyssey.core.domain.*
import com.odyssey.core.config.*
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
    // Base prices in paise by vehicle type, before the vendor's price factor. Add-ons use no bay: they run alongside the main service.
    private data class Svc(val name: String, val category: String, val minutes: Int, val bays: Int, val prices: Map<String, Long>, val description: String = "")

    private val menu = listOf(
        Svc("Basic wash", "Exterior", 30, 1, mapOf("HATCHBACK" to 29900L, "SEDAN" to 34900L, "SUV" to 44900L, "BIKE" to 14900L), "Exterior rinse and foam wash"),
        Svc("Foam wash", "Exterior", 45, 1, mapOf("HATCHBACK" to 49900L, "SEDAN" to 59900L, "SUV" to 74900L, "BIKE" to 24900L), "Foam wash with tyre and rim clean"),
        Svc("Wax and polish", "Exterior", 60, 1, mapOf("HATCHBACK" to 79900L, "SEDAN" to 89900L, "SUV" to 109900L), "Machine polish and carnauba wax"),
        Svc("Interior vacuum", "Interior", 30, 1, mapOf("HATCHBACK" to 24900L, "SEDAN" to 29900L, "SUV" to 39900L), "Seats, mats and boot vacuum"),
        Svc("Full detailing", "Detailing", 120, 1, mapOf("HATCHBACK" to 249900L, "SEDAN" to 299900L, "SUV" to 399900L), "Full exterior and interior detailing"),
        Svc("Underbody wash", "Add-on", 15, 0, mapOf("HATCHBACK" to 14900L, "SEDAN" to 14900L, "SUV" to 19900L)),
        Svc("Tyre shine", "Add-on", 10, 0, mapOf("HATCHBACK" to 9900L, "SEDAN" to 9900L, "SUV" to 12900L, "BIKE" to 4900L)),
        Svc("Ceramic spray", "Add-on", 20, 0, mapOf("HATCHBACK" to 44900L, "SEDAN" to 49900L, "SUV" to 59900L)),
        Svc("Air freshener", "Add-on", 5, 0, mapOf("HATCHBACK" to 4900L, "SEDAN" to 4900L, "SUV" to 4900L)),
    )

    fun seedIfEmpty() {
        if (r.tenants.bySlug("carwash") != null) return
        log.info("Seeding demo data")
        val tenant = Tenant(ctx.ids.newId(), "carwash", "Rinse", CarWashPack.VERTICAL, CarWashPack.config)
        r.tenants.insert(tenant)
        r.users.insert(User(ctx.ids.newId(), tenant.id, ADMIN_PHONE, "Platform Admin", Role.ADMIN))
        val now = ctx.clock.now()

        val locations = mutableListOf<Pair<Location, Provider>>()
        vendors.forEachIndexed { index, v ->
            val owner = User(ctx.ids.newId(), tenant.id, v.ownerPhone, "${v.name} Owner", Role.PROVIDER_OWNER, pinHash = ctx.pins.hash(OWNER_PIN))
            r.users.insert(owner)
            val provider = Provider(
                ctx.ids.newId(), tenant.id, v.name, owner.id, ProviderStatus.LIVE, commissionBps = if (index == 2) 1800 else null,
                documents = listOf(ProviderDocument("GST", "GSTIN-DEMO-$index", DocState.VERIFIED), ProviderDocument("TRADE_LICENCE", "TL-DEMO-$index", DocState.VERIFIED), ProviderDocument("BANK", "HDFC ••${1000 + index}", DocState.VERIFIED)),
                bank = BankDetails(v.name, "•••• ${1000 + index}", "HDFC0000001"), createdAt = now,
            )
            r.providers.insert(provider)
            r.users.update(owner.copy(providerId = provider.id))
            val location = Location(
                ctx.ids.newId(), tenant.id, provider.id, v.outlet, v.address, v.lat, v.lng, openTime = LocalTime.parse(v.open), closeTime = LocalTime.parse(v.close),
                capacity = v.bays, amenities = v.amenities, ratingAvg = v.rating, ratingCount = v.reviews, phone = "+9180${4000000 + index * 1111}",
            )
            r.locations.insert(location)
            menu.forEach { svc ->
                r.catalog.insert(
                    CatalogItem(ctx.ids.newId(), tenant.id, location.id, svc.name, svc.description, svc.category, svc.minutes, true,
                        svc.prices.mapValues { (Math.round(it.value * v.priceFactor / 100.0) * 100) }, svc.bays, outOfStock = svc.name == "Ceramic spray" && index == 1),
                )
            }
            locations += location to provider
            r.users.insert(User(ctx.ids.newId(), tenant.id, "+91900000010${index + 1}", "${v.name} Manager", Role.PROVIDER_MANAGER, provider.id, ctx.pins.hash(STAFF_PIN)))
            r.users.insert(User(ctx.ids.newId(), tenant.id, "+91900000020${index + 1}", "${v.name} Counter", Role.PROVIDER_STAFF, provider.id, ctx.pins.hash(STAFF_PIN)))
        }
        r.promos.save(PromoCode(tenant.id, "FIRSTWASH", PromoKind.FLAT, 10000, null, 30000, null, null, null, 1, true, "₹100 off your first wash"))
        r.promos.save(PromoCode(tenant.id, "WEEKEND10", PromoKind.PERCENT, 1000, 15000, 0, null, null, null, 5, true, "10% off, up to ₹150"))
        // One vendor waiting for review so the admin approval flow has something to show.
        val pendingOwner = User(ctx.ids.newId(), tenant.id, "+919000000099", "Gleam Garage Owner", Role.PROVIDER_OWNER)
        r.users.insert(pendingOwner)
        val pending = Provider(ctx.ids.newId(), tenant.id, "Gleam Garage", pendingOwner.id, ProviderStatus.SUBMITTED, documents = listOf(ProviderDocument("GST", "29ABCPA1234F1Z5", DocState.VERIFIED), ProviderDocument("TRADE_LICENCE", "BBMP-8841", DocState.NEEDS_FIX, "Image is blurred. Please upload a clear scan."), ProviderDocument("BANK", "ICICI ••2209", DocState.VERIFIED), ProviderDocument("PAN", "ABCPA1234F", DocState.VERIFIED)), createdAt = now.minusSeconds(2 * 86400))
        r.providers.insert(pending)
        r.users.update(pendingOwner.copy(providerId = pending.id))
        r.locations.insert(Location(ctx.ids.newId(), tenant.id, pending.id, "Gleam Jayanagar", "4th Block, Jayanagar, Bengaluru", 12.9250, 77.5938))

        val admin = Actor(null, Role.ADMIN, tenant.id, name = "Priya M.")
        locations.firstOrNull { it.first.name.contains("Indiranagar") }?.let { (l, _) -> r.rules.insert(CommissionRule(ctx.ids.newId(), tenant.id, RuleScope.OUTLET, l.id, "Sparkle Auto Spa · ${l.name}", 1400, now.minusSeconds(21 * 86400), null, "Priya M.", now)) }
        locations.firstOrNull { it.second.name.startsWith("Shine") }?.let { (_, p) -> r.rules.insert(CommissionRule(ctx.ids.newId(), tenant.id, RuleScope.VENDOR, p.id, p.name + " (all outlets)", 1200, now.minusSeconds(40 * 86400), now.plusSeconds(70 * 86400), "Rohit S.", now)) }
        r.rules.insert(CommissionRule(ctx.ids.newId(), tenant.id, RuleScope.CATEGORY, "Detailing", "Detailing and polish", 1000, now.minusSeconds(10 * 86400), null, "Priya M.", now))
        seedHistory(tenant, locations, now)
        log.info("Demo logins (OTP ${ctx.config.devOtp}): admin $ADMIN_PHONE, vendor owners +919000000001..4 (Owner PIN $OWNER_PIN), counter staff +919000000201.. (PIN $STAFF_PIN)")
    }

    /** Four weeks of past bookings with proper ledger entries, so dashboards, heatmaps, payouts and reviews have something real to show. */
    private fun seedHistory(tenant: Tenant, locations: List<Pair<Location, Provider>>, now: java.time.Instant) {
        val rnd = java.util.Random(42)
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val today = java.time.LocalDate.now(zone)
        val names = listOf("Asha", "Ravi", "Meera", "Kiran", "Neha", "Dev", "Zoya", "Imran", "Priya", "Karthik", "Sneha", "Arjun")
        val plates = listOf("KA 03 MN 4821", "KA 51 AB 9902", "KA 05 MK 0047", "KA 04 MP 3021", "KA 02 ZZ 6501", "KA 01 RT 8830", "KA 53 E 2210", "KA 05 JT 1188")
        val variants = tenant.config.variants.options.map { it.key }
        val customers = names.mapIndexed { i, n -> User(ctx.ids.newId(), tenant.id, "+9198000000${10 + i}", n, Role.CUSTOMER).also { r.users.insert(it) } }
        var orderCount = 0
        locations.forEach { (loc, provider) ->
            val items = r.catalog.byLocation(loc.id, activeOnly = true).filter { it.baysUsed > 0 }
            val perDay = 3 + loc.capacity * 2
            for (back in 1..28) {
                val day = today.minusDays(back.toLong())
                val weekend = day.dayOfWeek.value >= 6
                repeat(if (weekend) perDay + 3 else perDay) {
                    val item = items[rnd.nextInt(items.size)]
                    val variant = variants.first { item.prices.containsKey(it) }.let { v -> variants.filter { item.prices.containsKey(it) }.let { it[rnd.nextInt(it.size)] } }
                    val hour = loc.openTime.hour + rnd.nextInt(maxOf(1, loc.closeTime.hour - loc.openTime.hour - 2))
                    val start = day.atTime(hour, if (rnd.nextBoolean()) 0 else 30).atZone(zone).toInstant()
                    val end = start.plusSeconds(60L * maxOf(item.durationMinutes, loc.slotMinutes))
                    val price = item.prices.getValue(variant)
                    val customer = customers[rnd.nextInt(customers.size)]
                    val roll = rnd.nextInt(100)
                    val status = when { roll < 82 -> "COMPLETED"; roll < 90 -> "CANCELLED"; roll < 95 -> "NO_SHOW"; else -> "REJECTED" }
                    val quote = com.odyssey.core.service.Pricing.quote(tenant.config, provider, price, 0, com.odyssey.core.service.bps(price, provider.commissionBps ?: tenant.config.money.defaultCommissionBps))
                    var order = Order(
                        ctx.ids.newId(), tenant.id, ctx.ids.newCode(), customer.id, customer.name, customer.phone, provider.id, loc.id, loc.name, plates[rnd.nextInt(plates.size)], variant, status, OrderSource.APP,
                        start, end, listOf(OrderLine(item.id, item.name, price, item.durationMinutes, item.baysUsed, item.category)), quote.subtotalMinor, quote.taxMinor, quote.feeMinor, quote.totalMinor,
                        quote.commissionBps, quote.commissionMinor, null, if (status == "CANCELLED" || status == "REJECTED") "Seed data" else null, false, null, start.minusSeconds(86400), end, bay = 1 + rnd.nextInt(loc.capacity),
                    )
                    r.payments.insert(Payment(ctx.ids.newId(), tenant.id, order.id, order.totalMinor, if (status == "CANCELLED" || status == "REJECTED") order.totalMinor else 0, if (status == "CANCELLED" || status == "REJECTED") PaymentStatus.REFUNDED else PaymentStatus.CAPTURED, "UPI", "pay_seed_$orderCount", start.minusSeconds(86400)))
                    ctx.ledger.paymentCaptured(order)
                    when (status) {
                        "COMPLETED", "NO_SHOW" -> { ctx.ledger.settled(order, tenant.config.money.gatewayFeeBps); order = order.copy(settled = true) }
                        else -> ctx.ledger.refundedFull(order)
                    }
                    r.orders.insert(order)
                    r.orders.addEvent(OrderEvent(order.id, null, "CONFIRMED", "ACCEPT", null, null, order.createdAt))
                    if (status == "COMPLETED" && rnd.nextInt(100) < 35) r.reviews.insert(Review(ctx.ids.newId(), tenant.id, order.id, loc.id, customer.name, 4 + rnd.nextInt(2), listOf("Quick and clean", "Spotless", "On time", "Good value", "Missed a spot on the bumper")[rnd.nextInt(5)], end, listOf("On time", "Friendly staff").take(rnd.nextInt(3))))
                    orderCount++
                }
            }
        }
        // A problem report waiting for the back office.
        r.orders.all(tenant.id, "COMPLETED", null, null, 1).firstOrNull()?.let { o ->
            r.tickets.insert(Ticket(ctx.ids.newId(), tenant.id, o.id, o.customerId!!, o.providerId, "QUALITY", "Rear bumper and wheel arches still muddy.", emptyList(), TicketStatus.OPEN, null, 0, ctx.clock.now().minusSeconds(30 * 3600)))
        }
        log.info("Seeded $orderCount historical bookings")
    }
}
