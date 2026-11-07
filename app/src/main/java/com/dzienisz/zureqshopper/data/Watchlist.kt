package com.dzienisz.zureqshopper.data

import android.content.SharedPreferences
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.ZureqClient
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.ui.applyLiveDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class WatchedItem(
    val product: Product,
    val addedPrice: Double? = null,
    val addedAt: Long,
    val checkedAt: Long? = null,
) {
    val id: String get() = product.id

    /** Difference between the current price and the price when the item was added, or null if unchanged/unknown. */
    val priceChange: Double?
        get() {
            val added = addedPrice ?: return null
            val price = product.price ?: return null
            return if (added == price) null else price - added
        }
}

/** Products the user watches, persisted as JSON in SharedPreferences. */
class Watchlist(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    data class State(
        val items: List<WatchedItem> = emptyList(),
        val isRefreshing: Boolean = false,
        val refreshError: RefreshError? = null,
    )

    sealed interface RefreshError {
        data class Stopped(val error: ZureqException) : RefreshError
        data class Partial(val failed: Int, val total: Int) : RefreshError
    }

    private val _state = MutableStateFlow(State(items = load()))
    val state: StateFlow<State> = _state
    val items: List<WatchedItem> get() = _state.value.items

    fun contains(product: Product): Boolean = items.any { it.id == product.id }

    fun toggle(product: Product, checkedAt: Long? = null) {
        if (contains(product)) remove(product)
        else set(listOf(WatchedItem(product, product.price, now(), checkedAt)) + items)
    }

    fun remove(product: Product) = set(items.filterNot { it.id == product.id })

    /** Stores a live-checked [product] for an item that is already watched. */
    fun record(product: Product, checkedAt: Long = now()) {
        if (!contains(product)) return
        set(items.map { if (it.id == product.id) it.copy(product = product, checkedAt = checkedAt) else it })
    }

    /** Checks every item's live price (1 credit each); stops at the first error that would fail all following calls. */
    suspend fun refresh(fetch: suspend (Product) -> ProductDetails) {
        if (_state.value.isRefreshing || items.isEmpty()) return
        _state.update { it.copy(isRefreshing = true, refreshError = null) }
        var failures = 0
        try {
            for (item in items) {
                try {
                    val details = fetch(item.product)
                    record(applyLiveDetails(item.product, details).first, now())
                } catch (e: ZureqException) {
                    if (e.stopsBatch) {
                        _state.update { it.copy(refreshError = RefreshError.Stopped(e)) }
                        return
                    }
                    failures += 1
                } catch (e: Exception) {
                    failures += 1
                }
            }
            if (failures > 0) _state.update { it.copy(refreshError = RefreshError.Partial(failures, items.size)) }
        } finally {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    private fun set(items: List<WatchedItem>) {
        _state.update { it.copy(items = items) }
        prefs.edit().putString(KEY, ZureqClient.json.encodeToString(serializer, items)).apply()
    }

    private fun load(): List<WatchedItem> = prefs.getString(KEY, null)
        ?.let { runCatching { ZureqClient.json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    companion object {
        private const val KEY = "zureqWatchlist"
        private val serializer = ListSerializer(WatchedItem.serializer())
    }
}
