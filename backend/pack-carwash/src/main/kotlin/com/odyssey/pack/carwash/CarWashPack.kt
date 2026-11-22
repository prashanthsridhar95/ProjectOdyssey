package com.odyssey.pack.carwash

import com.odyssey.core.config.*
import com.odyssey.core.domain.Role

/**
 * The car wash vertical pack: pure configuration over the core framework.
 * Words, vehicle types, the order lifecycle, service templates and policies all live here.
 */
object CarWashPack {
    const val VERTICAL = "carwash"

    private val owners = setOf(Role.PROVIDER_OWNER, Role.PROVIDER_MANAGER)
    private val counter = setOf(Role.PROVIDER_OWNER, Role.PROVIDER_MANAGER, Role.PROVIDER_STAFF)
    private val backoffice = setOf(Role.ADMIN, Role.SUPPORT)

    val flow = OrderFlow(
        initial = "PENDING_PAYMENT",
        paid = "PAID",
        accepted = "CONFIRMED",
        completed = "COMPLETED",
        states = listOf(
            StateDef("PENDING_PAYMENT", "Awaiting payment", occupiesCapacity = true),
            StateDef("EXPIRED", "Expired", occupiesCapacity = false, terminal = true),
            StateDef("PAID", "Paid, awaiting vendor", occupiesCapacity = true),
            StateDef("CONFIRMED", "Confirmed", occupiesCapacity = true),
            StateDef("IN_PROGRESS", "Wash in progress", occupiesCapacity = true),
            StateDef("COMPLETED", "Completed", occupiesCapacity = false, terminal = true),
            StateDef("REJECTED", "Rejected by vendor", occupiesCapacity = false, terminal = true),
            StateDef("CANCELLED", "Cancelled", occupiesCapacity = false, terminal = true),
            StateDef("NO_SHOW", "No-show", occupiesCapacity = false, terminal = true),
        ),
        transitions = listOf(
            TransitionDef(listOf("PENDING_PAYMENT"), "PAYMENT_CAPTURED", "PAID", setOf(Role.SYSTEM), system = true),
            TransitionDef(listOf("PENDING_PAYMENT"), "EXPIRE", "EXPIRED", setOf(Role.SYSTEM), system = true),
            TransitionDef(listOf("PENDING_PAYMENT"), "ABANDON", "EXPIRED", setOf(Role.CUSTOMER)),
            TransitionDef(listOf("PAID"), "ACCEPT", "CONFIRMED", owners + Role.SYSTEM),
            TransitionDef(listOf("PAID"), "REJECT", "REJECTED", owners + backoffice, effect = Effect.REFUND_FULL),
            TransitionDef(listOf("PAID", "CONFIRMED"), "CANCEL_BY_CUSTOMER", "CANCELLED", setOf(Role.CUSTOMER), effect = Effect.REFUND_BY_POLICY),
            TransitionDef(listOf("CONFIRMED"), "CANCEL_BY_PROVIDER", "CANCELLED", owners, effect = Effect.REFUND_FULL),
            TransitionDef(listOf("PAID", "CONFIRMED", "IN_PROGRESS"), "ADMIN_CANCEL", "CANCELLED", backoffice, effect = Effect.REFUND_FULL),
            TransitionDef(listOf("CONFIRMED"), "START", "IN_PROGRESS", counter),
            TransitionDef(listOf("IN_PROGRESS"), "COMPLETE", "COMPLETED", counter, effect = Effect.SETTLE),
            TransitionDef(listOf("CONFIRMED"), "NO_SHOW", "NO_SHOW", counter, effect = Effect.SETTLE_NO_SHOW),
        ),
    )

    val config = TenantConfig(
        labels = Labels(provider = "Vendor", location = "Outlet", order = "Booking", item = "Service", customer = "Customer"),
        variants = VariantDimension(
            "Vehicle type",
            listOf(
                VariantOption("HATCHBACK", "Hatchback"),
                VariantOption("SEDAN", "Sedan"),
                VariantOption("SUV", "SUV"),
                VariantOption("BIKE", "Bike"),
            ),
        ),
        asset = AssetKind(
            kind = "VEHICLE", label = "Vehicle", labelPlural = "Garage",
            fields = listOf(
                AssetField("vehicleType", "Vehicle type", options = listOf("HATCHBACK", "SEDAN", "SUV", "BIKE")),
                AssetField("plate", "Number plate"),
            ),
        ),
        flow = flow,
        availability = AvailabilityConfig(leadMinutes = 30, windowDays = 7, holdMinutes = 5),
        money = MoneyConfig(currency = "INR", taxBps = 1800, customerFeeMinor = 0, defaultCommissionBps = 1500),
        cancellation = CancellationPolicy(fullRefundHoursBefore = 2, lateRetainBps = 5000),
        billing = BillingConfig(baseSubscriptionMinor = 0, includedOrders = 500, perOrderBps = 100),
        serviceTemplates = listOf(
            ServiceTemplate("Basic wash", "Wash", 30, "Exterior rinse and foam wash"),
            ServiceTemplate("Foam wash", "Wash", 45, "Foam wash with tyre and rim clean"),
            ServiceTemplate("Interior vacuum", "Interior", 30, "Seats, mats and boot vacuum"),
            ServiceTemplate("Full detailing", "Detailing", 120, "Full exterior and interior detailing"),
        ),
        amenities = listOf("Waiting lounge", "Covered bay", "Parking", "Free WiFi"),
    )
}
