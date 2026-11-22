package com.odyssey.core.service

import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Duration
import java.time.LocalTime

data class LocationDraft(
    val name: String, val address: String, val lat: Double, val lng: Double,
    val openTime: LocalTime = LocalTime.of(8, 0), val closeTime: LocalTime = LocalTime.of(20, 0),
    val slotMinutes: Int = 30, val capacity: Int = 2, val amenities: List<String> = emptyList(), val phone: String? = null,
)

enum class ProviderAction(val from: Set<ProviderStatus>, val to: ProviderStatus) {
    REVIEW(setOf(ProviderStatus.SUBMITTED, ProviderStatus.CHANGES_REQUESTED), ProviderStatus.UNDER_REVIEW),
    REQUEST_CHANGES(setOf(ProviderStatus.UNDER_REVIEW), ProviderStatus.CHANGES_REQUESTED),
    APPROVE(setOf(ProviderStatus.UNDER_REVIEW), ProviderStatus.APPROVED),
    REJECT(setOf(ProviderStatus.SUBMITTED, ProviderStatus.UNDER_REVIEW), ProviderStatus.REJECTED),
    GO_LIVE(setOf(ProviderStatus.APPROVED), ProviderStatus.LIVE),
    SUSPEND(setOf(ProviderStatus.LIVE), ProviderStatus.SUSPENDED),
    REINSTATE(setOf(ProviderStatus.SUSPENDED), ProviderStatus.LIVE),
}

/** Onboarding, verification workflow, staff and the PIN gate for sensitive provider actions. */
class ProviderService(
    private val r: Repos,
    private val uow: UnitOfWork,
    private val pins: PinHasher,
    private val auditor: Auditor,
    private val clock: Clock,
    private val ids: Ids,
) {
    /** Back-office creates a provider together with its owner login and first location. */
    fun adminCreate(tenant: Tenant, actor: Actor, name: String, ownerName: String, ownerPhone: String, location: LocationDraft): Provider = uow.run {
        if (!actor.role.isBackoffice) throw forbidden()
        if (r.users.byPhone(tenant.id, ownerPhone) != null) throw conflict("phone_taken", "That phone number is already registered")
        val owner = User(ids.newId(), tenant.id, ownerPhone, ownerName, Role.PROVIDER_OWNER)
        r.users.insert(owner)
        createProvider(tenant, actor, name, owner, location, ProviderStatus.UNDER_REVIEW)
    }

    /** A signed-in user applies to become a provider. */
    fun apply(tenant: Tenant, user: User, name: String, location: LocationDraft): Provider = uow.run {
        if (user.role != Role.CUSTOMER) throw conflict("already_member", "This account cannot apply")
        val owner = user.copy(role = Role.PROVIDER_OWNER)
        r.users.update(owner)
        createProvider(tenant, Actor(user.id, Role.PROVIDER_OWNER, tenant.id), name, owner, location, ProviderStatus.SUBMITTED)
    }

    private fun createProvider(tenant: Tenant, actor: Actor, name: String, owner: User, draft: LocationDraft, status: ProviderStatus): Provider {
        val provider = Provider(ids.newId(), tenant.id, name, owner.id, status, createdAt = clock.now())
        r.providers.insert(provider)
        r.users.update(owner.copy(providerId = provider.id))
        r.locations.insert(
            Location(
                id = ids.newId(), tenantId = tenant.id, providerId = provider.id, name = draft.name, address = draft.address,
                lat = draft.lat, lng = draft.lng, openTime = draft.openTime, closeTime = draft.closeTime,
                slotMinutes = draft.slotMinutes, capacity = draft.capacity, amenities = draft.amenities, phone = draft.phone,
            ),
        )
        auditor.log(actor, "PROVIDER_CREATED", "provider", provider.id, name)
        return provider
    }

    fun transition(tenant: Tenant, actor: Actor, providerId: Id, action: ProviderAction, note: String?): Provider = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.SUPPORT) throw forbidden()
        val provider = provider(tenant, providerId)
        if (provider.status !in action.from) throw conflict("invalid_transition", "Cannot ${action.name} a provider that is ${provider.status}")
        if (action == ProviderAction.GO_LIVE && r.locations.byProvider(providerId).isEmpty()) throw conflict("no_location", "Add a location first")
        val updated = provider.copy(status = action.to, reviewNote = note ?: provider.reviewNote)
        r.providers.update(updated)
        auditor.log(actor, "PROVIDER_${action.name}", "provider", providerId, note ?: "")
        updated
    }

    fun setCommission(tenant: Tenant, actor: Actor, providerId: Id, bps: Int?): Provider = uow.run {
        if (actor.role != Role.ADMIN && actor.role != Role.FINANCE) throw forbidden()
        if (bps != null && bps !in 0..5000) throw invalid("Commission must be between 0% and 50%")
        val updated = provider(tenant, providerId).copy(commissionBps = bps)
        r.providers.update(updated)
        auditor.log(actor, "COMMISSION_SET", "provider", providerId, bps?.toString() ?: "default")
        updated
    }

    fun updateDocuments(tenant: Tenant, actor: Actor, documents: List<ProviderDocument>): Provider = uow.run {
        val provider = ownProvider(tenant, actor)
        val resubmit = provider.status == ProviderStatus.CHANGES_REQUESTED
        val updated = provider.copy(documents = documents, status = if (resubmit) ProviderStatus.UNDER_REVIEW else provider.status)
        r.providers.update(updated)
        auditor.log(actor, "DOCUMENTS_UPDATED", "provider", provider.id)
        updated
    }

    fun updateBank(tenant: Tenant, actor: Actor, pin: String?, bank: BankDetails): Provider = uow.run {
        requireOwner(actor)
        verifyPin(actor, pin)
        val updated = ownProvider(tenant, actor).copy(bank = bank)
        r.providers.update(updated)
        auditor.log(actor, "BANK_UPDATED", "provider", updated.id)
        updated
    }

    // ---- PIN gate --------------------------------------------------------------------------------------------

    fun setOwnerPin(actor: Actor, newPin: String, currentPin: String?) = uow.run {
        requireOwner(actor)
        if (!newPin.matches(Regex("\\d{6}"))) throw invalid("Owner PIN must be 6 digits")
        val user = r.users.byId(actor.userId!!) ?: throw notFound("User")
        if (user.pinHash != null) verifyPin(actor, currentPin)
        r.users.update(r.users.byId(actor.userId)!!.copy(pinHash = pins.hash(newPin), pinFailures = 0, lockedUntil = null))
        auditor.log(actor, "OWNER_PIN_SET", "user", actor.userId)
    }

    /** Owner-only actions need the Owner PIN even inside an owner session. Five misses lock the account for 15 minutes. */
    fun verifyPin(actor: Actor, pin: String?) {
        requireOwner(actor)
        val user = r.users.byId(actor.userId!!) ?: throw notFound("User")
        val now = clock.now()
        if (user.lockedUntil != null && user.lockedUntil > now) throw DomainException("pin_locked", "Too many wrong PINs. Try again later.", 423)
        val hash = user.pinHash ?: throw DomainException("pin_not_set", "Set your Owner PIN first", 428)
        if (pin == null || !pins.verify(pin, hash)) {
            val failures = user.pinFailures + 1
            val lock = if (failures >= 5) now.plus(Duration.ofMinutes(15)) else null
            r.users.update(user.copy(pinFailures = if (lock != null) 0 else failures, lockedUntil = lock))
            if (lock != null) auditor.log(actor, "PIN_LOCKED", "user", user.id)
            throw DomainException("pin_invalid", "Wrong PIN", 403)
        }
        if (user.pinFailures > 0) r.users.update(user.copy(pinFailures = 0))
    }

    // ---- staff -----------------------------------------------------------------------------------------------

    fun addStaff(tenant: Tenant, actor: Actor, ownerPin: String?, name: String, phone: String, role: Role, staffPin: String): User = uow.run {
        requireOwner(actor)
        verifyPin(actor, ownerPin)
        if (role != Role.PROVIDER_MANAGER && role != Role.PROVIDER_STAFF) throw invalid("Staff role must be manager or staff")
        if (!staffPin.matches(Regex("\\d{4}"))) throw invalid("Staff PIN must be 4 digits")
        if (r.users.byPhone(tenant.id, phone) != null) throw conflict("phone_taken", "That phone number is already registered")
        val staff = User(ids.newId(), tenant.id, phone, name, role, actor.providerId, pins.hash(staffPin))
        r.users.insert(staff)
        auditor.log(actor, "STAFF_ADDED", "user", staff.id, "$name ($role)")
        staff
    }

    fun removeStaff(actor: Actor, ownerPin: String?, staffUserId: Id) = uow.run {
        requireOwner(actor)
        verifyPin(actor, ownerPin)
        val staff = r.users.byId(staffUserId)?.takeIf { it.providerId == actor.providerId && it.role != Role.PROVIDER_OWNER } ?: throw notFound("Staff")
        r.users.update(staff.copy(active = false))
        auditor.log(actor, "STAFF_REMOVED", "user", staffUserId)
    }

    /** Staff sign in on the shared counter device with a 4-digit PIN. Returns the user on success. */
    fun staffLogin(tenant: Tenant, phone: String, pin: String): User {
        val user = r.users.byPhone(tenant.id, phone)?.takeIf { it.active && (it.role == Role.PROVIDER_MANAGER || it.role == Role.PROVIDER_STAFF) }
            ?: throw DomainException("login_failed", "Wrong phone or PIN", 401)
        val now = clock.now()
        if (user.lockedUntil != null && user.lockedUntil > now) throw DomainException("pin_locked", "Too many wrong PINs. Try again later.", 423)
        if (user.pinHash == null || !pins.verify(pin, user.pinHash)) {
            val failures = user.pinFailures + 1
            val lock = if (failures >= 5) now.plus(Duration.ofMinutes(15)) else null
            r.users.update(user.copy(pinFailures = if (lock != null) 0 else failures, lockedUntil = lock))
            throw DomainException("login_failed", "Wrong phone or PIN", 401)
        }
        if (user.pinFailures > 0) r.users.update(user.copy(pinFailures = 0))
        return user
    }

    fun staff(actor: Actor): List<User> = r.users.byProvider(actor.providerId ?: throw forbidden()).filter { it.active }

    // ---- locations, menu, blocks -----------------------------------------------------------------------------

    fun updateLocation(tenant: Tenant, actor: Actor, locationId: Id, change: (Location) -> Location): Location = uow.run {
        val location = ownLocation(actor, locationId)
        if (actor.role == Role.PROVIDER_STAFF) throw forbidden()
        val updated = change(location).copy(id = location.id, tenantId = location.tenantId, providerId = location.providerId)
        if (updated.capacity < 1 || updated.slotMinutes !in 10..240) throw invalid("Invalid capacity or slot length")
        r.locations.update(updated)
        auditor.log(actor, "LOCATION_UPDATED", "location", locationId)
        updated
    }

    fun addLocation(tenant: Tenant, actor: Actor, ownerPin: String?, draft: LocationDraft): Location = uow.run {
        requireOwner(actor)
        val location = Location(
            ids.newId(), tenant.id, actor.providerId!!, draft.name, draft.address, draft.lat, draft.lng,
            openTime = draft.openTime, closeTime = draft.closeTime, slotMinutes = draft.slotMinutes, capacity = draft.capacity, amenities = draft.amenities, phone = draft.phone,
        )
        r.locations.insert(location)
        auditor.log(actor, "LOCATION_ADDED", "location", location.id)
        location
    }

    fun saveItem(tenant: Tenant, actor: Actor, ownerPin: String?, locationId: Id, itemId: Id?, name: String, description: String, category: String,
                 durationMinutes: Int, active: Boolean, prices: Map<String, Long>): CatalogItem = uow.run {
        ownLocation(actor, locationId)
        if (actor.role == Role.PROVIDER_STAFF) throw forbidden()
        if (durationMinutes !in 5..480) throw invalid("Duration must be between 5 and 480 minutes")
        val valid = tenant.config.variants.options.map { it.key }.toSet()
        if (prices.isEmpty() || prices.keys.any { it !in valid } || prices.values.any { it < 0 }) throw invalid("Invalid prices")
        val existing = itemId?.let { r.catalog.byId(it)?.takeIf { i -> i.locationId == locationId } ?: throw notFound("Item") }
        // Changing a price is owner-only and PIN gated. Name, description, duration and the on/off switch are not.
        if (existing == null || existing.prices != prices) {
            requireOwner(actor)
            verifyPin(actor, ownerPin)
        }
        val item = CatalogItem(existing?.id ?: ids.newId(), tenant.id, locationId, name.trim(), description, category, durationMinutes, active, prices)
        if (existing == null) r.catalog.insert(item) else r.catalog.update(item)
        auditor.log(actor, if (existing == null) "ITEM_ADDED" else "ITEM_UPDATED", "item", item.id, name)
        item
    }

    fun deleteItem(actor: Actor, ownerPin: String?, locationId: Id, itemId: Id) = uow.run {
        ownLocation(actor, locationId)
        requireOwner(actor)
        verifyPin(actor, ownerPin)
        r.catalog.byId(itemId)?.takeIf { it.locationId == locationId } ?: throw notFound("Item")
        r.catalog.delete(itemId)
        auditor.log(actor, "ITEM_DELETED", "item", itemId)
    }

    fun addBlock(tenant: Tenant, actor: Actor, locationId: Id, from: java.time.Instant, to: java.time.Instant, reason: String): Block = uow.run(locationId) {
        val location = ownLocation(actor, locationId)
        if (actor.role == Role.PROVIDER_STAFF) throw forbidden()
        if (!to.isAfter(from)) throw invalid("Block must end after it starts")
        val flow = tenant.config.flow
        val clash = r.orders.occupying(location.id, from, to, flow.occupying(), clock.now())
        if (clash.isNotEmpty()) {
            throw conflict("has_bookings", "${clash.size} booking(s) already exist in that time. Cancel them first, with a reason.")
        }
        val block = Block(ids.newId(), tenant.id, locationId, from, to, reason, actor.userId ?: "")
        r.blocks.insert(block)
        auditor.log(actor, "SLOT_BLOCKED", "location", locationId, "$from..$to $reason")
        block
    }

    fun removeBlock(actor: Actor, locationId: Id, blockId: Id) = uow.run {
        ownLocation(actor, locationId)
        if (actor.role == Role.PROVIDER_STAFF) throw forbidden()
        if (!r.blocks.delete(blockId, locationId)) throw notFound("Block")
        auditor.log(actor, "SLOT_REOPENED", "location", locationId, blockId)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun requireOwner(actor: Actor) { if (actor.role != Role.PROVIDER_OWNER) throw forbidden("Only the owner can do this") }

    private fun provider(tenant: Tenant, id: Id): Provider = r.providers.byId(id)?.takeIf { it.tenantId == tenant.id } ?: throw notFound("Provider")

    private fun ownProvider(tenant: Tenant, actor: Actor): Provider {
        if (!actor.role.isProvider) throw forbidden()
        return provider(tenant, actor.providerId ?: throw forbidden())
    }

    private fun ownLocation(actor: Actor, locationId: Id): Location {
        if (!actor.role.isProvider) throw forbidden()
        return r.locations.byId(locationId)?.takeIf { it.providerId == actor.providerId } ?: throw notFound("Location")
    }
}
