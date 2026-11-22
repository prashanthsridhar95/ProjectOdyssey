package com.odyssey.server.wiring

import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import com.odyssey.core.service.*
import com.odyssey.server.auth.*
import com.odyssey.server.persistence.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import java.util.UUID

data class AppConfig(
    val dbUrl: String = "jdbc:h2:mem:odyssey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    val dbUser: String = "sa",
    val dbPassword: String = "",
    val jwtSecret: String = "dev-only-secret-change-me-please-32bytes",
    val devOtp: String = "123456",
    val seed: Boolean = true,
    val defaultTenant: String = "carwash",
    val webhookSecret: String = "dev-webhook-secret",
    val port: Int = 8080,
    val corsOrigins: List<String> = listOf("*"),
    val holdSweepSeconds: Long = 30,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = AppConfig(
            dbUrl = env["DB_URL"] ?: AppConfig().dbUrl,
            dbUser = env["DB_USER"] ?: "sa",
            dbPassword = env["DB_PASSWORD"] ?: "",
            jwtSecret = env["JWT_SECRET"] ?: AppConfig().jwtSecret,
            devOtp = env["DEV_OTP"] ?: "123456",
            seed = (env["SEED"] ?: "true").toBoolean(),
            defaultTenant = env["DEFAULT_TENANT"] ?: "carwash",
            webhookSecret = env["WEBHOOK_SECRET"] ?: "dev-webhook-secret",
            port = env["PORT"]?.toInt() ?: 8080,
            corsOrigins = (env["CORS_ORIGINS"] ?: "*").split(",").map { it.trim() },
        )
    }
}

/** The composition root: builds every adapter and service once and hands them to the HTTP layer. */
class AppContext(val config: AppConfig, val clock: Clock = SystemClock) {
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.dbUrl; username = config.dbUser; password = config.dbPassword
        maximumPoolSize = 10
        if (config.dbUrl.contains("h2:mem")) poolName = "h2-" + UUID.randomUUID()
    })
    val db: Database

    init {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        db = Database.connect(dataSource)
    }

    private val persistence = Persistence(db)
    val repos = persistence.repos()
    val persistenceStore = persistence
    val ids: Ids = UuidIds()
    val uow: UnitOfWork = ExposedUnitOfWork(db)
    val pins: PinHasher = Pbkdf2PinHasher()
    val gateway: PaymentGateway = FakePaymentGateway()
    val identity: IdentityProvider = DevIdentityProvider(config.devOtp)
    val tokens = TokenService(config.jwtSecret)

    val auditor = Auditor(repos.audit, ids, clock)
    val ledger = Ledger(repos.ledger, ids, clock)
    val availability = SlotAvailability(repos.orders, repos.blocks)
    val orders = OrderService(repos, uow, gateway, availability, ledger, auditor, clock, ids)
    val providers = ProviderService(repos, uow, pins, auditor, clock, ids)
    val admin = AdminService(repos, uow, ledger, auditor, clock, ids)
    val discovery = Discovery(repos, availability, clock)
    val auth = AuthService(repos, ids, identity, tokens, clock)

    fun tenant(slug: String): Tenant = repos.tenants.bySlug(slug) ?: throw notFound("Tenant")

    fun close() = dataSource.close()
}
