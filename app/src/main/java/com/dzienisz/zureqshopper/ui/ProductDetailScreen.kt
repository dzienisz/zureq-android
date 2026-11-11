package com.dzienisz.zureqshopper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.CheckoutLink
import com.dzienisz.zureqshopper.api.Freshness
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Prefer get_product's live price over the indexed search price; remember the old one if it changed. */
fun applyLiveDetails(product: Product, details: ProductDetails): Pair<Product, Double?> {
    val price = details.price?.takeIf { it.isFinite() } ?: return product to null
    val updated = product.copy(
        price = price,
        currency = details.currency ?: product.currency,
        inStock = details.inStock ?: product.inStock,
        imageUrl = product.imageUrl ?: details.imageUrl,
    )
    val previous = product.price?.takeIf { it != price }
    return updated to previous
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(app: AppModel, initial: Product, onBack: () -> Unit) {
    var product by remember { mutableStateOf(initial) }
    var details by remember { mutableStateOf<ProductDetails?>(null) }
    var previousPrice by remember { mutableStateOf<Double?>(null) }
    var variantSku by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var checkoutError by remember { mutableStateOf<String?>(null) }
    var checkoutFailure by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var openingCheckout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val invalidLink = stringResource(R.string.product_invalid_link)
    val watchState by app.watchlist.state.collectAsStateWithLifecycle()
    val watched = watchState.items.any { it.id == initial.id }

    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(initial.id, attempt) {
        if (details != null) return@LaunchedEffect
        loading = true
        loadError = null
        try {
            val live = app.call(
                "get_product",
                JsonObject(mapOf("shopId" to JsonPrimitive(product.shopId), "sku" to JsonPrimitive(product.sku))),
                ProductDetails.serializer(),
            )
            val (applied, previous) = applyLiveDetails(product, live)
            app.watchlist.record(applied)
            product = applied
            previousPrice = previous
            details = live
            live.variants?.singleOrNull()?.let { variantSku = it.sku }
        } catch (e: Exception) {
            loadError = e
        } finally {
            loading = false
        }
    }

    val needsVariant = !details?.variants.isNullOrEmpty() && variantSku == null

    fun openCheckout() {
        if (openingCheckout) return
        openingCheckout = true
        checkoutError = null
        checkoutFailure = null
        scope.launch {
            try {
                val sku = variantSku ?: product.sku
                val link = app.call(
                    "create_checkout_link",
                    JsonObject(
                        mapOf(
                            "shopId" to JsonPrimitive(product.shopId),
                            "lines" to JsonArray(listOf(JsonObject(mapOf("sku" to JsonPrimitive(sku), "quantity" to JsonPrimitive(1))))),
                        ),
                    ),
                    CheckoutLink.serializer(),
                )
                val uri = Uri.parse(link.checkoutUrl)
                if (uri.scheme != "https" && uri.scheme != "http") {
                    checkoutError = invalidLink
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } catch (e: Exception) {
                checkoutFailure = e
            } finally {
                openingCheckout = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product.shopName ?: stringResource(R.string.product_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { app.watchlist.toggle(product, if (details != null) System.currentTimeMillis() else null) }) {
                        Icon(
                            if (watched) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(if (watched) R.string.watch_remove else R.string.watch_add),
                            tint = if (watched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    checkoutError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    checkoutFailure?.let { Text(errorText(it, R.string.product_checkout_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = { openCheckout() },
                        enabled = !openingCheckout && details?.inStock != false && !needsVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (openingCheckout) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text(
                            stringResource(R.string.product_buy_at, product.shopName ?: stringResource(R.string.product_shop_fallback)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                ProductImage(product.imageUrl, 260.dp, Modifier.padding(12.dp))
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    PriceText(product.price, product.currency, style = MaterialTheme.typography.headlineMedium)
                    previousPrice?.let {
                        Text(
                            PriceFormat.string(it, product.currency),
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                val live = details
                when {
                    live != null -> ListCard {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val net = live.priceNet
                            if (net != null && live.price != null && net != live.price) {
                                LabeledRow(stringResource(R.string.product_price_net), PriceFormat.string(net, product.currency))
                            }
                            LabeledRow(stringResource(R.string.product_stock), stockText(Freshness.stock(live)))
                            freshnessText(Freshness.label(live))?.let { LabeledRow(stringResource(R.string.product_price_info), it) }
                        }
                    }
                    loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.product_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    loadError != null -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                            Text(
                                errorText(loadError, R.string.product_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.search_try_again), color = MaterialTheme.colorScheme.onTertiaryContainer) }
                        }
                    }
                }
                val variants = details?.variants.orEmpty()
                if (variants.isNotEmpty()) {
                    Text(stringResource(R.string.product_variant), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    ListCard {
                        Column {
                            variants.forEach { variant ->
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = variantSku == variant.sku, onClick = { variantSku = variant.sku }),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = variantSku == variant.sku, onClick = { variantSku = variant.sku })
                                    Text(variant.displayLabel)
                                }
                            }
                        }
                    }
                }
                val description = details?.displayDescription
                if (!description.isNullOrEmpty() && description != product.name) {
                    Text(stringResource(R.string.product_description), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.product_checkout_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
