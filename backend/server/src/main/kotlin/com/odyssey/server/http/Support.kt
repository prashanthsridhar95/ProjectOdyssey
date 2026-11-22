package com.odyssey.server.http

import com.odyssey.core.domain.*
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun ApplicationCall.actor(): Actor {
    val p = principal<JWTPrincipal>()?.payload ?: throw DomainException("unauthorized", "Please sign in", 401)
    return Actor(
        userId = p.subject, role = Role.valueOf(p.getClaim("role").asString()), tenantId = p.getClaim("tenant").asString(),
        providerId = p.getClaim("provider").asString(), name = p.getClaim("name").asString() ?: "",
    )
}

fun Actor.require(vararg roles: Role) { if (role !in roles) throw forbidden() }
fun Actor.requireProvider() { if (!role.isProvider) throw forbidden() }
fun Actor.requireBackoffice() { if (!role.isBackoffice) throw forbidden() }

fun ApplicationCall.tenantFor(ctx: AppContext, actor: Actor): Tenant =
    ctx.repos.tenants.byId(actor.tenantId) ?: throw notFound("Tenant")

/** Public endpoints pick the tenant from the X-Tenant header, falling back to the configured default. */
fun ApplicationCall.publicTenant(ctx: AppContext): Tenant =
    ctx.tenant(request.header("X-Tenant") ?: ctx.config.defaultTenant)

fun ApplicationCall.ownerPin(): String? = request.header("X-Owner-Pin")

fun ApplicationCall.pathId(name: String = "id"): String = parameters[name] ?: throw invalid("Missing $name")

fun parseInstant(s: String): Instant = runCatching { Instant.parse(s) }.getOrElse { throw invalid("Invalid time: $s") }
fun parseDate(s: String?, zone: ZoneId): LocalDate =
    if (s == null) LocalDate.now(zone) else runCatching { LocalDate.parse(s) }.getOrElse { throw invalid("Invalid date: $s") }

/** Runs [block] once per Idempotency-Key. A retry with the same key returns the first response. */
inline fun <reified T> ApplicationCall.idempotent(ctx: AppContext, tenantId: String, scope: String, block: () -> T): T {
    val key = request.header("Idempotency-Key") ?: return block()
    val stored = ctx.persistenceStore.idempotent(tenantId, scope, key)
    if (stored != null) return appJson.decodeFromString(serializer<T>(), stored)
    val result = block()
    runCatching { ctx.persistenceStore.rememberIdempotent(tenantId, scope, key, appJson.encodeToString(serializer<T>(), result), ctx.clock.now()) }
    return result
}
