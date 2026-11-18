package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.HttpResponse
import com.dzienisz.zureqshopper.api.HttpTransport
import com.dzienisz.zureqshopper.api.CheckoutLink
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.ui.applyLiveDetails
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.data.InMemorySecretStore
import com.dzienisz.zureqshopper.ui.SearchViewModel
import com.dzienisz.zureqshopper.ui.SearchViewModel.Phase
import com.dzienisz.zureqshopper.ui.SearchViewModel.Sort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Holds each request until the test releases it, so answers can arrive out of order. */
private class GatedTransport : HttpTransport {
    val pending = mutableListOf<CompletableDeferred<HttpResponse>>()
    override suspend fun send(body: String, bearer: String): HttpResponse =
        CompletableDeferred<HttpResponse>().also { pending += it }.await()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun results(vararg names: String): HttpResponse {
        val rows = names.mapIndexed { i, name -> """{"shopId":"a-pl","sku":"$i","name":"$name","price":10,"currency":"PLN"}""" }
        return HttpResponse(200, """{"jsonrpc":"2.0","id":1,"result":{"structuredContent":{"products":[${rows.joinToString(",")}]}}}""")
    }

    private val toolError = HttpResponse(
        200,
        """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"error\":\"Shop search failed.\"}"}],"isError":true}}""",
    )

    private fun app(transport: HttpTransport) =
        AppModel(InMemorySecretStore().apply { write("zmcp_test", "zureqApiKey") }, FakePrefs(), transport)

    private fun product(sku: String, price: Double?, currency: String? = "PLN") =
        Product(shopId = "a-pl", sku = sku, name = sku, price = price, currency = currency)

    @Test
    fun failedLoadMoreKeepsTheRowsOnScreen() {
        val firstPage = Array(SearchViewModel.FIRST_PAGE) { "Tea $it" }
        val model = SearchViewModel(app(FakeTransport(ArrayDeque(listOf({ results(*firstPage) }, { toolError })))), null)

        model.search("tea")
        assertTrue(model.state.value.canLoadMore)
        model.loadMore()

        val state = model.state.value
        assertEquals(Phase.Results, state.phase)
        assertEquals(SearchViewModel.FIRST_PAGE, state.products.size)
        assertEquals("Shop search failed.", state.loadMoreError?.message)
        assertTrue(state.canLoadMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun answerToAnOlderSearchIsDropped() {
        val gate = GatedTransport()
        val app = app(gate)
        val model = SearchViewModel(app, null)

        model.search("tea")
        model.search("coffee")
        gate.pending[1].complete(results("Coffee"))
        gate.pending[0].complete(results("Tea"))

        assertEquals("coffee", model.state.value.searchedQuery)
        assertEquals(listOf("Coffee"), model.state.value.products.map { it.name })
        assertEquals(listOf("coffee"), app.state.value.history)
    }

    @Test
    fun answerAfterResetIsDropped() {
        val gate = GatedTransport()
        val model = SearchViewModel(app(gate), null)

        model.search("tea")
        model.setQuery("")
        gate.pending[0].complete(results("Tea"))

        assertEquals(Phase.Idle, model.state.value.phase)
        assertTrue(model.state.value.products.isEmpty())
    }

    @Test
    fun inPostPayFilterSendsTheFlagAndMarksItsRows() {
        val plain = SearchViewModel.arguments(SearchViewModel.State(), "mug")
        assertFalse(plain.containsKey("flags"))
        val filtered = SearchViewModel.arguments(SearchViewModel.State(inPostPayOnly = true), "mug")
        assertEquals("""["inpost_pay"]""", filtered["flags"].toString())
        assertTrue(SearchViewModel.State(inPostPayOnly = true).hasFilters)

        val rows = listOf(Product(shopId = "a-pl", sku = "1"), Product(shopId = "a-pl", sku = "2", flags = listOf("other")))
        assertEquals(listOf(listOf("inpost_pay"), listOf("other")), SearchViewModel.tagged(rows, filtered).map { it.flags })
        assertEquals(listOf(null, listOf("other")), SearchViewModel.tagged(rows, plain).map { it.flags })
        assertTrue(SearchViewModel.tagged(rows, filtered)[0].hasInPostPay)
    }

    @Test
    fun liveDetailsBringPaymentFlags() {
        val product = Product(shopId = "s", sku = "1", price = 10.0)
        assertTrue(applyLiveDetails(product, ProductDetails(flags = listOf("inpost_pay"))).first.hasInPostPay)
        val marked = product.copy(flags = listOf("inpost_pay"))
        assertFalse(applyLiveDetails(marked, ProductDetails(price = 12.0, flags = emptyList())).first.hasInPostPay)
        assertTrue(applyLiveDetails(marked, ProductDetails(price = 12.0)).first.hasInPostPay)
    }

    @Test
    fun checkoutLinkThatIsTheProductPage() {
        val page = "https://shop.pl/p/mug/51473"
        assertTrue(CheckoutLink(page).opensProductPage(page))
        assertTrue(CheckoutLink("$page/").opensProductPage(page))
        assertFalse(CheckoutLink("https://shop.pl/cart/1:1").opensProductPage(page))
        assertFalse(CheckoutLink("$page?add-to-cart=7").opensProductPage(page))
        assertFalse(CheckoutLink(page).opensProductPage(null))
        assertTrue(CheckoutLink("https://shop.pl/x", cartPrepared = false).opensProductPage(null))
        val product = Product(shopId = "s", sku = "1")
        assertEquals(page, applyLiveDetails(product, ProductDetails(productUrl = page)).first.productUrl)
    }

    @Test
    fun sortsByPriceWithUnpricedRowsLast() {
        val rows = listOf(product("a", 30.0), product("b", null), product("c", 10.0), product("d", 20.0))
        assertEquals(listOf("a", "b", "c", "d"), SearchViewModel.sorted(rows, Sort.Relevance).map { it.sku })
        assertEquals(listOf("c", "d", "a", "b"), SearchViewModel.sorted(rows, Sort.PriceLowToHigh).map { it.sku })
        assertEquals(listOf("a", "d", "c", "b"), SearchViewModel.sorted(rows, Sort.PriceHighToLow).map { it.sku })
    }

    @Test
    fun sortGroupsMixedCurrenciesInsteadOfComparingThem() {
        val rows = listOf(product("eur-9", 9.0, "EUR"), product("pln-40", 40.0), product("pln-5", 5.0), product("eur-2", 2.0, "EUR"), product("pln-20", 20.0))
        assertEquals(listOf("pln-5", "pln-20", "pln-40", "eur-2", "eur-9"), SearchViewModel.sorted(rows, Sort.PriceLowToHigh).map { it.sku })
    }

    @Test
    fun removesOneHistoryEntry() {
        val prefs = FakePrefs()
        val app = AppModel(InMemorySecretStore(), prefs, FakeTransport(ArrayDeque()))
        listOf("tea", "coffee", "mug").forEach(app::addHistory)
        app.removeHistory("coffee")
        assertEquals(listOf("mug", "tea"), app.state.value.history)
        assertEquals(listOf("mug", "tea"), AppModel(InMemorySecretStore(), prefs, FakeTransport(ArrayDeque())).state.value.history)
    }
}
