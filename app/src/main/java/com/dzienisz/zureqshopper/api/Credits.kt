package com.dzienisz.zureqshopper.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.Locale

object Credits {
    val toolCosts: Map<String, Double> = mapOf(
        "search_products" to 3.0,
        "get_product" to 1.0,
        "create_checkout_link" to 1.0,
        "get_shop" to 1.0,
        "list_shops" to 1.0,
        "list_categories" to 1.0,
        "list_markets" to 0.0,
        "get_usage" to 0.0,
    )

    /** An empty search costs 1 credit instead of 3. */
    fun cost(tool: String, resultCount: Int? = null): Double {
        if (tool == "search_products" && resultCount == 0) return 1.0
        return toolCosts[tool] ?: 0.0
    }

    enum class State { OK, LOW, EXHAUSTED }

    fun state(usage: Usage): State {
        if (usage.exceeded == true || (usage.remaining ?: 1.0) <= 0) return State.EXHAUSTED
        return if (usage.isLow) State.LOW else State.OK
    }

    fun format(value: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return "—"
        return NumberFormat.getIntegerInstance(locale).format(Math.round(value))
    }
}

object Location {
    /** search_products takes "EU" as `region`; `country: "EU"` silently returns nothing. */
    fun arguments(code: String?): Map<String, JsonPrimitive> {
        val location = code.orEmpty().trim().uppercase()
        if (location.isEmpty()) return emptyMap()
        return if (location == "EU") mapOf("region" to JsonPrimitive("EU")) else mapOf("country" to JsonPrimitive(location))
    }
}

sealed interface StockLabel {
    data object OutOfStock : StockLabel
    data object Available : StockLabel
    data class Quantity(val count: Int) : StockLabel
}

sealed interface FreshnessLabel {
    data object Live : FreshnessLabel
    data object JustNow : FreshnessLabel
    data class MinutesAgo(val minutes: Int) : FreshnessLabel
    data class HoursAgo(val hours: Int) : FreshnessLabel
    data class DaysAgo(val days: Int) : FreshnessLabel
    data object Unknown : FreshnessLabel
}

object Freshness {
    fun stock(details: ProductDetails): StockLabel {
        if (details.inStock != true) return StockLabel.OutOfStock
        val qty = details.stockQty
        if (qty == null || qty == 99) return StockLabel.Available
        return StockLabel.Quantity(qty)
    }

    fun label(details: ProductDetails, now: Instant = Instant.now()): FreshnessLabel {
        if (details.live == true) return FreshnessLabel.Live
        val synced = details.syncedAt?.let(::parseDate) ?: return FreshnessLabel.Unknown
        val ageSeconds = maxOf(0L, now.epochSecond - synced.epochSecond)
        if (ageSeconds < 60) return FreshnessLabel.JustNow
        val minutes = (ageSeconds / 60).toInt()
        if (minutes < 60) return FreshnessLabel.MinutesAgo(minutes)
        val hours = minutes / 60
        if (hours < 24) return FreshnessLabel.HoursAgo(hours)
        return FreshnessLabel.DaysAgo(hours / 24)
    }

    fun parseDate(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        runCatching { Instant.parse(text) }.getOrNull()
    }
}

object PriceFormat {
    fun string(price: Double?, currency: String?, locale: Locale = Locale.getDefault()): String {
        if (price == null) return "—"
        if (currency == null || currency.length != 3) {
            return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(price)
        }
        val unit = runCatching { Currency.getInstance(currency.uppercase()) }.getOrNull()
            ?: return "${NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(price)} $currency"
        return NumberFormat.getCurrencyInstance(locale).apply { this.currency = unit }.format(price)
    }

    /** Null when empty; throws [ZureqException] with [ZureqException.Code.INVALID_PRICE] when not a positive number. */
    fun parseMaxPrice(raw: String): Double? {
        val text = raw.trim().replace(',', '.')
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0) throw ZureqException(ZureqException.Code.INVALID_PRICE)
        return value
    }
}

fun JsonObject.Companion.of(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject = JsonObject(mapOf(*pairs))
