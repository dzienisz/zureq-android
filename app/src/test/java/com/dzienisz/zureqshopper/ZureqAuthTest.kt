package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.HttpResponse
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.auth.AuthHttp
import com.dzienisz.zureqshopper.auth.LoopbackServer
import com.dzienisz.zureqshopper.auth.ZureqAuth
import com.dzienisz.zureqshopper.data.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZureqAuthTest {
    private class FakeHttp : AuthHttp {
        val calls = mutableListOf<Triple<String, String, String>>()
        var responses = ArrayDeque<HttpResponse>()
        override suspend fun post(url: String, contentType: String, body: String): HttpResponse {
            calls += Triple(url, contentType, body)
            return responses.removeFirstOrNull() ?: HttpResponse(500, "")
        }
    }

    private val secrets = InMemorySecretStore()
    private val prefs = FakePrefs()
    private val http = FakeHttp()
    private var clock = 1_700_000_000_000L
    private val auth = ZureqAuth(secrets, prefs, http, now = { clock })

    private fun tokenJson(access: String, refresh: String = "r1", expiresIn: Int = 3600) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"token_type":"Bearer"}"""

    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ZureqAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun formBodyEncodesAndSorts() {
        assertEquals("a=x%20y&b=%2B%26~", ZureqAuth.formBody(mapOf("b" to "+&~", "a" to "x y")))
    }

    @Test
    fun registersClientOnceAndBuildsAuthorizeUrl() = runTest {
        http.responses.addLast(HttpResponse(201, """{"client_id":"cid-1"}"""))
        val request = auth.startRequest()
        assertEquals("cid-1", request.clientId)
        assertEquals("${ZureqAuth.ISSUER}/api/oauth/register", http.calls.single().first)
        assertTrue(http.calls.single().third.contains(ZureqAuth.LOOPBACK_REDIRECT_URI))
        val params = ZureqAuth.queryParams(request.url)
        assertEquals("code", params["response_type"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(ZureqAuth.challenge(request.verifier), params["code_challenge"])
        assertEquals(request.state, params["state"])
        assertEquals(ZureqAuth.RESOURCE, params["resource"])
        assertEquals("cid-1", auth.clientId())
        assertEquals(1, http.calls.size)
    }

    @Test
    fun completeRejectsWrongState() = runTest {
        val request = ZureqAuth.Request("u", "state1", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI)
        try {
            auth.complete(request, "http://localhost:47823/callback?code=abc&state=other")
            fail()
        } catch (e: ZureqException) {
            assertEquals(ZureqException.Code.AUTH_FAILED, e.code)
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun completeMapsAccessDeniedToCancelled() = runTest {
        val request = ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI)
        try {
            auth.complete(request, "http://localhost:47823/callback?error=access_denied&state=s")
            fail()
        } catch (e: ZureqException) {
            assertEquals(ZureqException.Code.AUTH_CANCELLED, e.code)
        }
    }

    @Test
    fun completeExchangesCodeAndStoresTokens() = runTest {
        http.responses.addLast(HttpResponse(200, tokenJson("a1")))
        val request = ZureqAuth.Request("u", "s", "verifier", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI)
        auth.complete(request, "http://localhost:47823/callback?code=abc&state=s")
        val (url, type, body) = http.calls.single()
        assertEquals("${ZureqAuth.ISSUER}/api/oauth/token", url)
        assertEquals("application/x-www-form-urlencoded", type)
        assertTrue(body.contains("grant_type=authorization_code") && body.contains("code_verifier=verifier") && body.contains("code=abc"))
        assertTrue(auth.hasSession)
        assertEquals("a1", auth.accessToken())
    }

    @Test
    fun accessTokenRefreshesWhenExpiring() = runTest {
        http.responses.addLast(HttpResponse(200, tokenJson("a1", expiresIn = 100)))
        auth.complete(ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI), "http://localhost:47823/callback?code=abc&state=s")
        clock += 50_000
        http.responses.addLast(HttpResponse(200, tokenJson("a2", refresh = "r2")))
        assertEquals("a2", auth.accessToken())
        assertTrue(http.calls.last().third.contains("grant_type=refresh_token") && http.calls.last().third.contains("refresh_token=r1"))
        assertEquals("a2", auth.accessToken())
        assertEquals(2, http.calls.size)
    }

    @Test
    fun refreshAfterStaleTokenSkipsWhenAlreadyFresh() = runTest {
        http.responses.addLast(HttpResponse(200, tokenJson("a1")))
        auth.complete(ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI), "http://localhost:47823/callback?code=abc&state=s")
        assertEquals("a1", auth.refresh("old"))
        assertEquals(1, http.calls.size)
    }

    @Test
    fun invalidGrantEndsSession() = runTest {
        http.responses.addLast(HttpResponse(200, tokenJson("a1")))
        auth.complete(ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI), "http://localhost:47823/callback?code=abc&state=s")
        http.responses.addLast(HttpResponse(400, """{"error":"invalid_grant"}"""))
        try {
            auth.refresh("a1")
            fail()
        } catch (e: ZureqException) {
            assertEquals(ZureqException.Code.SIGN_IN_ENDED, e.code)
        }
        assertFalse(auth.hasSession)
    }

    @Test
    fun forgetReturnsTokensForRevoke() = runTest {
        http.responses.addLast(HttpResponse(200, tokenJson("a1", refresh = "rr")))
        auth.complete(ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.LOOPBACK_REDIRECT_URI), "http://localhost:47823/callback?code=abc&state=s")
        val tokens = auth.forget()
        assertEquals("rr", tokens?.refresh)
        assertNull(auth.forget())
        auth.revoke(tokens!!)
        assertEquals("${ZureqAuth.ISSUER}/api/oauth/revoke", http.calls.last().first)
        assertTrue(http.calls.last().third.contains("token=rr"))
    }


    @Test
    fun appRedirectBuildsAuthorizeUrlAndRegistersScheme() = runTest {
        val appAuth = ZureqAuth(secrets, prefs, http, redirectUri = ZureqAuth.APP_REDIRECT_URI)
        http.responses.addLast(HttpResponse(201, """{"client_id":"cid-app"}"""))
        val request = appAuth.startRequest()
        assertTrue(http.calls.single().third.contains(ZureqAuth.APP_REDIRECT_URI))
        assertEquals(ZureqAuth.APP_REDIRECT_URI, ZureqAuth.queryParams(request.url)["redirect_uri"])
        assertEquals(ZureqAuth.APP_REDIRECT_URI, request.redirectUri)
    }

    @Test
    fun completeWithAppRedirectSendsItsRedirectUri() = runTest {
        val appAuth = ZureqAuth(secrets, prefs, http, redirectUri = ZureqAuth.APP_REDIRECT_URI)
        http.responses.addLast(HttpResponse(200, tokenJson("a1")))
        val request = ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.APP_REDIRECT_URI)
        appAuth.complete(request, "com.dzienisz.zureqshopper:/oauth?code=abc&state=s")
        val body = http.calls.single().third
        assertTrue(body.contains("redirect_uri=" + java.net.URLEncoder.encode(ZureqAuth.APP_REDIRECT_URI, "UTF-8")))
        assertTrue(body.contains("code=abc"))
    }

    @Test
    fun requestRoundTripsThroughJson() {
        val request = ZureqAuth.Request("u", "s", "v", "cid", ZureqAuth.APP_REDIRECT_URI)
        assertEquals(request, ZureqAuth.decodeRequest(ZureqAuth.encodeRequest(request)))
        assertNull(ZureqAuth.decodeRequest("garbage"))
    }

    @Test
    fun defaultRedirectIsLoopbackUntilEnabled() {
        assertEquals(ZureqAuth.LOOPBACK_REDIRECT_URI, ZureqAuth.DEFAULT_REDIRECT_URI)
    }

    @Test
    fun loopbackParsesRequestLine() {
        assertEquals("/callback?code=1&state=2", LoopbackServer.requestTarget("GET /callback?code=1&state=2 HTTP/1.1"))
        assertNull(LoopbackServer.requestTarget("POST /callback HTTP/1.1"))
        assertNull(LoopbackServer.requestTarget("garbage"))
    }

    @Test
    fun loopbackServerCompletesOnCallback() = runTest {
        val server = LoopbackServer(0.let { 47999 })
        server.start()
        java.net.Socket("127.0.0.1", 47999).use { s ->
            s.getOutputStream().write("GET /callback?code=xyz&state=st HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().bufferedReader().readText()
            assertTrue(reply.startsWith("HTTP/1.1 200 OK"))
        }
        assertEquals("http://localhost:47999/callback?code=xyz&state=st", server.awaitCallback())
        server.stop()
    }
}
