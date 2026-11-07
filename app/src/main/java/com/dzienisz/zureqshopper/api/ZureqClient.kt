package com.dzienisz.zureqshopper.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class HttpResponse(val status: Int, val body: String)

interface HttpTransport {
    @Throws(IOException::class)
    suspend fun send(body: String, bearer: String): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {
    override suspend fun send(body: String, bearer: String): HttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(ZureqClient.ENDPOINT)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }
}

/** Supplies the bearer for each call: an API key, or an OAuth access token that can be refreshed. */
interface CredentialProvider {
    suspend fun credential(): String?

    /** A new token after the server rejected [rejected] with 401, or null if this credential cannot be refreshed. */
    suspend fun refreshed(rejected: String): String? = null

    /** Called when even a refreshed token was rejected. */
    suspend fun invalidate() {}
}

class StaticCredential(private val read: () -> String?) : CredentialProvider {
    override suspend fun credential(): String? = read()
}

/** JSON-RPC client for the ZUREQ MCP endpoint (https://api.zureq.io/mcp). */
class ZureqClient(
    private val provider: CredentialProvider,
    private val transport: HttpTransport = OkHttpTransport(),
    private val retryDelayMs: Long = 2_000,
) {
    constructor(transport: HttpTransport = OkHttpTransport(), retryDelayMs: Long = 2_000, credential: () -> String?) :
        this(StaticCredential(credential), transport, retryDelayMs)

    suspend fun <T> call(
        tool: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        deserializer: DeserializationStrategy<T>,
        apiKey: String? = null,
    ): T = decode(callRaw(tool, arguments, apiKey), deserializer)

    suspend fun callRaw(tool: String, arguments: JsonObject = JsonObject(emptyMap()), apiKey: String? = null): JsonElement {
        val stored = if (apiKey == null) provider.credential() else null
        var bearer = (apiKey ?: stored ?: "").trim()
        if (bearer.isEmpty()) throw ZureqException.noKey

        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", Random.nextInt(1, 1_000_000))
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            })
        }.toString()

        var attempt = 0
        var refreshed = false
        while (true) {
            attempt += 1
            try {
                val response = try {
                    transport.send(body, bearer)
                } catch (e: IOException) {
                    // A reused keep-alive connection that the server already closed fails the first request after idling.
                    if (attempt < 2) continue
                    throw ZureqException(ZureqException.Code.NETWORK_ERROR)
                }
                if (response.status == 401 && apiKey == null) {
                    if (refreshed) {
                        provider.invalidate()
                        throw ZureqException.signInEnded
                    }
                    val fresh = provider.refreshed(bearer)
                    if (fresh != null) {
                        bearer = fresh
                        refreshed = true
                        attempt -= 1
                        continue
                    }
                }
                return parse(response.body, response.status)
            } catch (e: ZureqException) {
                // docs.zureq.io: a 429 clears within the fixed one-minute window, so ~1 s is enough; shop timeouts get the full delay.
                if (e.isRetryable && attempt < 2) delay(if (e.code == ZureqException.Code.RATE_LIMIT) minOf(retryDelayMs, 1_000) else retryDelayMs) else throw e
            }
        }
    }

    companion object {
        const val ENDPOINT = "https://api.zureq.io/mcp"

        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        fun <T> decode(element: JsonElement, deserializer: DeserializationStrategy<T>): T = try {
            json.decodeFromJsonElement(deserializer, element)
        } catch (e: Exception) {
            throw ZureqException(ZureqException.Code.PARSE_ERROR)
        }

        fun parse(body: String, status: Int): JsonElement {
            val root = runCatching { json.parseToJsonElement(jsonPayload(body)) }.getOrNull() as? JsonObject
            if (root == null) {
                if (status in listOf(502, 503, 504)) throw ZureqException.unavailable
                throw ZureqException(ZureqException.Code.NETWORK_ERROR)
            }
            (root["error"] as? JsonObject)?.let { error ->
                val code = (error["code"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                val message = (error["message"] as? JsonPrimitive)?.contentOrNullSafe()
                throw ZureqException.fromRpc(code, message)
            }
            val result = root["result"] as? JsonObject ?: throw ZureqException(ZureqException.Code.PARSE_ERROR)
            val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
            val text = (result["content"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } == "text" }
                ?.let { ((it as JsonObject)["text"] as? JsonPrimitive)?.contentOrNullSafe() }
            val structured = result["structuredContent"]

            if (text != null) {
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                val embedded = (parsed as? JsonObject)?.get("error")
                if (isError || embedded != null) {
                    val message = (embedded as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
                        ?: (embedded as? JsonPrimitive)?.content ?: text
                    throw ZureqException(ZureqException.Code.API_ERROR, message)
                }
                return structured ?: parsed ?: JsonPrimitive(text)
            }
            if (isError) throw ZureqException(ZureqException.Code.API_ERROR)
            return structured ?: result
        }

        /** The endpoint may answer as Server-Sent Events; take the last `data:` line in that case. */
        private fun jsonPayload(body: String): String {
            if (!(body.startsWith("event:") || body.startsWith("data:"))) return body
            val line = body.lineSequence().lastOrNull { it.startsWith("data:") } ?: return body
            return line.removePrefix("data:").trim()
        }

        private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
    }
}
