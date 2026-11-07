package com.dzienisz.zureqshopper.auth

import android.content.SharedPreferences
import com.dzienisz.zureqshopper.api.HttpResponse
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Plain HTTP POST used for the OAuth endpoints on chat.zureq.io. */
interface AuthHttp {
    @Throws(IOException::class)
    suspend fun post(url: String, contentType: String, body: String): HttpResponse
}

class OkHttpAuthHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : AuthHttp {
    override suspend fun post(url: String, contentType: String, body: String): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody(contentType.toMediaType())).build()
        client.newCall(request).execute().use { HttpResponse(it.code, it.body?.string().orEmpty()) }
    }
}

/**
 * Sign in with ZUREQ: OAuth 2.1 authorization code + PKCE against chat.zureq.io
 * (docs.zureq.io/account/sign-in-with-zureq). Tokens live in the encrypted [SecretStore].
 */
class ZureqAuth(
    private val secrets: SecretStore,
    private val prefs: SharedPreferences,
    private val http: AuthHttp = OkHttpAuthHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Tokens(val access: String, val refresh: String, val expiresAt: Long, val clientId: String)

    data class Request(val url: String, val state: String, val verifier: String, val clientId: String)

    private val refreshLock = Mutex()

    val hasSession: Boolean get() = storedTokens() != null

    suspend fun clientId(): String {
        FIXED_CLIENT_ID?.let { return it }
        val cachedId = prefs.getString(CLIENT_ID_KEY, null)
        if (!cachedId.isNullOrEmpty() && prefs.getString(CLIENT_REDIRECT_KEY, null) == REDIRECT_URI) return cachedId
        val body = buildJsonObject {
            put("client_name", CLIENT_NAME)
            putJsonArray("redirect_uris") { add(kotlinx.serialization.json.JsonPrimitive(REDIRECT_URI)) }
            put("token_endpoint_auth_method", "none")
            putJsonArray("grant_types") { add(kotlinx.serialization.json.JsonPrimitive("authorization_code")); add(kotlinx.serialization.json.JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(kotlinx.serialization.json.JsonPrimitive("code")) }
            put("scope", "mcp")
        }
        val (status, json) = send("$ISSUER/api/oauth/register", "application/json", body.toString())
        val id = json?.get("client_id")?.jsonPrimitive?.content
        if (status !in 200..299 || id.isNullOrEmpty()) throw ZureqException(ZureqException.Code.AUTH_FAILED)
        prefs.edit().putString(CLIENT_ID_KEY, id).putString(CLIENT_REDIRECT_KEY, REDIRECT_URI).apply()
        return id
    }

    suspend fun startRequest(): Request {
        val clientId = clientId()
        val verifier = randomValue(32)
        val state = randomValue(16)
        val url = "$ISSUER/oauth/authorize?" + formBody(
            mapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to REDIRECT_URI,
                "code_challenge" to challenge(verifier),
                "code_challenge_method" to "S256",
                "scope" to "mcp",
                "state" to state,
                "resource" to RESOURCE,
            ),
        )
        return Request(url, state, verifier, clientId)
    }

    /** Checks the redirect the browser came back with and exchanges its code for tokens. */
    suspend fun complete(request: Request, callback: String) {
        val params = queryParams(callback)
        if (params["state"] != request.state) throw ZureqException(ZureqException.Code.AUTH_FAILED, "state")
        if (params["error"] == "access_denied") throw ZureqException(ZureqException.Code.AUTH_CANCELLED)
        val code = params["code"]
        if (params["error"] != null || code.isNullOrEmpty()) throw ZureqException(ZureqException.Code.AUTH_FAILED)
        val (status, json) = postToken(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to request.clientId,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to request.verifier,
                "resource" to RESOURCE,
            ),
        )
        val tokens = tokens(json, request.clientId)
        if (status !in 200..299 || tokens == null) throw ZureqException(ZureqException.Code.AUTH_FAILED)
        if (!store(tokens)) throw ZureqException(ZureqException.Code.AUTH_FAILED, "store")
    }

    suspend fun accessToken(): String {
        val tokens = storedTokens() ?: throw ZureqException.signInEnded
        if (tokens.expiresAt > now() + 60_000) return tokens.access
        return refresh(tokens.access)
    }

    /** Refresh tokens are single-use, so concurrent callers share one refresh. */
    suspend fun refresh(stale: String?): String = refreshLock.withLock {
        val tokens = storedTokens() ?: throw ZureqException.signInEnded
        if (tokens.access != stale && tokens.expiresAt > now() + 60_000) return tokens.access
        val (status, json) = postToken(
            mapOf("grant_type" to "refresh_token", "refresh_token" to tokens.refresh, "client_id" to tokens.clientId),
        )
        if (json?.get("error")?.jsonPrimitive?.content == "invalid_grant") {
            forget()
            throw ZureqException.signInEnded
        }
        val fresh = tokens(json, tokens.clientId)
        if (status !in 200..299 || fresh == null || !store(fresh)) throw ZureqException(ZureqException.Code.AUTH_FAILED, "refresh")
        fresh.access
    }

    /** Drops the local session straight away and returns it so the caller can revoke it. */
    fun forget(): Tokens? {
        val tokens = storedTokens()
        secrets.delete(TOKENS_ACCOUNT)
        return tokens
    }

    suspend fun revoke(tokens: Tokens) {
        runCatching { http.post("$ISSUER/api/oauth/revoke", FORM, formBody(mapOf("token" to tokens.refresh, "client_id" to tokens.clientId))) }
    }

    private suspend fun postToken(fields: Map<String, String>) = send("$ISSUER/api/oauth/token", FORM, formBody(fields))

    private suspend fun send(url: String, contentType: String, body: String): Pair<Int, JsonObject?> {
        val response = try {
            http.post(url, contentType, body)
        } catch (e: IOException) {
            throw ZureqException(ZureqException.Code.NETWORK_ERROR)
        }
        val json = runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        return response.status to json
    }

    private fun tokens(json: JsonObject?, clientId: String): Tokens? {
        val access = json?.get("access_token")?.jsonPrimitive?.content ?: return null
        val refresh = json["refresh_token"]?.jsonPrimitive?.content ?: return null
        val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return Tokens(access, refresh, now() + (expiresIn * 1000).toLong(), clientId)
    }

    private fun store(tokens: Tokens): Boolean = secrets.write(Json.encodeToString(Tokens.serializer(), tokens), TOKENS_ACCOUNT)

    private fun storedTokens(): Tokens? {
        val text = secrets.read(TOKENS_ACCOUNT) ?: return null
        return runCatching { Json.decodeFromString(Tokens.serializer(), text) }.getOrNull()
    }

    companion object {
        const val ISSUER = "https://chat.zureq.io"
        const val RESOURCE = "https://api.zureq.io/mcp"
        const val CALLBACK_PORT = 47823
        /** chat.zureq.io rewrites 127.0.0.1 to localhost when it sends the user through login, so register localhost. */
        const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"
        /** A pre-registered client_id from hello@zureq.io; until there is one the app registers itself (RFC 7591). */
        val FIXED_CLIENT_ID: String? = null
        const val CLIENT_NAME = "ZUREQ Shopper for Android"
        const val TOKENS_ACCOUNT = "zureqOAuth"
        private const val CLIENT_ID_KEY = "zureqOAuthClientId"
        private const val CLIENT_REDIRECT_KEY = "zureqOAuthClientRedirect"
        private const val FORM = "application/x-www-form-urlencoded"

        fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun randomValue(count: Int): String = base64Url(ByteArray(count).also { SecureRandom().nextBytes(it) })

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun formBody(fields: Map<String, String>): String = fields.toSortedMap().entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        }

        fun queryParams(url: String): Map<String, String> {
            val query = url.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return emptyMap()
            return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
            }
        }
    }
}
