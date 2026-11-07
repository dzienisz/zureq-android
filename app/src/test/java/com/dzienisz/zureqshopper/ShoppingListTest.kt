package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.CheckoutLink
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.ShopInfo
import com.dzienisz.zureqshopper.api.Variant
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.data.ShopMessage
import com.dzienisz.zureqshopper.data.ShoppingList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListTest {
    private val prefs = FakePrefs()
    private var clock = 1_000L
    private val list = ShoppingList(prefs) { clock }
    private val a1 = Product(shopId = "s1", shopName = "Shop One", sku = "A1", name = "Alpha", price = 10.0, currency = "PLN")
    private val a2 = Product(shopId = "s2", shopName = "Shop Two", sku = "A2", name = "Alpha 2", price = 11.0, currency = "PLN")
    private val b1 = Product(shopId = "s1", shopName = "Shop One", sku = "B1", name = "Beta", price = 5.5, currency = "PLN")

    private suspend fun searchBoth() {
        list.setText("alpha, beta")
        list.search { q -> if (q == "alpha") listOf(a1, a2) else listOf(b1) }
    }

    @Test
    fun parsesLinesAndCommas() {
        assertEquals(listOf("a", "b", "c"), ShoppingList.parse(" a ,b\n\nc,"))
        assertEquals(emptyList<List<Int>>(), ShoppingList.chunk(emptyList<Int>(), 2))
        assertEquals(listOf(listOf(1, 2), listOf(3)), ShoppingList.chunk(listOf(1, 2, 3), 2))
        assertEquals(listOf(listOf(1, 2, 3)), ShoppingList.chunk(listOf(1, 2, 3), null))
    }

    @Test
    fun canSearchRespectsLimits() {
        assertFalse(list.state.value.canSearch)
        list.setText("x")
        assertTrue(list.state.value.canSearch)
        assertEquals(3, list.state.value.maxSearchCost)
        list.setText((1..11).joinToString(",") { "i$it" })
        assertFalse(list.state.value.canSearch)
    }

    @Test
    fun searchFillsPartsAndGroupsByShop() = runTest {
        searchBoth()
        val s = list.state.value
        assertEquals(listOf("alpha", "beta"), s.parts.map { it.query })
        assertEquals(2, s.parts[0].candidates.size)
        assertTrue(s.groups.isEmpty())
        list.togglePick(a1, 0)
        list.togglePick(b1, 1)
        val groups = list.state.value.groups
        assertEquals(1, groups.size)
        assertEquals("Shop One", groups[0].shopName)
        assertEquals(15.5, groups[0].totals.single().amount, 0.001)
        list.togglePick(a2, 0)
        assertEquals(listOf("s2", "s1"), list.state.value.groups.map { it.shopId })
    }

    @Test
    fun searchStopsOnBatchEndingError() = runTest {
        list.setText("alpha, beta")
        var calls = 0
        list.search { calls += 1; throw ZureqException(ZureqException.Code.CREDITS_EXHAUSTED) }
        assertEquals(1, calls)
        assertEquals(ZureqException.Code.CREDITS_EXHAUSTED, list.state.value.searchError?.code)
        assertFalse(list.state.value.isSearching)
    }

    @Test
    fun editingTextResetsResultsAndPersists() = runTest {
        searchBoth()
        list.setText("alpha, beta ")
        assertEquals(2, list.state.value.parts.size)
        list.setText("alpha")
        assertTrue(list.state.value.parts.isEmpty())
        val reloaded = ShoppingList(prefs) { clock }
        assertEquals("alpha", reloaded.state.value.text)
    }

    @Test
    fun loadDetailsAppliesVariantsAndAutoPicksSingle() = runTest {
        searchBoth()
        list.togglePick(a1, 0)
        assertTrue(list.state.value.parts[0].needsDetails)
        list.loadDetails(0) { ProductDetails(price = 9.0, variants = listOf(Variant("A1-RED"))) }
        val part = list.state.value.parts[0]
        assertEquals(9.0, part.pick?.price)
        assertEquals("A1-RED", part.variantSku)
        assertFalse(part.needsDetails)
        assertEquals("A1-RED", part.checkoutSku)
    }

    @Test
    fun checkoutAsksForVariantThenCreatesLink() = runTest {
        searchBoth()
        list.togglePick(a1, 0)
        list.togglePick(b1, 1)
        val details = mapOf(
            "A1" to ProductDetails(price = 10.0, variants = listOf(Variant("A1-S"), Variant("A1-M"))),
            "B1" to ProductDetails(price = 5.5),
        )
        val group = list.state.value.groups.single()
        var linkCalls = 0
        suspend fun run() = list.checkout(group, { details.getValue(it.sku) }, { ShopInfo(checkoutLinkSupported = true) }) { _, skus ->
            linkCalls += 1
            CheckoutLink("https://shop.example/cart?skus=" + skus.joinToString("+"))
        }
        run()
        assertEquals(ShopMessage.ChooseVariant(listOf("alpha")), list.state.value.shopMessages["s1"])
        assertEquals(0, linkCalls)
        list.setVariant("A1-M", 0)
        run()
        val cart = list.state.value.carts.getValue("s1").single()
        assertEquals("https://shop.example/cart?skus=A1-M+B1", cart.url)
        assertNull(list.state.value.shopMessages["s1"])
        assertEquals(1, linkCalls)
    }

    @Test
    fun checkoutSplitsByMaxLinesAndCachesShopInfo() = runTest {
        searchBoth()
        list.togglePick(a1, 0)
        list.togglePick(b1, 1)
        var shopCalls = 0
        val group = list.state.value.groups.single()
        suspend fun run() = list.checkout(group, { ProductDetails(price = 1.0) }, { shopCalls += 1; ShopInfo(true, 1.0) }) { _, skus ->
            CheckoutLink("https://shop.example/${skus.single()}")
        }
        run()
        val carts = list.state.value.carts.getValue("s1")
        assertEquals(listOf("https://shop.example/A1", "https://shop.example/B1"), carts.map { it.url })
        assertEquals(2, carts[0].count)
        run()
        assertEquals(1, shopCalls)
        clock += 25L * 60 * 60 * 1000
        run()
        assertEquals(2, shopCalls)
    }

    @Test
    fun checkoutReportsUnsupportedShopAndInvalidLink() = runTest {
        searchBoth()
        list.togglePick(a1, 0)
        val group = list.state.value.groups.single()
        list.checkout(group, { ProductDetails(price = 1.0) }, { ShopInfo(checkoutLinkSupported = false) }) { _, _ -> CheckoutLink("x") }
        assertEquals(ShopMessage.NoCheckoutLinks, list.state.value.shopMessages["s1"])
        val other = ShoppingList(prefs) { clock }
        other.setText("alpha")
        other.search { listOf(a2) }
        other.togglePick(a2, 0)
        other.checkout(other.state.value.groups.single(), { ProductDetails(price = 1.0) }, { ShopInfo(true) }) { _, _ -> CheckoutLink("javascript:alert(1)") }
        val cart = other.state.value.carts.getValue("s2").single()
        assertNull(cart.url)
        assertEquals(ZureqException.Code.PARSE_ERROR, cart.error?.code)
        assertNotNull(cart.error)
    }
}
