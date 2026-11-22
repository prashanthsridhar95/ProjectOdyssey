package com.odyssey.server.persistence

import org.jetbrains.exposed.sql.Table

object Tenants : Table("tenants") {
    val id = varchar("id", 36)
    val slug = varchar("slug", 64)
    val name = varchar("name", 200)
    val vertical = varchar("vertical", 64)
    val config = text("config")
    override val primaryKey = PrimaryKey(id)
}

object Users : Table("users") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val phone = varchar("phone", 32)
    val name = varchar("name", 200)
    val role = varchar("role", 32)
    val providerId = varchar("provider_id", 36).nullable()
    val pinHash = varchar("pin_hash", 200).nullable()
    val pinFailures = integer("pin_failures")
    val lockedUntil = long("locked_until").nullable()
    val active = bool("active")
    override val primaryKey = PrimaryKey(id)
}

object Providers : Table("providers") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val name = varchar("name", 200)
    val ownerUserId = varchar("owner_user_id", 36)
    val status = varchar("status", 32)
    val commissionBps = integer("commission_bps").nullable()
    val documents = text("documents")
    val bank = text("bank").nullable()
    val reviewNote = text("review_note").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Locations : Table("locations") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val providerId = varchar("provider_id", 36)
    val name = varchar("name", 200)
    val address = varchar("address", 500)
    val lat = double("lat")
    val lng = double("lng")
    val timezone = varchar("timezone", 64)
    val openTime = varchar("open_time", 5)
    val closeTime = varchar("close_time", 5)
    val slotMinutes = integer("slot_minutes")
    val capacity = integer("capacity")
    val autoAccept = bool("auto_accept")
    val isOpen = bool("is_open")
    val amenities = text("amenities")
    val photoUrl = varchar("photo_url", 500).nullable()
    val ratingAvg = double("rating_avg")
    val ratingCount = integer("rating_count")
    override val primaryKey = PrimaryKey(id)
}

object CatalogItems : Table("catalog_items") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val locationId = varchar("location_id", 36)
    val name = varchar("name", 200)
    val description = text("description")
    val category = varchar("category", 100)
    val durationMinutes = integer("duration_minutes")
    val active = bool("active")
    val prices = text("prices")
    override val primaryKey = PrimaryKey(id)
}

object Blocks : Table("blocks") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val locationId = varchar("location_id", 36)
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val reason = varchar("reason", 500)
    val createdBy = varchar("created_by", 36)
    override val primaryKey = PrimaryKey(id)
}

object Orders : Table("orders") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val code = varchar("code", 16)
    val customerId = varchar("customer_id", 36).nullable()
    val customerName = varchar("customer_name", 200)
    val customerPhone = varchar("customer_phone", 32)
    val providerId = varchar("provider_id", 36)
    val locationId = varchar("location_id", 36)
    val locationName = varchar("location_name", 200)
    val assetLabel = varchar("asset_label", 200).nullable()
    val variantKey = varchar("variant_key", 64)
    val status = varchar("status", 32)
    val origin = varchar("source", 16)
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val lines = text("lines")
    val subtotalMinor = long("subtotal_minor")
    val taxMinor = long("tax_minor")
    val feeMinor = long("fee_minor")
    val totalMinor = long("total_minor")
    val commissionBps = integer("commission_bps")
    val commissionMinor = long("commission_minor")
    val holdExpiresAt = long("hold_expires_at").nullable()
    val cancellationReason = text("cancellation_reason").nullable()
    val settled = bool("settled")
    val payoutId = varchar("payout_id", 36).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object OrderEvents : Table("order_events") {
    val id = long("id").autoIncrement()
    val orderId = varchar("order_id", 36)
    val fromStatus = varchar("from_status", 32).nullable()
    val toStatus = varchar("to_status", 32)
    val action = varchar("action", 64)
    val actorId = varchar("actor_id", 36).nullable()
    val note = text("note").nullable()
    val occurredAt = long("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object Payments : Table("payments") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val orderId = varchar("order_id", 36)
    val amountMinor = long("amount_minor")
    val refundedMinor = long("refunded_minor")
    val status = varchar("status", 32)
    val method = varchar("method", 32)
    val gatewayRef = varchar("gateway_ref", 100).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object LedgerEntries : Table("ledger_entries") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val orderId = varchar("order_id", 36).nullable()
    val txnId = varchar("txn_id", 36)
    val type = varchar("type", 32)
    val account = varchar("account", 64)
    val amountMinor = long("amount_minor")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Payouts : Table("payouts") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val providerId = varchar("provider_id", 36)
    val amountMinor = long("amount_minor")
    val orderCount = integer("order_count")
    val status = varchar("status", 32)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Reviews : Table("reviews") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val orderId = varchar("order_id", 36)
    val locationId = varchar("location_id", 36)
    val customerName = varchar("customer_name", 200)
    val rating = integer("rating")
    val comment = text("comment")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Assets : Table("assets") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val ownerUserId = varchar("owner_user_id", 36)
    val kind = varchar("kind", 32)
    val label = varchar("label", 200)
    val attributes = text("attributes")
    override val primaryKey = PrimaryKey(id)
}

object AuditLog : Table("audit_log") {
    val id = varchar("id", 36)
    val tenantId = varchar("tenant_id", 36)
    val actorId = varchar("actor_id", 36).nullable()
    val actorRole = varchar("actor_role", 32)
    val action = varchar("action", 64)
    val entity = varchar("entity", 32)
    val entityId = varchar("entity_id", 36).nullable()
    val detail = text("detail")
    val occurredAt = long("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object IdempotencyKeys : Table("idempotency_keys") {
    val tenantId = varchar("tenant_id", 36)
    val scope = varchar("scope", 64)
    val idemKey = varchar("idem_key", 100)
    val response = text("response")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(tenantId, scope, idemKey)
}
