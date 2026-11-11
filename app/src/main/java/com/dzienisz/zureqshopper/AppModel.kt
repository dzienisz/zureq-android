package com.dzienisz.zureqshopper

import android.content.SharedPreferences
import com.dzienisz.zureqshopper.api.CredentialProvider
import com.dzienisz.zureqshopper.api.Credits
import com.dzienisz.zureqshopper.api.HttpTransport
import com.dzienisz.zureqshopper.api.Market
import com.dzienisz.zureqshopper.api.MarketsResponse
import com.dzienisz.zureqshopper.api.OkHttpTransport
import com.dzienisz.zureqshopper.api.Usage
import com.dzienisz.zureqshopper.api.ZureqClient
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.auth.AuthHttp
import com.dzienisz.zureqshopper.auth.OkHttpAuthHttp
import com.dzienisz.zureqshopper.auth.ZureqAuth
import com.dzienisz.zureqshopper.data.SecretStore
import com.dzienisz.zureqshopper.data.ShoppingList
import com.dzienisz.zureqshopper.data.Watchlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

sealed interface SignIn {
    data object Idle : SignIn
    data class Waiting(val request: ZureqAuth.Request) : SignIn
    data class Failed(val error: Throwable) : SignIn
}

/** App-wide state: credentials, credits, markets and search history. One instance lives in [ZureqApp]. */
class AppModel(
    private val secrets: SecretStore,
    private val prefs: SharedPreferences,
    transport: HttpTransport = OkHttpTransport(),
    authHttp: AuthHttp = OkHttpAuthHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    val auth = ZureqAuth(secrets, prefs, authHttp, now)
    val watchlist = Watchlist(prefs, now)
    val shoppingList = ShoppingList(prefs, now)
    data class State(
        val hasKey: Boolean = false,
        val usesZureqSignIn: Boolean = false,
        val usage: Usage? = null,
        val sessionCredits: Double = 0.0,
        val markets: List<Market> = emptyList(),
        val history: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn

    val client: ZureqClient = ZureqClient(AppCredentials(), transport)

    init {
        val storedKey = !secrets.read(API_KEY_ACCOUNT).isNullOrEmpty()
        val signedIn = auth.hasSession
        _state.value = State(
            hasKey = storedKey || signedIn,
            usesZureqSignIn = signedIn && !storedKey,
            history = prefs.getString(HISTORY_KEY, null)?.let { decodeList(it) } ?: emptyList(),
        )
        // A loopback callback can't arrive after process death (nothing listens any more), so only a scheme redirect is resumable.
        val pending = secrets.read(PENDING_SIGN_IN_ACCOUNT)?.let { ZureqAuth.decodeRequest(it) }
        if (pending != null && !ZureqAuth.isLoopback(pending.redirectUri)) _signIn.value = SignIn.Waiting(pending) else secrets.delete(PENDING_SIGN_IN_ACCOUNT)
    }

    /** Starts Sign in with ZUREQ and remembers the pending request so it survives process death. */
    suspend fun beginSignIn(): ZureqAuth.Request {
        val request = auth.startRequest()
        secrets.write(ZureqAuth.encodeRequest(request), PENDING_SIGN_IN_ACCOUNT)
        _signIn.value = SignIn.Waiting(request)
        return request
    }

    /** Finishes Sign in with ZUREQ once the browser redirected to [callback]; foreign redirects are ignored. */
    suspend fun finishSignIn(callback: String) {
        val request = (_signIn.value as? SignIn.Waiting)?.request
            ?: secrets.read(PENDING_SIGN_IN_ACCOUNT)?.let { ZureqAuth.decodeRequest(it) }
            ?: return
        try {
            try {
                completeSignIn(request, callback)
            } catch (e: ZureqException) {
                // The network-block policy lifts asynchronously after the app returns to the front; retry once.
                if (e.code != ZureqException.Code.NETWORK_ERROR) throw e
                delay(1000)
                completeSignIn(request, callback)
            }
            secrets.delete(PENDING_SIGN_IN_ACCOUNT)
            _signIn.value = SignIn.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            secrets.delete(PENDING_SIGN_IN_ACCOUNT)
            _signIn.value = SignIn.Failed(e)
        }
    }

    fun cancelSignIn() {
        secrets.delete(PENDING_SIGN_IN_ACCOUNT)
        _signIn.value = SignIn.Idle
    }

    fun failSignIn(e: Throwable) {
        secrets.delete(PENDING_SIGN_IN_ACCOUNT)
        _signIn.value = SignIn.Failed(e)
    }

    /** Calls a tool and keeps the local credit counter in step with what ZUREQ charges. */
    suspend fun <T> call(
        tool: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        deserializer: DeserializationStrategy<T>,
        resultCount: ((T) -> Int)? = null,
    ): T {
        try {
            val value = client.call(tool, arguments, deserializer)
            charge(Credits.cost(tool, resultCount?.invoke(value)))
            return value
        } catch (e: ZureqException) {
            if (e.code == ZureqException.Code.NO_KEY || e.code == ZureqException.Code.SIGN_IN_ENDED) {
                _state.update { it.copy(hasKey = false, usesZureqSignIn = false) }
            }
            throw e
        }
    }

    suspend fun saveKey(raw: String) {
        val key = extractKey(raw)
        if (key.isEmpty()) throw ZureqException.noKey
        val usage = client.call("get_usage", deserializer = Usage.serializer(), apiKey = key)
        if (!secrets.write(key, API_KEY_ACCOUNT)) throw ZureqException(ZureqException.Code.API_ERROR, "Could not save the key.")
        _state.update { it.copy(hasKey = true, usesZureqSignIn = false, usage = usage) }
    }

    /** Finishes Sign in with ZUREQ once the browser redirected to [callback]; the session replaces any API key. */
    suspend fun completeSignIn(request: ZureqAuth.Request, callback: String) {
        auth.complete(request, callback)
        secrets.delete(API_KEY_ACCOUNT)
        val usage = runCatching { client.call("get_usage", deserializer = Usage.serializer()) }.getOrNull()
        _state.update { it.copy(hasKey = true, usesZureqSignIn = true, usage = usage, sessionCredits = 0.0) }
    }

    /** Disconnects the API key or signs out of ZUREQ (revoking the session server-side). */
    suspend fun disconnect() {
        secrets.delete(API_KEY_ACCOUNT)
        val tokens = auth.forget()
        _state.update { it.copy(hasKey = false, usesZureqSignIn = false, usage = null, sessionCredits = 0.0) }
        if (tokens != null) auth.revoke(tokens)
    }

    /** Disconnects the API key. */
    fun removeKey() {
        secrets.delete(API_KEY_ACCOUNT)
        auth.forget()
        _state.update { it.copy(hasKey = false, usesZureqSignIn = false, usage = null, sessionCredits = 0.0) }
    }

    suspend fun refreshUsage() {
        if (!_state.value.hasKey) return
        val usage = runCatching { client.call("get_usage", deserializer = Usage.serializer()) }.getOrNull() ?: return
        _state.update { it.copy(usage = usage) }
    }

    suspend fun loadMarkets() {
        if (_state.value.markets.isEmpty()) cachedMarkets()?.let { cached -> _state.update { it.copy(markets = cached) } }
        if (_state.value.markets.isNotEmpty() || !_state.value.hasKey) return
        val response = runCatching { client.call("list_markets", deserializer = MarketsResponse.serializer()) }.getOrNull() ?: return
        val markets = response.markets()
        _state.update { it.copy(markets = markets) }
        prefs.edit()
            .putString(MARKETS_CACHE_KEY, ZureqClient.json.encodeToString(ListSerializer(Market.serializer()), markets))
            .putLong(MARKETS_SAVED_AT_KEY, now())
            .apply()
    }

    fun addHistory(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val history = (listOf(clean) + _state.value.history.filterNot { it.equals(clean, ignoreCase = true) }).take(20)
        _state.update { it.copy(history = history) }
        prefs.edit().putString(HISTORY_KEY, ZureqClient.json.encodeToString(ListSerializer(String.serializer()), history)).apply()
    }

    fun removeHistory(query: String) {
        val history = _state.value.history - query
        _state.update { it.copy(history = history) }
        prefs.edit().putString(HISTORY_KEY, ZureqClient.json.encodeToString(ListSerializer(String.serializer()), history)).apply()
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        prefs.edit().remove(HISTORY_KEY).apply()
    }

    private fun charge(cost: Double) {
        if (cost <= 0) return
        _state.update { s ->
            val usage = s.usage?.let { u ->
                u.copy(
                    remaining = u.remaining?.let { maxOf(0.0, it - cost) },
                    used = u.used?.let { it + cost },
                )
            }
            s.copy(sessionCredits = s.sessionCredits + cost, usage = usage)
        }
    }

    private fun cachedMarkets(): List<Market>? {
        val savedAt = prefs.getLong(MARKETS_SAVED_AT_KEY, 0L)
        if (now() - savedAt >= MARKETS_CACHE_TTL_MS) return null
        val data = prefs.getString(MARKETS_CACHE_KEY, null) ?: return null
        return runCatching { ZureqClient.json.decodeFromString(ListSerializer(Market.serializer()), data) }.getOrNull()
    }

    private fun decodeList(text: String): List<String>? =
        runCatching { ZureqClient.json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrNull()

    /** Prefers a saved API key; OAuth sessions are added in a later step. */
    private inner class AppCredentials : CredentialProvider {
        override suspend fun credential(): String? =
            secrets.read(API_KEY_ACCOUNT)?.takeIf { it.isNotEmpty() } ?: if (auth.hasSession) auth.accessToken() else null

        override suspend fun refreshed(rejected: String): String? =
            if (auth.hasSession) runCatching { auth.refresh(rejected) }.getOrNull() else null

        override suspend fun invalidate() {
            auth.forget()
        }
    }

    companion object {
        const val API_KEY_ACCOUNT = "zureqApiKey"
        const val PENDING_SIGN_IN_ACCOUNT = "zureqOAuthPending"
        private const val HISTORY_KEY = "zureqHistory"
        private const val MARKETS_CACHE_KEY = "zureqMarketsCache"
        private const val MARKETS_SAVED_AT_KEY = "zureqMarketsSavedAt"
        private const val MARKETS_CACHE_TTL_MS = 24L * 60 * 60 * 1000

        private val keyPattern = Regex("zmcp_[A-Za-z0-9_\\-]+")

        /** Accepts a bare zmcp_ key or a pasted block of text that contains one. */
        fun extractKey(raw: String): String {
            val trimmed = raw.trim()
            return keyPattern.find(trimmed)?.value ?: trimmed
        }
    }
}
