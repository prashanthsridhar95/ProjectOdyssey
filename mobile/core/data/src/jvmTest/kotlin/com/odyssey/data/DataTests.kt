package com.odyssey.data

import com.odyssey.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private val json = headersOf(HttpHeaders.ContentType, "application/json")

private fun container(handler: MockRequestHandler, store: SessionStore = MemorySessionStore()) =
    AppContainer("http://api.test", "carwash", store, MockEngine(handler))

class ApiClientTest {
    @Test fun `sends tenant and bearer token and parses the reply`() = runTest {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val c = container({ req ->
            seen = req
            if (req.url.encodedPath == "/v1/auth/verify") respond("""{"accessToken":"tok","expiresAt":"x","user":{"id":"u1","name":"Asha","phone":"+91","role":"CUSTOMER"}}""", HttpStatusCode.OK, json)
            else respond("""[]""", HttpStatusCode.OK, json)
        })
        val session = c.signInWithOtp.verify("98765 43210", "123456", "Asha")
        assertEquals("tok", session.accessToken)
        assertEquals(Role.CUSTOMER, session.user.role)
        c.customer.assets()
        assertEquals("Bearer tok", seen!!.headers[HttpHeaders.Authorization])
        assertEquals("carwash", seen!!.headers["X-Tenant"])
    }

    @Test fun `server errors keep their code and message`() = runTest {
        val c = container({ respond("""{"code":"slot_unavailable","message":"That slot is no longer available"}""", HttpStatusCode.Conflict, json) })
        val e = assertFailsWith<ApiException> { c.customer.placeOrder(PlaceOrderInput("l", listOf("i"), "SEDAN", "2030-01-01T00:00:00Z", null), "k1") }
        assertEquals("slot_unavailable", e.code)
        assertEquals(409, e.status)
    }

    @Test fun `a 401 with a token signs the user out`() = runTest {
        val store = MemorySessionStore().apply { save(Session("old", User("u", "A", "+91", Role.CUSTOMER))) }
        val c = container({ respond("""{"code":"unauthorized","message":"Please sign in"}""", HttpStatusCode.Unauthorized, json) }, store)
        assertNotNull(c.session.value)
        assertFailsWith<ApiException> { c.customer.assets() }
        assertNull(c.session.value)
        assertNull(store.load())
    }

    @Test fun `owner PIN and idempotency key travel as headers`() = runTest {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val c = container({ req -> seen = req; respond("""{"orders":1}""", HttpStatusCode.OK, json) })
        c.provider.earnings("123456")
        assertEquals("123456", seen!!.headers["X-Owner-Pin"])
        container({ req -> seen = req; respond("""{"id":"o","code":"X","status":"PENDING_PAYMENT","statusLabel":"s","providerId":"p","locationId":"l","locationName":"L","variantKey":"SEDAN","startsAt":"a","endsAt":"b","subtotalMinor":1,"totalMinor":1}""", HttpStatusCode.Created, json) })
            .customer.placeOrder(PlaceOrderInput("l", listOf("i"), "SEDAN", "t", null), "idem-1")
        assertEquals("idem-1", seen!!.headers["Idempotency-Key"])
    }

    @Test fun `network failure becomes a friendly error`() = runTest {
        val c = container({ throw RuntimeException("offline") })
        assertEquals("network", assertFailsWith<ApiException> { c.customer.assets() }.code)
    }
}
