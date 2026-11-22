package com.odyssey.server

import com.odyssey.core.domain.DomainException
import com.odyssey.server.http.*
import com.odyssey.server.wiring.AppConfig
import com.odyssey.server.wiring.AppContext
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnv()
    val ctx = AppContext(config)
    if (config.seed) Seeder(ctx).seedIfEmpty()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(ctx) }.start(wait = true)
}

fun Application.module(ctx: AppContext) {
    val log = LoggerFactory.getLogger("odyssey")

    install(ContentNegotiation) { json(appJson) }
    install(CallLogging) { level = Level.INFO }
    install(CORS) {
        if (ctx.config.corsOrigins.contains("*")) anyHost() else ctx.config.corsOrigins.forEach { allowHost(it.removePrefix("http://").removePrefix("https://"), listOf("http", "https")) }
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Tenant"); allowHeader("Idempotency-Key"); allowHeader("X-Owner-Pin")
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
    }
    install(StatusPages) {
        exception<DomainException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorBody(e.code, e.message)) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.message ?: "Invalid request body")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.cause?.message ?: e.message ?: "Invalid request")) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "Something went wrong"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized", "Please sign in")) }
    }
    install(Authentication) {
        jwt("auth") {
            verifier(ctx.tokens.verifier)
            validate { if (it.payload.subject != null) JWTPrincipal(it.payload) else null }
        }
    }

    routing {
        route("/v1") {
            publicRoutes(ctx)
            authenticate("auth") {
                customerRoutes(ctx)
                providerRoutes(ctx)
                adminRoutes(ctx)
            }
        }
        get("/health") { call.respond(mapOf("status" to "ok")) }
    }

    // Release slots held by unpaid orders.
    val sweeper = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        while (isActive) {
            delay(ctx.config.holdSweepSeconds * 1000)
            runCatching { ctx.orders.expireHolds(); ctx.orders.sendReminders() }.onFailure { log.warn("Hold sweep failed", it) }
        }
    }
    monitor.subscribe(ApplicationStopping) { sweeper.cancel() }
}
