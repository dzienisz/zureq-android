package com.dzienisz.zureqshopper.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.api.Location
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.SearchResult
import com.dzienisz.zureqshopper.api.ZureqException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SearchViewModel(private val app: AppModel, private val prefs: SharedPreferences?) : ViewModel() {
    sealed interface Phase {
        data object Idle : Phase
        data object Loading : Phase
        data object Results : Phase
        data object Empty : Phase
        data class Failed(val error: Throwable) : Phase
    }

    data class State(
        val query: String = "",
        val market: String = "",
        val maxPrice: String = "",
        val inStockOnly: Boolean = true,
        val phase: Phase = Phase.Idle,
        val products: List<Product> = emptyList(),
        val note: String? = null,
        val searchedQuery: String = "",
        /** search_products returns at most 50 rows; the first page asks for 20 and "Show more" re-runs the query for 50. */
        val limit: Int = FIRST_PAGE,
        val isLoadingMore: Boolean = false,
    ) {
        val hasFilters: Boolean get() = maxPrice.isNotEmpty() || !inStockOnly
        val canLoadMore: Boolean get() = phase == Phase.Results && !isLoadingMore && products.size >= limit && limit < MAX_LIMIT
    }

    private val _state = MutableStateFlow(State(market = prefs?.getString(MARKET_KEY, null) ?: ""))
    val state: StateFlow<State> = _state

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        if (value.isEmpty()) reset()
    }

    fun setMarket(code: String) {
        _state.update { it.copy(market = code) }
        prefs?.edit()?.putString(MARKET_KEY, code)?.apply()
    }

    fun setMaxPrice(value: String) = _state.update { it.copy(maxPrice = value) }
    fun setInStockOnly(value: Boolean) = _state.update { it.copy(inStockOnly = value) }
    fun resetFilters() = _state.update { it.copy(maxPrice = "", inStockOnly = true) }

    fun search(raw: String? = null) {
        val text = (raw ?: _state.value.query).trim()
        if (text.length < 2) {
            _state.update { it.copy(phase = Phase.Failed(MinLengthException())) }
            return
        }
        _state.update { it.copy(query = text, searchedQuery = text, products = emptyList(), note = null, limit = FIRST_PAGE, phase = Phase.Loading) }
        viewModelScope.launch {
            try {
                val args = arguments(_state.value, text)
                val result = SearchResult.from(
                    app.call("search_products", args, SearchResult.dtoSerializer()) { SearchResult.from(it).products.size },
                )
                app.addHistory(text)
                _state.update {
                    it.copy(products = result.products, note = result.note, phase = if (result.products.isEmpty()) Phase.Empty else Phase.Results)
                }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Failed(e)) }
            }
        }
    }

    /** Re-runs the current query for the maximum page size (costs another search). */
    fun loadMore() {
        if (!_state.value.canLoadMore) return
        _state.update { it.copy(limit = MAX_LIMIT, isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val s = _state.value
                val result = SearchResult.from(
                    app.call("search_products", arguments(s, s.searchedQuery), SearchResult.dtoSerializer()) { SearchResult.from(it).products.size },
                )
                _state.update { it.copy(products = result.products, note = result.note, isLoadingMore = false) }
            } catch (e: Exception) {
                _state.update { it.copy(limit = FIRST_PAGE, isLoadingMore = false, phase = Phase.Failed(e)) }
            }
        }
    }

    fun reset() = _state.update { it.copy(products = emptyList(), note = null, limit = FIRST_PAGE, phase = Phase.Idle) }

    class MinLengthException : Exception("Type at least 2 characters.")

    companion object {
        private const val MARKET_KEY = "zureqCountry"
        const val FIRST_PAGE = 20
        const val MAX_LIMIT = 50

        fun arguments(state: State, query: String): JsonObject {
            val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "query" to JsonPrimitive(query),
                "limit" to JsonPrimitive(state.limit),
                "inStockOnly" to JsonPrimitive(state.inStockOnly),
            )
            args.putAll(Location.arguments(state.market))
            PriceFormat.parseMaxPrice(state.maxPrice)?.let { args["maxPrice"] = JsonPrimitive(it) }
            return JsonObject(args)
        }

        fun factory(app: AppModel, prefs: SharedPreferences? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app, prefs) }
        }
    }
}
