package com.odyssey.data

import com.odyssey.domain.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ErrorBody(val code: String = "error", val message: String = "Something went wrong")

val apiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** One place that knows about URLs, headers and error bodies. Repositories never touch Ktor directly. */
class ApiClient(
    private val baseUrl: String,
    private val tenant: String,
    private val token: () -> String?,
    engine: HttpClientEngine = defaultEngine(),
    private val onUnauthorized: () -> Unit = {},
) {
    val http = HttpClient(engine) {
        install(ContentNegotiation) { json(apiJson) }
        expectSuccess = false
    }

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap(), pin: String? = null): T = send(HttpMethod.Get, path, query, null, pin, null).body()

    suspend inline fun <reified B : Any, reified T> post(path: String, payload: B, pin: String? = null, idempotencyKey: String? = null): T =
        send(HttpMethod.Post, path, emptyMap(), { setBody(payload) }, pin, idempotencyKey).body()

    suspend inline fun <reified B : Any, reified T> put(path: String, payload: B, pin: String? = null): T =
        send(HttpMethod.Put, path, emptyMap(), { setBody(payload) }, pin, null).body()

    suspend inline fun <reified B : Any> postUnit(path: String, payload: B, pin: String? = null) { send(HttpMethod.Post, path, emptyMap(), { setBody(payload) }, pin, null) }

    suspend fun postNoBody(path: String, pin: String? = null) { send(HttpMethod.Post, path, emptyMap(), { setBody("{}"); contentType(ContentType.Application.Json) }, pin, null) }

    suspend fun delete(path: String, pin: String? = null) { send(HttpMethod.Delete, path, emptyMap(), null, pin, null) }

    @PublishedApi
    internal suspend fun send(
        method: HttpMethod, path: String, query: Map<String, Any?>, body: (HttpRequestBuilder.() -> Unit)?, pin: String?, idempotencyKey: String?,
    ): HttpResponse {
        val response = try {
            http.request {
                this.method = method
                url(baseUrl.trimEnd('/') + "/v1" + path)
                query.forEach { (k, v) -> if (v != null) url.parameters.append(k, v.toString()) }
                header("X-Tenant", tenant)
                token()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                pin?.let { header("X-Owner-Pin", it) }
                idempotencyKey?.let { header("Idempotency-Key", it) }
                if (body != null) { contentType(ContentType.Application.Json); body() }
            }
        } catch (e: Exception) {
            throw ApiException("network", "Cannot reach the server. Check your connection.")
        }
        if (response.status.value in 200..299) return response
        val error = runCatching { apiJson.decodeFromString(ErrorBody.serializer(), response.bodyAsText()) }.getOrDefault(ErrorBody())
        if (response.status.value == 401 && token() != null) onUnauthorized()
        throw ApiException(error.code, error.message, response.status.value)
    }
}
