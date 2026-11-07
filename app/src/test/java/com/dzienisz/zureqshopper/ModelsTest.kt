package com.dzienisz.zureqshopper

import com.dzienisz.zureqshopper.api.Credits
import com.dzienisz.zureqshopper.api.Freshness
import com.dzienisz.zureqshopper.api.FreshnessLabel
import com.dzienisz.zureqshopper.api.Location
import com.dzienisz.zureqshopper.api.MarketsResponse
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.StockLabel
import com.dzienisz.zureqshopper.api.Usage
import com.dzienisz.zureqshopper.api.ZureqClient
import com.dzienisz.zureqshopper.api.ZureqException
import com.dzienisz.zureqshopper.api.decodeHtmlEntities
import com.dzienisz.zureqshopper.ui.SearchViewModel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ModelsTest {
    @Test
    fun decodesHtmlEntities() {
        assertEquals("Kawa – 1 kg & more", "Kawa &#8211; 1 kg &amp; more".decodeHtmlEntities())
        assertEquals("A&B", "A&B".decodeHtmlEntities())
        assertEquals("“q”", "&#x201C;q&#x201D;".decodeHtmlEntities())
    }

    @Test
    fun productDetailsReadsFlexibleNumbers() {
        val details = ZureqClient.json.decodeFromString(
            ProductDetails.serializer(),
            """{"price":"19,99x","stockQty":"5","inStock":true,"variants":[{"sku":"v1","name":"Red"}],"name":"Cup &amp; Saucer"}""",
        )
        assertNull(details.price)
        assertEquals(5, details.stockQty)
        assertEquals("Red", details.variants!![0].displayLabel)
        assertEquals("Cup & Saucer", details.displayName)
        assertEquals(StockLabel.Quantity(5), Freshness.stock(details))
    }

    @Test
    fun stockLabels() {
        assertEquals(StockLabel.OutOfStock, Freshness.stock(ProductDetails(inStock = false)))
        assertEquals(StockLabel.Available, Freshness.stock(ProductDetails(inStock = true, stockQtyValue = 99.0)))
    }

    @Test
    fun freshnessLabels() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(FreshnessLabel.Live, Freshness.label(ProductDetails(live = true), now))
        assertEquals(FreshnessLabel.MinutesAgo(5), Freshness.label(ProductDetails(syncedAt = "2026-01-01T11:55:00.000Z"), now))
        assertEquals(FreshnessLabel.HoursAgo(3), Freshness.label(ProductDetails(syncedAt = "2026-01-01T09:00:00+00:00"), now))
        assertEquals(FreshnessLabel.DaysAgo(2), Freshness.label(ProductDetails(syncedAt = "2025-12-30T11:00:00Z"), now))
        assertEquals(FreshnessLabel.Unknown, Freshness.label(ProductDetails(syncedAt = "garbage"), now))
    }

    @Test
    fun creditsCostAndState() {
        assertEquals(3.0, Credits.cost("search_products", 5), 0.0)
        assertEquals(1.0, Credits.cost("search_products", 0), 0.0)
        assertEquals(0.0, Credits.cost("get_usage"), 0.0)
        assertEquals(Credits.State.EXHAUSTED, Credits.state(Usage(remaining = 0.0, included = 100.0)))
        assertEquals(Credits.State.EXHAUSTED, Credits.state(Usage(exceeded = true)))
        assertEquals(Credits.State.LOW, Credits.state(Usage(remaining = 4.0, included = 100.0)))
        assertEquals(Credits.State.OK, Credits.state(Usage(remaining = 50.0, included = 100.0)))
        assertEquals("1,234", Credits.format(1234.4, Locale.US))
        assertEquals("—", Credits.format(null))
    }

    @Test
    fun locationArguments() {
        assertEquals(emptyMap<String, JsonPrimitive>(), Location.arguments(" "))
        assertEquals(mapOf("region" to JsonPrimitive("EU")), Location.arguments("eu"))
        assertEquals(mapOf("country" to JsonPrimitive("PL")), Location.arguments("pl"))
    }

    @Test
    fun priceFormatting() {
        assertEquals("€12.50", PriceFormat.string(12.5, "EUR", Locale.US))
        assertEquals("12.50", PriceFormat.string(12.5, null, Locale.US))
        assertEquals("—", PriceFormat.string(null, "EUR"))
        assertNull(PriceFormat.parseMaxPrice(" "))
        assertEquals(12.5, PriceFormat.parseMaxPrice("12,5")!!, 0.0)
    }

    @Test(expected = ZureqException::class)
    fun rejectsNonPositivePrice() {
        PriceFormat.parseMaxPrice("0")
    }

    @Test
    fun searchArguments() {
        val state = SearchViewModel.State(market = "EU", maxPrice = "20", inStockOnly = false)
        val args = SearchViewModel.arguments(state, "mug")
        assertEquals(JsonPrimitive("mug"), args["query"])
        assertEquals(JsonPrimitive("EU"), args["region"])
        assertEquals(JsonPrimitive(20.0), args["maxPrice"])
        assertEquals(JsonPrimitive(false), args["inStockOnly"])
        assertNull(args["country"])
    }

    @Test
    fun marketsListCountriesThenEu() {
        val response = ZureqClient.json.decodeFromString(
            MarketsResponse.serializer(),
            """{"countries":[{"country":"pl","shops":120},{"country":"DE","shops":300}],"regions":[{"key":"EU","name":"European Union","shops":900},{"key":"WORLD"}]}""",
        )
        val markets = response.markets(Locale.US)
        assertEquals(listOf("PL", "DE", "EU"), markets.map { it.code })
        assertEquals("Poland", markets[0].label)
        assertEquals(900, markets[2].shops)
    }

    @Test
    fun extractsKeyFromPastedText() {
        assertEquals("zmcp_abc-123", AppModel.extractKey("  Your key: zmcp_abc-123 (keep it secret) "))
        assertEquals("plain", AppModel.extractKey(" plain "))
    }

    @Test
    fun retryableErrors() {
        assertTrue(ZureqException.rateLimit.isRetryable)
        assertTrue(ZureqException(ZureqException.Code.API_ERROR, "Shop timed out").isRetryable)
        assertTrue(!ZureqException(ZureqException.Code.API_ERROR, "Nope").isRetryable)
        assertTrue(ZureqException(ZureqException.Code.CREDITS_EXHAUSTED).stopsBatch)
    }
}
