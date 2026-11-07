package com.dzienisz.zureqshopper.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Decodes a number that the API may send as a JSON number or a numeric string; anything else becomes null. */
object FlexibleDouble : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE).nullable

    override fun deserialize(decoder: Decoder): Double? {
        val element = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return null
        return element.doubleOrNull ?: element.contentOrNull?.trim()?.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

@Serializable
data class Product(
    val shopId: String,
    val shopName: String? = null,
    val sku: String,
    val name: String = "",
    @Serializable(with = FlexibleDouble::class) val price: Double? = null,
    val currency: String? = null,
    val inStock: Boolean? = null,
    val category: String? = null,
    val imageUrl: String? = null,
) {
    val id: String get() = "$shopId|$sku"

    /** Falls back to the SKU when the feed has no name and decodes HTML entities left by shop feeds. */
    fun normalized(): Product = copy(name = name.ifBlank { sku }.decodeHtmlEntities())
}

@Serializable
internal data class SearchResultDto(
    val products: List<Product>? = null,
    val items: List<Product>? = null,
    val note: String? = null,
)

data class SearchResult(val products: List<Product>, val note: String? = null) {
    companion object {
        internal fun from(raw: SearchResultDto): SearchResult =
            SearchResult((raw.products ?: raw.items ?: emptyList()).map { it.normalized() }, raw.note)

        internal fun dtoSerializer(): KSerializer<SearchResultDto> = SearchResultDto.serializer()
    }
}

@Serializable
data class Variant(
    val sku: String,
    val label: String? = null,
    private val name: String? = null,
    private val title: String? = null,
) {
    val displayLabel: String get() = label ?: name ?: title ?: sku
}

@Serializable
data class ProductDetails(
    val shopId: String? = null,
    val shopName: String? = null,
    val sku: String? = null,
    val name: String? = null,
    val description: String? = null,
    @Serializable(with = FlexibleDouble::class) val price: Double? = null,
    val currency: String? = null,
    val inStock: Boolean? = null,
    @SerialName("stockQty") @Serializable(with = FlexibleDouble::class) val stockQtyValue: Double? = null,
    val imageUrl: String? = null,
    val live: Boolean? = null,
    val syncedAt: String? = null,
    val variants: List<Variant>? = null,
    val requiresVariant: Boolean? = null,
) {
    val stockQty: Int? get() = stockQtyValue?.toInt()
    val displayName: String? get() = name?.decodeHtmlEntities()
    val displayDescription: String? get() = description?.decodeHtmlEntities()
}

@Serializable
data class CheckoutLink(val checkoutUrl: String, val note: String? = null)

@Serializable
data class ShopInfo(
    val checkoutLinkSupported: Boolean? = null,
    @SerialName("checkoutLinkMaxLines") @Serializable(with = FlexibleDouble::class) val checkoutLinkMaxLinesValue: Double? = null,
) {
    val checkoutLinkMaxLines: Int? get() = checkoutLinkMaxLinesValue?.toInt()
}

@Serializable
data class Usage(
    val plan: String? = null,
    @Serializable(with = FlexibleDouble::class) val used: Double? = null,
    @Serializable(with = FlexibleDouble::class) val included: Double? = null,
    @Serializable(with = FlexibleDouble::class) val remaining: Double? = null,
    val period: String? = null,
    val exceeded: Boolean? = null,
) {
    val isLow: Boolean
        get() {
            val r = remaining ?: return false
            val i = included ?: return false
            return i > 0 && r / i < 0.05
        }
}

@Serializable
data class Market(val code: String, val label: String, val shops: Int? = null)

@Serializable
data class MarketsResponse(val countries: List<Country> = emptyList(), val regions: List<Region>? = null) {
    @Serializable
    data class Country(val country: String, val shops: Int? = null)

    @Serializable
    data class Region(val key: String, val name: String? = null, val shops: Int? = null)

    /** Countries first, then the EU region (WORLD only applies to compare_markets). */
    fun markets(locale: java.util.Locale = java.util.Locale.getDefault()): List<Market> {
        val countryMarkets = countries.map { item ->
            val code = item.country.uppercase()
            val label = java.util.Locale("", code).getDisplayCountry(locale).ifBlank { code }
            Market(code, label, item.shops)
        }
        val regionMarkets = (regions ?: emptyList()).filter { it.key.equals("EU", ignoreCase = true) }
            .map { Market("EU", it.name ?: "European Union", it.shops) }
        return countryMarkets + regionMarkets
    }
}

private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
)

/** Shop feeds often leave HTML entities such as `&#8211;` or `&amp;` in titles. */
fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this
    val out = StringBuilder()
    var i = 0
    while (i < length) {
        val amp = indexOf('&', i)
        if (amp < 0) { out.append(this, i, length); break }
        out.append(this, i, amp)
        val semi = indexOf(';', amp + 1).takeIf { it in (amp + 2)..(amp + 11) }
        val entity = semi?.let { substring(amp + 1, it) }
        val decoded = when {
            entity == null -> null
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
            else -> namedEntities[entity]
        }
        if (decoded != null && semi != null) {
            out.append(decoded)
            i = semi + 1
        } else {
            out.append('&')
            i = amp + 1
        }
    }
    return out.toString()
}

private fun codePoint(value: Int): String? =
    if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null
