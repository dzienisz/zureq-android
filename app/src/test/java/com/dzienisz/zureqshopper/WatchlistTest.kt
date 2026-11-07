package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.data.Watchlist
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {
    private val prefs = FakePrefs()
    private var clock = 1_000L
    private val watchlist = Watchlist(prefs) { clock }
    private val a = Product(shopId = "shop", sku = "A", name = "Alpha", price = 10.0, currency = "PLN")
    private val b = Product(shopId = "shop", sku = "B", name = "Beta", price = 20.0, currency = "PLN")

    @Test
    fun toggleAddsNewestFirstAndRemoves() {
        watchlist.toggle(a)
        clock = 2_000
        watchlist.toggle(b)
        assertEquals(listOf("shop|B", "shop|A"), watchlist.items.map { it.id })
        assertEquals(10.0, watchlist.items[1].addedPrice)
        assertEquals(2_000L, watchlist.items[0].addedAt)
        watchlist.toggle(a)
        assertFalse(watchlist.contains(a))
    }

    @Test
    fun persistsAcrossInstances() {
        watchlist.toggle(a)
        val reloaded = Watchlist(prefs) { clock }
        assertEquals(listOf("shop|A"), reloaded.items.map { it.id })
    }

    @Test
    fun recordUpdatesPriceChangeOnlyForWatched() {
        watchlist.toggle(a)
        watchlist.record(a.copy(price = 8.0), 5_000)
        watchlist.record(b.copy(price = 1.0), 5_000)
        val item = watchlist.items.single()
        assertEquals(-2.0, item.priceChange)
        assertEquals(5_000L, item.checkedAt)
        assertNull(watchlist.items.single().copy(product = a).priceChange)
    }

    @Test
    fun refreshAppliesLivePricesAndReportsPartialFailures() = runTest {
        watchlist.toggle(a)
        watchlist.toggle(b)
        watchlist.refresh { product ->
            if (product.sku == "B") throw ZureqException.unavailable
            ProductDetails(price = 12.5, inStock = false)
        }
        val state = watchlist.state.value
        assertFalse(state.isRefreshing)
        assertEquals(Watchlist.RefreshError.Partial(1, 2), state.refreshError)
        val alpha = state.items.first { it.id == "shop|A" }
        assertEquals(12.5, alpha.product.price)
        assertEquals(false, alpha.product.inStock)
        assertEquals(2.5, alpha.priceChange)
    }

    @Test
    fun refreshStopsOnBatchEndingError() = runTest {
        watchlist.toggle(a)
        watchlist.toggle(b)
        var calls = 0
        watchlist.refresh { calls += 1; throw ZureqException(ZureqException.Code.CREDITS_EXHAUSTED) }
        assertEquals(1, calls)
        assertTrue(watchlist.state.value.refreshError is Watchlist.RefreshError.Stopped)
    }
}
