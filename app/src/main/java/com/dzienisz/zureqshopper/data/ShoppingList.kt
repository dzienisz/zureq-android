package com.dzienisz.zureqshopper.data

import android.content.SharedPreferences
import com.dzienisz.zureqshopper.api.CheckoutLink
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.ShopInfo
import com.dzienisz.zureqshopper.api.Variant
import com.dzienisz.zureqshopper.api.ZureqClient
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.ui.applyLiveDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ListPart(
    val id: Int,
    val query: String,
    val candidates: List<Product> = emptyList(),
    val pickId: String? = null,
    val variantSku: String? = null,
    val variantsByProduct: Map<String, List<Variant>> = emptyMap(),
    val searched: Boolean = false,
    @Transient val error: ZureqException? = null,
) {
    val pick: Product? get() = candidates.firstOrNull { it.id == pickId }
    val variants: List<Variant> get() = pickId?.let { variantsByProduct[it] } ?: emptyList()
    val needsDetails: Boolean get() = pickId != null && variantsByProduct[pickId] == null
    val needsVariant: Boolean get() = variants.isNotEmpty() && variantSku == null
    val checkoutSku: String? get() = pick?.let { variantSku ?: it.sku }
}

data class ShopGroup(val shopId: String, val shopName: String, val parts: List<ListPart>, val totals: List<Total>) {
    data class Total(val currency: String, val amount: Double)
}

/** One checkout link for a shop; [index]/[count] identify the cart when a shop needs several, [query] a single-item fallback. */
data class CartLink(val index: Int, val count: Int, val query: String? = null, val url: String? = null, val error: ZureqException? = null)

sealed interface ShopMessage {
    data class StillChecking(val query: String, val error: ZureqException?) : ShopMessage
    data class ChooseVariant(val queries: List<String>) : ShopMessage
    data object NoCheckoutLinks : ShopMessage
    data class Failed(val error: ZureqException) : ShopMessage
}

/** Several items at once: search each, pick one result per item, then check out per shop. */
class ShoppingList(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    data class State(
        val text: String = "",
        val market: String = "",
        val parts: List<ListPart> = emptyList(),
        val isSearching: Boolean = false,
        val progress: Int? = null,
        val searchError: ZureqException? = null,
        val loadingDetails: Set<Int> = emptySet(),
        val busyShops: Set<String> = emptySet(),
        val carts: Map<String, List<CartLink>> = emptyMap(),
        val shopMessages: Map<String, ShopMessage> = emptyMap(),
    ) {
        val queries: List<String> get() = parse(text)
        val canSearch: Boolean get() = !isSearching && queries.size in 1..MAX_ITEMS
        val maxSearchCost: Int get() = minOf(queries.size, MAX_ITEMS) * 3

        val groups: List<ShopGroup>
            get() {
                val order = mutableListOf<String>()
                val byShop = mutableMapOf<String, MutableList<ListPart>>()
                for (part in parts) {
                    val pick = part.pick ?: continue
                    if (pick.shopId !in byShop) { order += pick.shopId; byShop[pick.shopId] = mutableListOf() }
                    byShop.getValue(pick.shopId) += part
                }
                return order.map { shopId ->
                    val shopParts = byShop.getValue(shopId)
                    val totals = shopParts.mapNotNull { p -> p.pick?.let { pick -> pick.price?.takeIf { it.isFinite() }?.let { (pick.currency ?: "—").uppercase() to it } } }
                        .groupBy({ it.first }, { it.second }).map { (currency, amounts) -> ShopGroup.Total(currency, amounts.sum()) }
                    ShopGroup(shopId, shopParts.first().pick?.shopName ?: shopId, shopParts, totals)
                }
            }
    }

    @Serializable
    private data class Saved(val text: String = "", val market: String = "", val parts: List<ListPart> = emptyList())

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    fun setText(text: String) {
        val current = _state.value
        if (text == current.text) return
        val reset = parse(text) != current.parts.map { it.query }
        _state.update { if (reset) it.cleared().copy(text = text) else it.copy(text = text) }
        save()
    }

    fun setMarket(market: String) {
        if (market == _state.value.market) return
        _state.update { it.cleared().copy(market = market) }
        save()
    }

    fun clear() {
        _state.update { it.cleared().copy(text = "", searchError = null) }
        save()
    }

    suspend fun search(searchItem: suspend (String) -> List<Product>) {
        val start = _state.value
        if (!start.canSearch) return
        val parts = start.queries.mapIndexed { index, query -> ListPart(index, query) }
        _state.update { it.cleared().copy(isSearching = true, searchError = null, parts = parts) }
        try {
            for (index in parts.indices) {
                _state.update { it.copy(progress = index) }
                try {
                    val found = searchItem(parts[index].query).take(CANDIDATES_PER_ITEM)
                    updatePart(index) { it.copy(candidates = found, searched = true) }
                } catch (e: ZureqException) {
                    if (e.stopsBatch) {
                        updatePart(index) { it.copy(searched = true) }
                        _state.update { it.copy(searchError = e) }
                        return
                    }
                    updatePart(index) { it.copy(searched = true, error = e) }
                } catch (e: Exception) {
                    updatePart(index) { it.copy(searched = true, error = ZureqException(ZureqException.Code.API_ERROR)) }
                }
                save()
            }
        } finally {
            _state.update { it.copy(isSearching = false, progress = null) }
            save()
        }
    }

    fun togglePick(product: Product, partId: Int) {
        val part = _state.value.parts.firstOrNull { it.id == partId } ?: return
        part.pick?.shopId?.let(::clearCheckout)
        val pickId = if (part.pickId == product.id) null else product.id
        updatePart(partId) { p ->
            val picked = p.copy(pickId = pickId, variantSku = null)
            if (picked.variants.size == 1) picked.copy(variantSku = picked.variants[0].sku) else picked
        }
        clearCheckout(product.shopId)
        save()
    }

    fun setVariant(sku: String?, partId: Int) {
        updatePart(partId) { it.copy(variantSku = sku) }
        _state.value.parts.firstOrNull { it.id == partId }?.pick?.shopId?.let(::clearCheckout)
        save()
    }

    /** Fetches live price and variants for a part's pick (1 credit); no-op when already known or loading. */
    suspend fun loadDetails(partId: Int, fetch: suspend (Product) -> ProductDetails) {
        val part = _state.value.parts.firstOrNull { it.id == partId } ?: return
        val pick = part.pick ?: return
        if (!part.needsDetails || partId in _state.value.loadingDetails) return
        _state.update { it.copy(loadingDetails = it.loadingDetails + partId) }
        try {
            val details = fetch(pick)
            updatePart(partId) { current ->
                if (current.candidates.none { it.id == pick.id }) return@updatePart current
                val variants = details.variants ?: emptyList()
                val updated = current.copy(
                    candidates = current.candidates.map { if (it.id == pick.id) applyLiveDetails(pick, details).first else it },
                    variantsByProduct = current.variantsByProduct + (pick.id to variants),
                    error = null,
                )
                if (updated.pickId == pick.id && variants.size == 1) updated.copy(variantSku = variants[0].sku) else updated
            }
            save()
        } catch (e: ZureqException) {
            updatePart(partId) { it.copy(error = e) }
        } catch (e: Exception) {
            updatePart(partId) { it.copy(error = ZureqException(ZureqException.Code.API_ERROR)) }
        } finally {
            _state.update { it.copy(loadingDetails = it.loadingDetails - partId) }
        }
    }

    suspend fun checkout(
        group: ShopGroup,
        fetchDetails: suspend (Product) -> ProductDetails,
        fetchShop: suspend (String) -> ShopInfo,
        createLink: suspend (String, List<String>) -> CheckoutLink,
    ) {
        val shopId = group.shopId
        if (shopId in _state.value.busyShops) return
        _state.update { it.copy(busyShops = it.busyShops + shopId) }
        try {
            clearCheckout(shopId)
            for (part in group.parts) loadDetails(part.id, fetchDetails)
            val current = group.parts.mapNotNull { part -> _state.value.parts.firstOrNull { it.id == part.id } }
            current.firstOrNull { it.needsDetails }?.let { failed ->
                setMessage(shopId, ShopMessage.StillChecking(failed.query, failed.error))
                return
            }
            val unresolved = current.filter { it.needsVariant }.map { it.query }
            if (unresolved.isNotEmpty()) {
                setMessage(shopId, ShopMessage.ChooseVariant(unresolved))
                return
            }
            val info = try {
                shopInfo(shopId, fetchShop)
            } catch (e: ZureqException) {
                setMessage(shopId, ShopMessage.Failed(e)); return
            } catch (e: Exception) {
                setMessage(shopId, ShopMessage.Failed(ZureqException(ZureqException.Code.API_ERROR))); return
            }
            if (info.checkoutLinkSupported == false) {
                setMessage(shopId, ShopMessage.NoCheckoutLinks); return
            }
            val chunks = chunk(current, info.checkoutLinkMaxLines)
            val links = mutableListOf<CartLink>()
            for ((index, chunkParts) in chunks.withIndex()) {
                try {
                    links += CartLink(index, chunks.size, url = link(shopId, chunkParts, createLink))
                } catch (e: ZureqException) {
                    if (e.stopsBatch) { links += CartLink(index, chunks.size, error = e); break }
                    if (chunkParts.size == 1) { links += CartLink(index, chunks.size, chunkParts[0].query, error = e); continue }
                    for (part in chunkParts) {
                        try {
                            links += CartLink(index, chunks.size, part.query, url = link(shopId, listOf(part), createLink))
                        } catch (inner: ZureqException) {
                            links += CartLink(index, chunks.size, part.query, error = inner)
                        }
                    }
                }
            }
            _state.update { it.copy(carts = it.carts + (shopId to links)) }
        } finally {
            _state.update { it.copy(busyShops = it.busyShops - shopId) }
        }
    }

    private suspend fun link(shopId: String, parts: List<ListPart>, createLink: suspend (String, List<String>) -> CheckoutLink): String {
        val link = try {
            createLink(shopId, parts.mapNotNull { it.checkoutSku })
        } catch (e: ZureqException) {
            throw e
        } catch (e: Exception) {
            throw ZureqException(ZureqException.Code.API_ERROR)
        }
        val url = link.checkoutUrl.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) throw ZureqException(ZureqException.Code.PARSE_ERROR)
        return url
    }

    private suspend fun shopInfo(shopId: String, fetch: suspend (String) -> ShopInfo): ShopInfo {
        val savedAt = prefs.getLong("$SHOP_CACHE_KEY.$shopId.at", 0L)
        val cached = prefs.getString("$SHOP_CACHE_KEY.$shopId", null)
        if (cached != null && now() - savedAt < SHOP_CACHE_TTL_MS) {
            runCatching { ZureqClient.json.decodeFromString(ShopInfo.serializer(), cached) }.getOrNull()?.let { return it }
        }
        val info = fetch(shopId)
        prefs.edit()
            .putString("$SHOP_CACHE_KEY.$shopId", ZureqClient.json.encodeToString(ShopInfo.serializer(), info))
            .putLong("$SHOP_CACHE_KEY.$shopId.at", now())
            .apply()
        return info
    }

    private fun setMessage(shopId: String, message: ShopMessage) = _state.update { it.copy(shopMessages = it.shopMessages + (shopId to message)) }

    private fun clearCheckout(shopId: String) = _state.update { it.copy(carts = it.carts - shopId, shopMessages = it.shopMessages - shopId) }

    private fun updatePart(partId: Int, transform: (ListPart) -> ListPart) =
        _state.update { s -> s.copy(parts = s.parts.map { if (it.id == partId) transform(it) else it }) }

    private fun State.cleared() = copy(parts = emptyList(), carts = emptyMap(), shopMessages = emptyMap())

    private fun save() {
        val s = _state.value
        prefs.edit().putString(KEY, ZureqClient.json.encodeToString(Saved.serializer(), Saved(s.text, s.market, s.parts))).apply()
    }

    private fun load(): State {
        val saved = prefs.getString(KEY, null)?.let { runCatching { ZureqClient.json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
        return State(
            text = saved?.text.orEmpty(),
            market = saved?.market ?: prefs.getString("zureqCountry", null).orEmpty(),
            parts = saved?.parts ?: emptyList(),
        )
    }

    companion object {
        const val MAX_ITEMS = 10
        const val CANDIDATES_PER_ITEM = 5
        private const val KEY = "zureqShoppingList"
        private const val SHOP_CACHE_KEY = "zureqShopCache"
        private const val SHOP_CACHE_TTL_MS = 24L * 60 * 60 * 1000

        fun parse(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun <T> chunk(items: List<T>, maxLines: Int?): List<List<T>> {
            if (items.isEmpty()) return emptyList()
            if (maxLines == null || maxLines <= 0 || items.size <= maxLines) return listOf(items)
            return items.chunked(maxLines)
        }
    }
}
