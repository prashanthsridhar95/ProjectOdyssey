package com.odyssey.server.persistence

import com.odyssey.core.config.TenantConfig
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private fun ms(i: Instant) = i.toEpochMilli()
private fun at(l: Long) = Instant.ofEpochMilli(l)

@Serializable private data class DocDto(val type: String, val reference: String)
@Serializable private data class BankDto(val accountName: String, val accountNumberMasked: String, val ifsc: String)

class ExposedUnitOfWork(private val db: Database) : UnitOfWork {
    override fun <T> run(lockLocationId: Id?, block: () -> T): T = transaction(db) {
        if (lockLocationId != null) Locations.selectAll().where { Locations.id eq lockLocationId }.forUpdate().toList()
        block()
    }
}

class UuidIds : Ids {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()
    override fun newId(): Id = UUID.randomUUID().toString()
    override fun newCode(): String = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

class Pbkdf2PinHasher : PinHasher {
    private val random = SecureRandom()
    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded
    override fun hash(pin: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val b64 = Base64.getEncoder()
        return b64.encodeToString(salt) + ":" + b64.encodeToString(derive(pin, salt))
    }
    override fun verify(pin: String, hash: String): Boolean {
        val parts = hash.split(":")
        if (parts.size != 2) return false
        val d = Base64.getDecoder()
        return MessageDigest.isEqual(derive(pin, d.decode(parts[0])), d.decode(parts[1]))
    }
}

class Persistence(private val db: Database) {
    private fun <T> tx(block: () -> T): T = transaction(db) { block() }

    val tenants = object : TenantRepo {
        private fun map(r: ResultRow) = Tenant(r[Tenants.id], r[Tenants.slug], r[Tenants.name], r[Tenants.vertical], json.decodeFromString<TenantConfig>(r[Tenants.config]))
        override fun bySlug(slug: String) = tx { Tenants.selectAll().where { Tenants.slug eq slug }.singleOrNull()?.let(::map) }
        override fun byId(id: Id) = tx { Tenants.selectAll().where { Tenants.id eq id }.singleOrNull()?.let(::map) }
        override fun all() = tx { Tenants.selectAll().map(::map) }
        override fun insert(tenant: Tenant) = tx { Tenants.insert { it[id] = tenant.id; it[slug] = tenant.slug; it[name] = tenant.name; it[vertical] = tenant.vertical; it[config] = json.encodeToString(TenantConfig.serializer(), tenant.config) }; Unit }
        override fun update(tenant: Tenant) = tx { Tenants.update({ Tenants.id eq tenant.id }) { it[name] = tenant.name; it[config] = json.encodeToString(TenantConfig.serializer(), tenant.config) }; Unit }
    }

    val users = object : UserRepo {
        private fun map(r: ResultRow) = User(r[Users.id], r[Users.tenantId], r[Users.phone], r[Users.name], Role.valueOf(r[Users.role]), r[Users.providerId], r[Users.pinHash], r[Users.pinFailures], r[Users.lockedUntil]?.let(::at), r[Users.active])
        override fun byId(id: Id) = tx { Users.selectAll().where { Users.id eq id }.singleOrNull()?.let(::map) }
        override fun byPhone(tenantId: Id, phone: String) = tx { Users.selectAll().where { (Users.tenantId eq tenantId) and (Users.phone eq phone) }.singleOrNull()?.let(::map) }
        override fun insert(user: User) = tx { Users.insert { bind(it, user) }; Unit }
        override fun update(user: User) = tx { Users.update({ Users.id eq user.id }) { bind(it, user) }; Unit }
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, u: User) {
            it[Users.id] = u.id; it[Users.tenantId] = u.tenantId; it[Users.phone] = u.phone; it[Users.name] = u.name; it[Users.role] = u.role.name
            it[Users.providerId] = u.providerId; it[Users.pinHash] = u.pinHash; it[Users.pinFailures] = u.pinFailures; it[Users.lockedUntil] = u.lockedUntil?.let(::ms); it[Users.active] = u.active
        }
        override fun byProvider(providerId: Id) = tx { Users.selectAll().where { Users.providerId eq providerId }.map(::map) }
        override fun byRole(tenantId: Id, role: Role, limit: Int) = tx { Users.selectAll().where { (Users.tenantId eq tenantId) and (Users.role eq role.name) }.limit(limit).map(::map) }
    }

    val providers = object : ProviderRepo {
        private fun map(r: ResultRow) = Provider(
            r[Providers.id], r[Providers.tenantId], r[Providers.name], r[Providers.ownerUserId], ProviderStatus.valueOf(r[Providers.status]), r[Providers.commissionBps],
            json.decodeFromString(ListSerializer(DocDto.serializer()), r[Providers.documents]).map { ProviderDocument(it.type, it.reference) },
            r[Providers.bank]?.let { json.decodeFromString(BankDto.serializer(), it) }?.let { BankDetails(it.accountName, it.accountNumberMasked, it.ifsc) },
            r[Providers.reviewNote], at(r[Providers.createdAt]),
        )
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, p: Provider) {
            it[Providers.id] = p.id; it[Providers.tenantId] = p.tenantId; it[Providers.name] = p.name; it[Providers.ownerUserId] = p.ownerUserId
            it[Providers.status] = p.status.name; it[Providers.commissionBps] = p.commissionBps
            it[Providers.documents] = json.encodeToString(ListSerializer(DocDto.serializer()), p.documents.map { d -> DocDto(d.type, d.reference) })
            it[Providers.bank] = p.bank?.let { b -> json.encodeToString(BankDto.serializer(), BankDto(b.accountName, b.accountNumberMasked, b.ifsc)) }
            it[Providers.reviewNote] = p.reviewNote; it[Providers.createdAt] = ms(p.createdAt)
        }
        override fun byId(id: Id) = tx { Providers.selectAll().where { Providers.id eq id }.singleOrNull()?.let(::map) }
        override fun byOwner(ownerUserId: Id) = tx { Providers.selectAll().where { Providers.ownerUserId eq ownerUserId }.firstOrNull()?.let(::map) }
        override fun list(tenantId: Id, status: ProviderStatus?) = tx {
            Providers.selectAll().where { if (status == null) Providers.tenantId eq tenantId else (Providers.tenantId eq tenantId) and (Providers.status eq status.name) }
                .orderBy(Providers.createdAt, SortOrder.DESC).map(::map)
        }
        override fun insert(provider: Provider) = tx { Providers.insert { bind(it, provider) }; Unit }
        override fun update(provider: Provider) = tx { Providers.update({ Providers.id eq provider.id }) { bind(it, provider) }; Unit }
    }

    val locations = object : LocationRepo {
        private fun map(r: ResultRow) = Location(
            r[Locations.id], r[Locations.tenantId], r[Locations.providerId], r[Locations.name], r[Locations.address], r[Locations.lat], r[Locations.lng], r[Locations.timezone],
            LocalTime.parse(r[Locations.openTime]), LocalTime.parse(r[Locations.closeTime]), r[Locations.slotMinutes], r[Locations.capacity], r[Locations.autoAccept], r[Locations.isOpen],
            json.decodeFromString(ListSerializer(String.serializer()), r[Locations.amenities]), r[Locations.photoUrl], r[Locations.ratingAvg], r[Locations.ratingCount], r[Locations.phone],
        )
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, l: Location) {
            it[Locations.id] = l.id; it[Locations.tenantId] = l.tenantId; it[Locations.providerId] = l.providerId; it[Locations.name] = l.name; it[Locations.address] = l.address
            it[Locations.lat] = l.lat; it[Locations.lng] = l.lng; it[Locations.timezone] = l.timezone; it[Locations.openTime] = l.openTime.toString(); it[Locations.closeTime] = l.closeTime.toString()
            it[Locations.slotMinutes] = l.slotMinutes; it[Locations.capacity] = l.capacity; it[Locations.autoAccept] = l.autoAccept; it[Locations.isOpen] = l.open
            it[Locations.amenities] = json.encodeToString(ListSerializer(String.serializer()), l.amenities); it[Locations.photoUrl] = l.photoUrl
            it[Locations.ratingAvg] = l.ratingAvg; it[Locations.ratingCount] = l.ratingCount; it[Locations.phone] = l.phone
        }
        override fun byId(id: Id) = tx { Locations.selectAll().where { Locations.id eq id }.singleOrNull()?.let(::map) }
        override fun byProvider(providerId: Id) = tx { Locations.selectAll().where { Locations.providerId eq providerId }.map(::map) }
        override fun live(tenantId: Id) = tx {
            Locations.join(Providers, JoinType.INNER, onColumn = Locations.providerId, otherColumn = Providers.id).selectAll().where { (Locations.tenantId eq tenantId) and (Providers.status eq ProviderStatus.LIVE.name) }.map(::map)
        }
        override fun insert(location: Location) = tx { Locations.insert { bind(it, location) }; Unit }
        override fun update(location: Location) = tx { Locations.update({ Locations.id eq location.id }) { bind(it, location) }; Unit }
    }

    val catalog = object : CatalogRepo {
        private val prices = MapSerializer(String.serializer(), Long.serializer())
        private fun map(r: ResultRow) = CatalogItem(r[CatalogItems.id], r[CatalogItems.tenantId], r[CatalogItems.locationId], r[CatalogItems.name], r[CatalogItems.description], r[CatalogItems.category], r[CatalogItems.durationMinutes], r[CatalogItems.active], json.decodeFromString(prices, r[CatalogItems.prices]))
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, i: CatalogItem) {
            it[CatalogItems.id] = i.id; it[CatalogItems.tenantId] = i.tenantId; it[CatalogItems.locationId] = i.locationId; it[CatalogItems.name] = i.name; it[CatalogItems.description] = i.description
            it[CatalogItems.category] = i.category; it[CatalogItems.durationMinutes] = i.durationMinutes; it[CatalogItems.active] = i.active; it[CatalogItems.prices] = json.encodeToString(prices, i.prices)
        }
        override fun byId(id: Id) = tx { CatalogItems.selectAll().where { CatalogItems.id eq id }.singleOrNull()?.let(::map) }
        override fun byIds(ids: List<Id>) = tx { if (ids.isEmpty()) emptyList() else CatalogItems.selectAll().where { CatalogItems.id inList ids }.map(::map) }
        override fun byLocation(locationId: Id, activeOnly: Boolean) = tx {
            CatalogItems.selectAll().where { if (activeOnly) (CatalogItems.locationId eq locationId) and (CatalogItems.active eq true) else CatalogItems.locationId eq locationId }
                .orderBy(CatalogItems.category).orderBy(CatalogItems.name).map(::map)
        }
        override fun insert(item: CatalogItem) = tx { CatalogItems.insert { bind(it, item) }; Unit }
        override fun update(item: CatalogItem) = tx { CatalogItems.update({ CatalogItems.id eq item.id }) { bind(it, item) }; Unit }
        override fun delete(id: Id) = tx { CatalogItems.deleteWhere { CatalogItems.id eq id }; Unit }
    }

    val blocks = object : BlockRepo {
        private fun map(r: ResultRow) = Block(r[Blocks.id], r[Blocks.tenantId], r[Blocks.locationId], at(r[Blocks.startsAt]), at(r[Blocks.endsAt]), r[Blocks.reason], r[Blocks.createdBy])
        override fun overlapping(locationId: Id, from: Instant, to: Instant) = tx {
            Blocks.selectAll().where { (Blocks.locationId eq locationId) and (Blocks.startsAt less ms(to)) and (Blocks.endsAt greater ms(from)) }.orderBy(Blocks.startsAt).map(::map)
        }
        override fun insert(block: Block) = tx { Blocks.insert { it[id] = block.id; it[tenantId] = block.tenantId; it[locationId] = block.locationId; it[startsAt] = ms(block.startsAt); it[endsAt] = ms(block.endsAt); it[reason] = block.reason; it[createdBy] = block.createdBy }; Unit }
        override fun delete(id: Id, locationId: Id) = tx { Blocks.deleteWhere { (Blocks.id eq id) and (Blocks.locationId eq locationId) } > 0 }
    }

    val orders = object : OrderRepo {
        private val lines = ListSerializer(OrderLineDto.serializer())
        private fun map(r: ResultRow) = Order(
            r[Orders.id], r[Orders.tenantId], r[Orders.code], r[Orders.customerId], r[Orders.customerName], r[Orders.customerPhone], r[Orders.providerId], r[Orders.locationId], r[Orders.locationName],
            r[Orders.assetLabel], r[Orders.variantKey], r[Orders.status], OrderSource.valueOf(r[Orders.origin]), at(r[Orders.startsAt]), at(r[Orders.endsAt]),
            json.decodeFromString(lines, r[Orders.lines]).map { OrderLine(it.itemId, it.name, it.priceMinor, it.durationMinutes) },
            r[Orders.subtotalMinor], r[Orders.taxMinor], r[Orders.feeMinor], r[Orders.totalMinor], r[Orders.commissionBps], r[Orders.commissionMinor],
            r[Orders.holdExpiresAt]?.let(::at), r[Orders.cancellationReason], r[Orders.settled], r[Orders.payoutId], at(r[Orders.createdAt]), at(r[Orders.updatedAt]),
            r[Orders.discountMinor], r[Orders.promoCode], r[Orders.reminded], r[Orders.reschedules],
        )
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, o: Order) {
            it[Orders.id] = o.id; it[Orders.tenantId] = o.tenantId; it[Orders.code] = o.code; it[Orders.customerId] = o.customerId; it[Orders.customerName] = o.customerName
            it[Orders.customerPhone] = o.customerPhone; it[Orders.providerId] = o.providerId; it[Orders.locationId] = o.locationId; it[Orders.locationName] = o.locationName
            it[Orders.assetLabel] = o.assetLabel; it[Orders.variantKey] = o.variantKey; it[Orders.status] = o.status; it[Orders.origin] = o.source.name
            it[Orders.startsAt] = ms(o.startsAt); it[Orders.endsAt] = ms(o.endsAt)
            it[Orders.lines] = json.encodeToString(lines, o.lines.map { l -> OrderLineDto(l.itemId, l.name, l.priceMinor, l.durationMinutes) })
            it[Orders.subtotalMinor] = o.subtotalMinor; it[Orders.taxMinor] = o.taxMinor; it[Orders.feeMinor] = o.feeMinor; it[Orders.totalMinor] = o.totalMinor
            it[Orders.commissionBps] = o.commissionBps; it[Orders.commissionMinor] = o.commissionMinor; it[Orders.holdExpiresAt] = o.holdExpiresAt?.let(::ms)
            it[Orders.cancellationReason] = o.cancellationReason; it[Orders.settled] = o.settled; it[Orders.payoutId] = o.payoutId
            it[Orders.createdAt] = ms(o.createdAt); it[Orders.updatedAt] = ms(o.updatedAt)
            it[Orders.discountMinor] = o.discountMinor; it[Orders.promoCode] = o.promoCode; it[Orders.reminded] = o.reminded; it[Orders.reschedules] = o.reschedules
        }
        override fun byId(id: Id) = tx { Orders.selectAll().where { Orders.id eq id }.singleOrNull()?.let(::map) }
        override fun insert(order: Order) = tx { Orders.insert { bind(it, order) }; Unit }
        override fun update(order: Order) = tx { Orders.update({ Orders.id eq order.id }) { bind(it, order) }; Unit }
        override fun addEvent(event: OrderEvent) = tx {
            OrderEvents.insert { it[orderId] = event.orderId; it[fromStatus] = event.from; it[toStatus] = event.to; it[action] = event.action; it[actorId] = event.actorId; it[note] = event.note; it[occurredAt] = ms(event.at) }; Unit
        }
        override fun events(orderId: Id) = tx {
            OrderEvents.selectAll().where { OrderEvents.orderId eq orderId }.orderBy(OrderEvents.id).map {
                OrderEvent(orderId, it[OrderEvents.fromStatus], it[OrderEvents.toStatus], it[OrderEvents.action], it[OrderEvents.actorId], it[OrderEvents.note], at(it[OrderEvents.occurredAt]))
            }
        }
        override fun occupying(locationId: Id, from: Instant, to: Instant, states: List<String>, now: Instant) = tx {
            Orders.selectAll().where {
                (Orders.locationId eq locationId) and (Orders.status inList states) and (Orders.startsAt less ms(to)) and (Orders.endsAt greater ms(from)) and
                    (Orders.holdExpiresAt.isNull() or (Orders.holdExpiresAt greater ms(now)))
            }.map(::map)
        }
        override fun byCustomer(customerId: Id) = tx { Orders.selectAll().where { Orders.customerId eq customerId }.orderBy(Orders.startsAt, SortOrder.DESC).map(::map) }
        override fun byProvider(providerId: Id, locationId: Id?, status: String?, from: Instant?, to: Instant?) = tx {
            var cond: Op<Boolean> = Orders.providerId eq providerId
            if (locationId != null) cond = cond and (Orders.locationId eq locationId)
            if (status != null) cond = cond and (Orders.status eq status)
            if (from != null) cond = cond and (Orders.startsAt greaterEq ms(from))
            if (to != null) cond = cond and (Orders.startsAt less ms(to))
            Orders.selectAll().where { cond }.orderBy(Orders.startsAt).map(::map)
        }
        override fun all(tenantId: Id, status: String?, from: Instant?, to: Instant?, limit: Int) = tx {
            var cond: Op<Boolean> = Orders.tenantId eq tenantId
            if (status != null) cond = cond and (Orders.status eq status)
            if (from != null) cond = cond and (Orders.createdAt greaterEq ms(from))
            if (to != null) cond = cond and (Orders.createdAt less ms(to))
            Orders.selectAll().where { cond }.orderBy(Orders.createdAt, SortOrder.DESC).limit(limit).map(::map)
        }
        override fun expiredHolds(now: Instant, state: String) = tx {
            Orders.selectAll().where { (Orders.status eq state) and Orders.holdExpiresAt.isNotNull() and (Orders.holdExpiresAt less ms(now)) }.map(::map)
        }
        override fun settledUnpaid(providerId: Id) = tx { Orders.selectAll().where { (Orders.providerId eq providerId) and (Orders.settled eq true) and Orders.payoutId.isNull() }.map(::map) }
        override fun byCode(tenantId: Id, code: String, providerId: Id?) = tx {
            Orders.selectAll().where { (Orders.tenantId eq tenantId) and (Orders.code eq code) and (if (providerId == null) Op.TRUE else Orders.providerId eq providerId) }.orderBy(Orders.createdAt, SortOrder.DESC).firstOrNull()?.let(::map)
        }
        override fun countPromoUses(tenantId: Id, code: String, userId: Id?) = tx {
            val dead = listOf("PENDING_PAYMENT", "EXPIRED", "CANCELLED", "REJECTED")
            Orders.selectAll().where { (Orders.tenantId eq tenantId) and (Orders.promoCode eq code) and (Orders.status notInList dead) and (if (userId == null) Op.TRUE else Orders.customerId eq userId) }.count().toInt()
        }
        override fun dueForReminder(from: Instant, to: Instant, states: List<String>) = tx {
            Orders.selectAll().where { (Orders.status inList states) and (Orders.reminded eq false) and (Orders.startsAt greaterEq ms(from)) and (Orders.startsAt less ms(to)) }.map(::map)
        }
        override fun paidOrders(tenantId: Id, from: Instant, to: Instant, unpaidStates: List<String>) = tx {
            Orders.selectAll().where { (Orders.tenantId eq tenantId) and (Orders.createdAt greaterEq ms(from)) and (Orders.createdAt less ms(to)) and (Orders.status notInList unpaidStates) }.map(::map)
        }
    }

    val payments = object : PaymentRepo {
        private fun map(r: ResultRow) = Payment(r[Payments.id], r[Payments.tenantId], r[Payments.orderId], r[Payments.amountMinor], r[Payments.refundedMinor], PaymentStatus.valueOf(r[Payments.status]), r[Payments.method], r[Payments.gatewayRef], at(r[Payments.createdAt]))
        override fun byOrder(orderId: Id) = tx { Payments.selectAll().where { Payments.orderId eq orderId }.singleOrNull()?.let(::map) }
        override fun insert(payment: Payment) = tx {
            Payments.insert { it[id] = payment.id; it[tenantId] = payment.tenantId; it[orderId] = payment.orderId; it[amountMinor] = payment.amountMinor; it[refundedMinor] = payment.refundedMinor; it[status] = payment.status.name; it[method] = payment.method; it[gatewayRef] = payment.gatewayRef; it[createdAt] = ms(payment.createdAt) }; Unit
        }
        override fun update(payment: Payment) = tx { Payments.update({ Payments.id eq payment.id }) { it[refundedMinor] = payment.refundedMinor; it[status] = payment.status.name }; Unit }
    }

    val ledger = object : LedgerRepo {
        private fun map(r: ResultRow) = LedgerEntry(r[LedgerEntries.id], r[LedgerEntries.tenantId], r[LedgerEntries.orderId], r[LedgerEntries.txnId], r[LedgerEntries.type], r[LedgerEntries.account], r[LedgerEntries.amountMinor], at(r[LedgerEntries.createdAt]))
        override fun post(entries: List<LedgerEntry>) = tx {
            LedgerEntries.batchInsert(entries) { e -> this[LedgerEntries.id] = e.id; this[LedgerEntries.tenantId] = e.tenantId; this[LedgerEntries.orderId] = e.orderId; this[LedgerEntries.txnId] = e.txnId; this[LedgerEntries.type] = e.type; this[LedgerEntries.account] = e.account; this[LedgerEntries.amountMinor] = e.amountMinor; this[LedgerEntries.createdAt] = ms(e.createdAt) }; Unit
        }
        override fun payableFor(orderId: Id) = tx {
            -LedgerEntries.selectAll().where { (LedgerEntries.orderId eq orderId) and (LedgerEntries.account eq "provider_payable") }.sumOf { it[LedgerEntries.amountMinor] }
        }
        override fun totals(tenantId: Id, from: Instant?, to: Instant?) = tx {
            val sum = LedgerEntries.amountMinor.sum()
            LedgerEntries.select(LedgerEntries.account, sum).where {
                var c: Op<Boolean> = LedgerEntries.tenantId eq tenantId
                if (from != null) c = c and (LedgerEntries.createdAt greaterEq ms(from))
                if (to != null) c = c and (LedgerEntries.createdAt less ms(to))
                c
            }.groupBy(LedgerEntries.account).associate { it[LedgerEntries.account] to (it[sum] ?: 0L) }
        }
        override fun forOrder(orderId: Id) = tx { LedgerEntries.selectAll().where { LedgerEntries.orderId eq orderId }.map(::map) }
    }

    val payouts = object : PayoutRepo {
        private fun map(r: ResultRow) = Payout(r[Payouts.id], r[Payouts.tenantId], r[Payouts.providerId], r[Payouts.amountMinor], r[Payouts.orderCount], r[Payouts.status], at(r[Payouts.createdAt]))
        override fun insert(payout: Payout) = tx { Payouts.insert { it[id] = payout.id; it[tenantId] = payout.tenantId; it[providerId] = payout.providerId; it[amountMinor] = payout.amountMinor; it[orderCount] = payout.orderCount; it[status] = payout.status; it[createdAt] = ms(payout.createdAt) }; Unit }
        override fun list(tenantId: Id, providerId: Id?) = tx {
            Payouts.selectAll().where { if (providerId == null) Payouts.tenantId eq tenantId else (Payouts.tenantId eq tenantId) and (Payouts.providerId eq providerId) }.orderBy(Payouts.createdAt, SortOrder.DESC).map(::map)
        }
    }

    val reviews = object : ReviewRepo {
        private fun map(r: ResultRow) = Review(r[Reviews.id], r[Reviews.tenantId], r[Reviews.orderId], r[Reviews.locationId], r[Reviews.customerName], r[Reviews.rating], r[Reviews.comment], at(r[Reviews.createdAt]), json.decodeFromString(ListSerializer(String.serializer()), r[Reviews.tags]))
        override fun insert(review: Review) = tx { Reviews.insert { it[id] = review.id; it[tenantId] = review.tenantId; it[orderId] = review.orderId; it[locationId] = review.locationId; it[customerName] = review.customerName; it[rating] = review.rating; it[comment] = review.comment; it[createdAt] = ms(review.createdAt); it[tags] = json.encodeToString(ListSerializer(String.serializer()), review.tags) }; Unit }
        override fun byOrder(orderId: Id) = tx { Reviews.selectAll().where { Reviews.orderId eq orderId }.singleOrNull()?.let(::map) }
        override fun byLocation(locationId: Id, limit: Int) = tx { Reviews.selectAll().where { Reviews.locationId eq locationId }.orderBy(Reviews.createdAt, SortOrder.DESC).limit(limit).map(::map) }
    }

    val assets = object : AssetRepo {
        private val attrs = MapSerializer(String.serializer(), String.serializer())
        private fun map(r: ResultRow) = Asset(r[Assets.id], r[Assets.tenantId], r[Assets.ownerUserId], r[Assets.kind], r[Assets.label], json.decodeFromString(attrs, r[Assets.attributes]))
        override fun byId(id: Id) = tx { Assets.selectAll().where { Assets.id eq id }.singleOrNull()?.let(::map) }
        override fun byOwner(ownerUserId: Id) = tx { Assets.selectAll().where { Assets.ownerUserId eq ownerUserId }.map(::map) }
        override fun insert(asset: Asset) = tx { Assets.insert { it[id] = asset.id; it[tenantId] = asset.tenantId; it[ownerUserId] = asset.ownerUserId; it[kind] = asset.kind; it[label] = asset.label; it[attributes] = json.encodeToString(attrs, asset.attributes) }; Unit }
        override fun delete(id: Id, ownerUserId: Id) = tx { Assets.deleteWhere { (Assets.id eq id) and (Assets.ownerUserId eq ownerUserId) } > 0 }
    }

    val audit = object : AuditRepo {
        override fun add(entry: AuditEntry) = tx {
            AuditLog.insert { it[id] = entry.id; it[tenantId] = entry.tenantId; it[actorId] = entry.actorId; it[actorRole] = entry.actorRole; it[action] = entry.action; it[entity] = entry.entity; it[entityId] = entry.entityId; it[detail] = entry.detail; it[occurredAt] = ms(entry.at) }; Unit
        }
        override fun list(tenantId: Id, limit: Int) = tx {
            AuditLog.selectAll().where { AuditLog.tenantId eq tenantId }.orderBy(AuditLog.occurredAt, SortOrder.DESC).limit(limit).map {
                AuditEntry(it[AuditLog.id], it[AuditLog.tenantId], it[AuditLog.actorId], it[AuditLog.actorRole], it[AuditLog.action], it[AuditLog.entity], it[AuditLog.entityId], it[AuditLog.detail], at(it[AuditLog.occurredAt]))
            }
        }
    }


    val promos = object : PromoRepo {
        private fun map(r: ResultRow) = PromoCode(r[PromoCodes.tenantId], r[PromoCodes.code], PromoKind.valueOf(r[PromoCodes.kind]), r[PromoCodes.discountValue], r[PromoCodes.maxDiscountMinor], r[PromoCodes.minSubtotalMinor], r[PromoCodes.startsAt]?.let(::at), r[PromoCodes.expiresAt]?.let(::at), r[PromoCodes.usageLimit], r[PromoCodes.perUserLimit], r[PromoCodes.active], r[PromoCodes.description])
        override fun byCode(tenantId: Id, code: String) = tx { PromoCodes.selectAll().where { (PromoCodes.tenantId eq tenantId) and (PromoCodes.code eq code) }.singleOrNull()?.let(::map) }
        override fun list(tenantId: Id) = tx { PromoCodes.selectAll().where { PromoCodes.tenantId eq tenantId }.map(::map) }
        override fun save(promo: PromoCode) = tx {
            PromoCodes.deleteWhere { (PromoCodes.tenantId eq promo.tenantId) and (PromoCodes.code eq promo.code) }
            PromoCodes.insert {
                it[tenantId] = promo.tenantId; it[code] = promo.code; it[kind] = promo.kind.name; it[discountValue] = promo.value; it[maxDiscountMinor] = promo.maxDiscountMinor; it[minSubtotalMinor] = promo.minSubtotalMinor
                it[startsAt] = promo.startsAt?.let(::ms); it[expiresAt] = promo.expiresAt?.let(::ms); it[usageLimit] = promo.usageLimit; it[perUserLimit] = promo.perUserLimit; it[active] = promo.active; it[description] = promo.description
            }; Unit
        }
    }

    val favorites = object : FavoriteRepo {
        override fun ids(userId: Id) = tx { Favorites.selectAll().where { Favorites.userId eq userId }.orderBy(Favorites.createdAt, SortOrder.DESC).map { it[Favorites.locationId] } }
        override fun add(userId: Id, locationId: Id, at: Instant) = tx { if (Favorites.selectAll().where { (Favorites.userId eq userId) and (Favorites.locationId eq locationId) }.empty()) Favorites.insert { it[Favorites.userId] = userId; it[Favorites.locationId] = locationId; it[createdAt] = ms(at) }; Unit }
        override fun remove(userId: Id, locationId: Id) = tx { Favorites.deleteWhere { (Favorites.userId eq userId) and (Favorites.locationId eq locationId) }; Unit }
    }

    val places = object : PlaceRepo {
        private fun map(r: ResultRow) = SavedPlace(r[SavedPlaces.id], r[SavedPlaces.userId], r[SavedPlaces.label], r[SavedPlaces.address], r[SavedPlaces.lat], r[SavedPlaces.lng])
        override fun list(userId: Id) = tx { SavedPlaces.selectAll().where { SavedPlaces.userId eq userId }.map(::map) }
        override fun save(place: SavedPlace) = tx {
            SavedPlaces.deleteWhere { (SavedPlaces.userId eq place.userId) and (SavedPlaces.label eq place.label) }
            SavedPlaces.insert { it[id] = place.id; it[userId] = place.userId; it[label] = place.label; it[address] = place.address; it[lat] = place.lat; it[lng] = place.lng }; Unit
        }
        override fun delete(userId: Id, label: String) = tx { SavedPlaces.deleteWhere { (SavedPlaces.userId eq userId) and (SavedPlaces.label eq label) }; Unit }
    }

    val tickets = object : TicketRepo {
        private val photos = ListSerializer(String.serializer())
        private fun map(r: ResultRow) = Ticket(r[Tickets.id], r[Tickets.tenantId], r[Tickets.orderId], r[Tickets.userId], r[Tickets.providerId], r[Tickets.category], r[Tickets.description], json.decodeFromString(photos, r[Tickets.photos]), TicketStatus.valueOf(r[Tickets.status]), r[Tickets.resolutionNote], r[Tickets.refundMinor], at(r[Tickets.createdAt]), r[Tickets.resolvedAt]?.let(::at))
        private fun bind(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, t: Ticket) {
            it[Tickets.id] = t.id; it[Tickets.tenantId] = t.tenantId; it[Tickets.orderId] = t.orderId; it[Tickets.userId] = t.userId; it[Tickets.providerId] = t.providerId; it[Tickets.category] = t.category
            it[Tickets.description] = t.description; it[Tickets.photos] = json.encodeToString(photos, t.photos); it[Tickets.status] = t.status.name; it[Tickets.resolutionNote] = t.resolutionNote
            it[Tickets.refundMinor] = t.refundMinor; it[Tickets.createdAt] = ms(t.createdAt); it[Tickets.resolvedAt] = t.resolvedAt?.let(::ms)
        }
        override fun insert(ticket: Ticket) = tx { Tickets.insert { bind(it, ticket) }; Unit }
        override fun update(ticket: Ticket) = tx { Tickets.update({ Tickets.id eq ticket.id }) { bind(it, ticket) }; Unit }
        override fun byId(id: Id) = tx { Tickets.selectAll().where { Tickets.id eq id }.singleOrNull()?.let(::map) }
        override fun byUser(userId: Id) = tx { Tickets.selectAll().where { Tickets.userId eq userId }.orderBy(Tickets.createdAt, SortOrder.DESC).map(::map) }
        override fun byProvider(providerId: Id) = tx { Tickets.selectAll().where { Tickets.providerId eq providerId }.orderBy(Tickets.createdAt, SortOrder.DESC).map(::map) }
        override fun list(tenantId: Id, status: TicketStatus?) = tx {
            Tickets.selectAll().where { if (status == null) Tickets.tenantId eq tenantId else (Tickets.tenantId eq tenantId) and (Tickets.status eq status.name) }.orderBy(Tickets.createdAt, SortOrder.DESC).map(::map)
        }
    }

    val notifications = object : NotificationRepo {
        override fun insert(n: Notification) = tx { Notifications.insert { it[id] = n.id; it[tenantId] = n.tenantId; it[userId] = n.userId; it[title] = n.title; it[body] = n.body; it[orderId] = n.orderId; it[isRead] = n.read; it[createdAt] = ms(n.createdAt) }; Unit }
        override fun list(userId: Id, limit: Int) = tx {
            Notifications.selectAll().where { Notifications.userId eq userId }.orderBy(Notifications.createdAt, SortOrder.DESC).limit(limit).map {
                Notification(it[Notifications.id], it[Notifications.tenantId], it[Notifications.userId], it[Notifications.title], it[Notifications.body], it[Notifications.orderId], it[Notifications.isRead], at(it[Notifications.createdAt]))
            }
        }
        override fun markRead(userId: Id) = tx { Notifications.update({ Notifications.userId eq userId }) { it[isRead] = true }; Unit }
    }

    val devices = object : DeviceRepo {
        override fun register(tenantId: Id, userId: Id, token: String, platform: String, at: Instant) = tx {
            Devices.deleteWhere { Devices.token eq token }
            Devices.insert { it[Devices.token] = token; it[Devices.tenantId] = tenantId; it[Devices.userId] = userId; it[Devices.platform] = platform; it[createdAt] = ms(at) }; Unit
        }
    }

    /** Idempotency store for retried POSTs: same key returns the stored response. */
    fun idempotent(tenantId: Id, scope: String, key: String): String? = tx {
        IdempotencyKeys.selectAll().where { (IdempotencyKeys.tenantId eq tenantId) and (IdempotencyKeys.scope eq scope) and (IdempotencyKeys.idemKey eq key) }.singleOrNull()?.get(IdempotencyKeys.response)
    }

    fun rememberIdempotent(tenantId: Id, scope: String, key: String, response: String, now: Instant) = tx {
        IdempotencyKeys.insert { it[IdempotencyKeys.tenantId] = tenantId; it[IdempotencyKeys.scope] = scope; it[idemKey] = key; it[IdempotencyKeys.response] = response; it[createdAt] = ms(now) }; Unit
    }

    fun repos() = Repos(tenants, users, providers, locations, catalog, blocks, orders, payments, ledger, payouts, reviews, assets, audit, promos, favorites, places, tickets, notifications, devices)
}

@Serializable
data class OrderLineDto(val itemId: String, val name: String, val priceMinor: Long, val durationMinutes: Int)
