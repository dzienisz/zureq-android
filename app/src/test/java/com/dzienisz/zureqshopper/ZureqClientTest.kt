package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.HttpResponse
import com.dzienisz.zureqshopper.api.HttpTransport
import com.dzienisz.zureqshopper.api.SearchResult
import com.dzienisz.zureqshopper.api.Usage
import com.dzienisz.zureqshopper.api.ZureqClient
import com.dzienisz.zureqshopper.api.ZureqException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FakeTransport(private val responses: ArrayDeque<() -> HttpResponse>) : HttpTransport {
    val sent = mutableListOf<Pair<String, String>>()
    override suspend fun send(body: String, bearer: String): HttpResponse {
        sent += body to bearer
        return responses.removeFirst()()
    }
}

private fun rpc(result: String) = HttpResponse(200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
private fun text(payload: String, isError: Boolean = false) =
    rpc("""{"content":[{"type":"text","text":${JsonPrimitive(payload)}}],"isError":$isError}""")

class ZureqClientTest {
    private fun client(vararg responses: () -> HttpResponse, key: String? = "zmcp_test") =
        ZureqClient(FakeTransport(ArrayDeque(responses.toList())), retryDelayMs = 0) { key }

    @Test
    fun parsesSearchResultFromTextContent() = runTest {
        val c = client({ text("""{"products":[{"shopId":"s1","sku":"a","name":"Mug &amp; Co","price":"12.5"}],"note":"hi"}""") })
        val result = SearchResult.from(c.call("search_products", deserializer = SearchResult.dtoSerializer()))
        assertEquals(1, result.products.size)
        assertEquals("Mug & Co", result.products[0].name)
        assertEquals(12.5, result.products[0].price!!, 0.0)
        assertEquals("hi", result.note)
    }

    @Test
    fun acceptsItemsKeyAndSseBody() = runTest {
        val sse = "event: message\ndata: " + """{"jsonrpc":"2.0","id":1,"result":{"structuredContent":{"items":[{"shopId":"s","sku":"k"}]}}}"""
        val c = client({ HttpResponse(200, sse) })
        val result = SearchResult.from(c.call("search_products", deserializer = SearchResult.dtoSerializer()))
        assertEquals("k", result.products.single().name)
    }

    @Test
    fun mapsRpcErrorCodes() = runTest {
        val c = client({ HttpResponse(200, """{"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"bad"}}""") })
        try { c.callRaw("get_usage"); fail() } catch (e: ZureqException) { assertEquals(ZureqException.Code.BAD_KEY, e.code) }
    }

    @Test
    fun embeddedErrorBecomesApiErrorWithMessage() = runTest {
        val c = client({ text("""{"error":{"message":"Shop did not answer"}}""", isError = true) })
        try { c.callRaw("get_product"); fail() } catch (e: ZureqException) {
            assertEquals(ZureqException.Code.API_ERROR, e.code)
            assertEquals("Shop did not answer", e.serverMessage)
        }
    }

    @Test
    fun retriesOnceOnRateLimit() = runTest {
        val c = client(
            { HttpResponse(200, """{"jsonrpc":"2.0","id":1,"error":{"code":-32002}}""") },
            { text("""{"plan":"free","remaining":"4","included":100}""") },
        )
        val usage = c.call("get_usage", deserializer = Usage.serializer())
        assertEquals(4.0, usage.remaining!!, 0.0)
        assertTrue(usage.isLow)
    }

    @Test
    fun retriesOnceOnDroppedConnection() = runTest {
        val transport = FakeTransport(ArrayDeque(listOf({ throw IOException("reset") }, { text("""{"plan":"free"}""") })))
        val usage = ZureqClient(transport, 0) { "k" }.call("get_usage", deserializer = Usage.serializer())
        assertEquals("free", usage.plan)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun gatewayErrorWithoutJsonIsUnavailable() = runTest {
        val c = client({ HttpResponse(503, "<html>bad gateway</html>") }, { HttpResponse(503, "<html>") })
        try { c.callRaw("get_usage"); fail() } catch (e: ZureqException) { assertEquals(ZureqException.Code.UNAVAILABLE, e.code) }
    }

    @Test
    fun missingKeyFailsBeforeNetwork() = runTest {
        val transport = FakeTransport(ArrayDeque())
        try { ZureqClient(transport, 0) { "  " }.callRaw("get_usage"); fail() } catch (e: ZureqException) {
            assertEquals(ZureqException.Code.NO_KEY, e.code)
        }
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun sendsToolCallEnvelope() = runTest {
        val transport = FakeTransport(ArrayDeque(listOf({ text("{}") })))
        ZureqClient(transport, 0) { "zmcp_x" }.callRaw("search_products", JsonObject(mapOf("query" to JsonPrimitive("mug"))))
        val (body, bearer) = transport.sent.single()
        assertEquals("zmcp_x", bearer)
        assertTrue(body.contains("\"method\":\"tools/call\""))
        assertTrue(body.contains("\"name\":\"search_products\""))
        assertTrue(body.contains("\"query\":\"mug\""))
        assertNull(Regex("\"id\":\"").find(body))
    }
}
