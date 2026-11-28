package com.odyssey.core.config

import com.odyssey.core.domain.Role
import kotlinx.serialization.Serializable

/**
 * Everything a vertical (car wash, food delivery, ...) configures. The core reads this and never
 * contains vertical-specific words or rules. A vertical pack ships a default [TenantConfig];
 * admins can then edit it per tenant.
 */
@Serializable
data class TenantConfig(
    val labels: Labels,
    val variants: VariantDimension,
    val asset: AssetKind?,
    val flow: OrderFlow,
    val availability: AvailabilityConfig = AvailabilityConfig(),
    val money: MoneyConfig = MoneyConfig(),
    val cancellation: CancellationPolicy = CancellationPolicy(),
    val billing: BillingConfig = BillingConfig(),
    val serviceTemplates: List<ServiceTemplate> = emptyList(),
    val amenities: List<String> = emptyList(),
    /** Customer messages by order action (ACCEPT, START, COMPLETE...) and REMINDER. Placeholders: {location} {address} {when} {time} {code} {asset} {services} {note}. */
    val messages: Map<String, Message> = emptyMap(),
    /** Cities where the marketplace is live, so an app opened elsewhere can offer to look there. */
    val serviceAreas: List<ServiceArea> = emptyList(),
    /** Refer-a-friend rewards. Both vouchers are funded by the platform. */
    val referral: ReferralConfig = ReferralConfig(),
)

/** A voucher handed out by the referral scheme. PERCENT value is in basis points; FLAT is minor units. */
@Serializable
data class RewardTerms(val kind: String = "FLAT", val value: Long = 10_000, val maxDiscountMinor: Long? = null, val minSubtotalMinor: Long = 0, val validDays: Int = 30)

@Serializable
data class ReferralConfig(
    val enabled: Boolean = true,
    /** What the new customer gets when they use a friend's code. */
    val welcome: RewardTerms = RewardTerms(),
    /** What the friend gets after the new customer's first completed booking. */
    val reward: RewardTerms = RewardTerms(),
)

@Serializable
data class ServiceArea(val name: String, val lat: Double, val lng: Double, val radiusKm: Double = 30.0)

@Serializable
data class Message(val title: String, val body: String)

/** Display words, so the same core can say Vendor/Outlet/Booking or Restaurant/Branch/Order. */
@Serializable
data class Labels(
    val provider: String,
    val location: String,
    val order: String,
    val item: String,
    val customer: String = "Customer",
)

/** The dimension prices vary by (vehicle type for car wash, size for pizza, ...). */
@Serializable
data class VariantDimension(val name: String, val options: List<VariantOption>)

@Serializable
data class VariantOption(val key: String, val label: String)

/** What a customer keeps on file (a vehicle, an address...). Null when a vertical needs none. */
@Serializable
data class AssetKind(val kind: String, val label: String, val labelPlural: String, val fields: List<AssetField>)

@Serializable
data class AssetField(val key: String, val label: String, val required: Boolean = true, val options: List<String> = emptyList())

@Serializable
data class ServiceTemplate(val name: String, val category: String, val durationMinutes: Int, val description: String = "")

@Serializable
data class AvailabilityConfig(
    val leadMinutes: Int = 30,
    val windowDays: Int = 7,
    val holdMinutes: Int = 5,
    /** How long a vendor has to accept a paid booking before it is declined and refunded. */
    val acceptMinutes: Int = 10,
)

@Serializable
data class MoneyConfig(
    val currency: String = "INR",
    /** Tax on the service amount, in basis points (1800 = 18%). */
    val taxBps: Int = 1800,
    /** Flat convenience fee charged to the customer, in minor units. */
    val customerFeeMinor: Long = 0,
    /** Commission the tenant takes from a provider unless the provider has its own rate. */
    val defaultCommissionBps: Int = 1500,
    /** The payment gateway's cut, recovered from the vendor's share, in basis points of the service amount. */
    val gatewayFeeBps: Int = 130,
    /** Day of the week the platform pays vendors. */
    val payoutWeekday: String = "MONDAY",
)

@Serializable
data class CancellationPolicy(
    /** Customer cancels at least this many hours before the start: full refund. */
    val fullRefundHoursBefore: Int = 2,
    /** Customer cancels later: this share of the total is kept (basis points). */
    val lateRetainBps: Int = 5000,
)

/** What the framework owner charges a tenant: base subscription plus a per-order charge above a threshold. */
@Serializable
data class BillingConfig(
    val baseSubscriptionMinor: Long = 0,
    val includedOrders: Int = 500,
    val perOrderBps: Int = 100,
)

@Serializable
enum class Effect { NONE, REFUND_FULL, REFUND_BY_POLICY, SETTLE, SETTLE_NO_SHOW }

@Serializable
data class StateDef(
    val name: String,
    val label: String = name,
    /** True while the order holds capacity (a slot, stock...). */
    val occupiesCapacity: Boolean,
    val terminal: Boolean = false,
)

/** A role marker for actions the system performs itself (payment captured, hold expired). */
@Serializable
data class TransitionDef(
    val from: List<String>,
    val action: String,
    val to: String,
    val roles: Set<Role>,
    val system: Boolean = false,
    val effect: Effect = Effect.NONE,
)

/** The order state machine as data, so a vertical can reshape it without code. */
@Serializable
data class OrderFlow(
    val initial: String,
    val paid: String,
    val accepted: String,
    val completed: String,
    val states: List<StateDef>,
    val transitions: List<TransitionDef>,
) {
    fun state(name: String): StateDef = states.firstOrNull { it.name == name } ?: error("Unknown state $name")
    fun occupying(): List<String> = states.filter { it.occupiesCapacity }.map { it.name }
    fun transition(from: String, action: String): TransitionDef? =
        transitions.firstOrNull { it.action == action && from in it.from }
    fun actionsFor(from: String, role: Role): List<TransitionDef> =
        transitions.filter { from in it.from && !it.system && role in it.roles }
}
