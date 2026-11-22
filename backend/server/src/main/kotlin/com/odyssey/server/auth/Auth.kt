package com.odyssey.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.odyssey.core.domain.*
import com.odyssey.core.ports.*
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Port for the identity product. Production plugs our existing IAM in here (sign-up, OTP, login);
 * the API only asks "is this phone number verified?". [DevIdentityProvider] accepts a fixed code.
 */
interface IdentityProvider {
    fun requestOtp(phone: String)
    fun verifyOtp(phone: String, code: String): Boolean
}

class DevIdentityProvider(private val code: String) : IdentityProvider {
    override fun requestOtp(phone: String) = Unit
    override fun verifyOtp(phone: String, code: String) = code == this.code
}

class FakePaymentGateway : PaymentGateway {
    private var counter = 0L
    private val pendingChecks = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override fun charge(req: ChargeRequest): ChargeResult {
        if (req.simulate == "fail" || req.method.equals("FAIL", true)) return ChargeResult(false, null, "The payment was declined")
        val ref = "pay_dev_${req.orderId.take(8)}_${++counter}"
        // simulate=pending behaves like a slow bank: the first status check is still pending, the second succeeds.
        if (req.simulate == "pending") { pendingChecks[ref] = 0; return ChargeResult(false, ref, pending = true) }
        return ChargeResult(true, ref)
    }

    override fun status(gatewayRef: String): ChargeResult {
        val checks = pendingChecks.merge(gatewayRef, 1) { a, b -> a + b } ?: 1
        return if (checks >= 2) ChargeResult(true, gatewayRef) else ChargeResult(false, gatewayRef, pending = true)
    }
    override fun refund(gatewayRef: String, amountMinor: Long) = RefundResult(true, "rf_dev_${++counter}")
}

data class IssuedToken(val token: String, val expiresAt: Instant)

class TokenService(secret: String, private val ttl: Duration = Duration.ofHours(12)) {
    private val algorithm = Algorithm.HMAC256(secret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(user: User): IssuedToken {
        val expires = Instant.now().plus(ttl)
        val token = JWT.create().withIssuer(ISSUER).withSubject(user.id).withClaim("tenant", user.tenantId).withClaim("role", user.role.name)
            .withClaim("name", user.name).apply { user.providerId?.let { withClaim("provider", it) } }.withExpiresAt(Date.from(expires)).sign(algorithm)
        return IssuedToken(token, expires)
    }

    companion object { const val ISSUER = "odyssey" }
}

data class AuthResult(val token: IssuedToken, val user: User)

class AuthService(private val r: Repos, private val ids: Ids, private val identity: IdentityProvider, private val tokens: TokenService, private val clock: Clock) {
    private val phoneRule = Regex("^\\+?[0-9]{10,15}$")

    fun normalize(phone: String): String {
        val p = phone.replace(" ", "").replace("-", "")
        if (!phoneRule.matches(p)) throw invalid("Enter a valid phone number")
        return p
    }

    fun requestOtp(tenant: Tenant, phone: String) = identity.requestOtp(normalize(phone))

    /** Customers are created on first login. Providers and back-office users must already exist. */
    fun verifyOtp(tenant: Tenant, phone: String, code: String, name: String?): AuthResult {
        val p = normalize(phone)
        if (!identity.verifyOtp(p, code)) throw DomainException("otp_invalid", "That code is not right", 401)
        val existing = r.users.byPhone(tenant.id, p)
        val user = when {
            existing != null && !existing.active -> throw DomainException("account_disabled", "This account is disabled", 403)
            existing != null -> existing
            else -> User(ids.newId(), tenant.id, p, name?.takeIf { it.isNotBlank() } ?: "Guest", Role.CUSTOMER).also { r.users.insert(it) }
        }
        return AuthResult(tokens.issue(user), user)
    }

    fun issueFor(user: User) = AuthResult(tokens.issue(user), user)
}

/** Logs pushes. Replace with an FCM / APNs sender; the in-app notification inbox already works. */
class LogPushSender : PushSender {
    private val log = org.slf4j.LoggerFactory.getLogger("push")
    override fun send(userId: Id, title: String, body: String) = log.info("push to {}: {} - {}", userId, title, body)
}
