package com.dzienisz.zureqshopper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.CheckoutLink
import com.dzienisz.zureqshopper.api.Location
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.SearchResult
import com.dzienisz.zureqshopper.api.ShopInfo
import com.dzienisz.zureqshopper.data.CartLink
import com.dzienisz.zureqshopper.data.ListPart
import com.dzienisz.zureqshopper.data.ShopGroup
import com.dzienisz.zureqshopper.data.ShopMessage
import com.dzienisz.zureqshopper.data.ShoppingList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(app: AppModel) {
    val list = app.shoppingList
    val state by list.state.collectAsStateWithLifecycle()
    val appState by app.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { app.loadMarkets() }

    suspend fun fetchDetails(product: Product): ProductDetails = app.call(
        "get_product",
        JsonObject(mapOf("shopId" to JsonPrimitive(product.shopId), "sku" to JsonPrimitive(product.sku))),
        ProductDetails.serializer(),
    )

    fun search() = scope.launch {
        val location = Location.arguments(state.market)
        list.search { query ->
            val args = JsonObject(
                mapOf(
                    "query" to JsonPrimitive(query),
                    "inStockOnly" to JsonPrimitive(true),
                    "limit" to JsonPrimitive(ShoppingList.CANDIDATES_PER_ITEM),
                ) + location,
            )
            SearchResult.from(app.call("search_products", args, SearchResult.dtoSerializer()) { SearchResult.from(it).products.size }).products
        }
    }

    fun select(product: Product, part: ListPart) {
        list.togglePick(product, part.id)
        scope.launch { list.loadDetails(part.id, ::fetchDetails) }
    }

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun checkout(group: ShopGroup) = scope.launch {
        list.checkout(
            group,
            fetchDetails = ::fetchDetails,
            fetchShop = { shopId -> app.call("get_shop", JsonObject(mapOf("shopId" to JsonPrimitive(shopId))), ShopInfo.serializer()) },
            createLink = { shopId, skus ->
                app.call(
                    "create_checkout_link",
                    JsonObject(
                        mapOf(
                            "shopId" to JsonPrimitive(shopId),
                            "lines" to JsonArray(skus.map { JsonObject(mapOf("sku" to JsonPrimitive(it), "quantity" to JsonPrimitive(1))) }),
                        ),
                    ),
                    CheckoutLink.serializer(),
                )
            },
        )
        list.state.value.carts[group.shopId]?.singleOrNull()?.url?.let(::open)
    }

    val queries = state.queries
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                navigationIcon = { MarketMenu(app, state.market, list::setMarket) },
                actions = {
                    if (state.text.isNotEmpty()) {
                        TextButton(onClick = { list.clear() }, enabled = !state.isSearching) {
                            Text(stringResource(R.string.list_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    CreditsBadge(appState.usage, Modifier.padding(end = 12.dp))
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.list_items), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = list::setText,
                        placeholder = { Text(stringResource(R.string.list_placeholder)) },
                        minLines = 3,
                        maxLines = 10,
                        enabled = !state.isSearching,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { search() }, enabled = state.canSearch, modifier = Modifier.fillMaxWidth()) {
                        if (state.isSearching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                state.isSearching -> state.progress?.let { i ->
                                    state.parts.getOrNull(i)?.let { stringResource(R.string.list_progress, i + 1, state.parts.size, it.query) }
                                } ?: stringResource(R.string.list_searching)
                                queries.isEmpty() -> stringResource(R.string.list_search)
                                else -> pluralStringResource(R.plurals.list_search_items, queries.size, queries.size)
                            },
                            maxLines = 1,
                        )
                    }
                    Text(
                        when {
                            queries.size > ShoppingList.MAX_ITEMS -> stringResource(R.string.list_too_many, ShoppingList.MAX_ITEMS)
                            queries.isEmpty() -> stringResource(R.string.list_footer, ShoppingList.MAX_ITEMS)
                            else -> stringResource(R.string.list_footer_cost, stringResource(R.string.list_footer, ShoppingList.MAX_ITEMS), state.maxSearchCost)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.searchError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            state.parts.forEach { part ->
                item(key = "part-${part.id}") {
                    HorizontalDivider()
                    Text(part.query, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                    PartRows(part, loading = part.id in state.loadingDetails, select = { select(it, part) }, setVariant = { list.setVariant(it, part.id) })
                }
            }
            val groups = state.groups
            if (groups.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(stringResource(R.string.list_by_shop), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                }
                groups.forEach { group ->
                    item(key = "shop-${group.shopId}") {
                        ShopGroupCard(
                            group,
                            busy = group.shopId in state.busyShops,
                            message = state.shopMessages[group.shopId],
                            carts = state.carts[group.shopId].orEmpty(),
                            onCheckout = { checkout(group) },
                            onOpen = ::open,
                        )
                    }
                }
                item {
                    Text(stringResource(R.string.list_shop_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            } else if (state.parts.any { it.candidates.isNotEmpty() }) {
                item { Text(stringResource(R.string.list_pick_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun PartRows(part: ListPart, loading: Boolean, select: (Product) -> Unit, setVariant: (String?) -> Unit) {
    if (part.candidates.isEmpty()) {
        val text = when {
            part.error != null -> errorText(part.error, R.string.list_search_failed)
            part.searched -> stringResource(R.string.list_no_matches)
            else -> stringResource(R.string.list_not_searched)
        }
        Text(text, color = if (part.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp))
        return
    }
    part.candidates.forEach { product ->
        val picked = part.pickId == product.id
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (picked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 16.dp),
            )
            ProductRow(product) { select(product) }
        }
        if (picked) {
            Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.list_checking_live), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    part.variants.size > 1 -> VariantPicker(part, setVariant)
                }
                part.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun VariantPicker(part: ListPart, setVariant: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = part.variants.firstOrNull { it.sku == part.variantSku }
    OutlinedButton(onClick = { open = true }) {
        Text(stringResource(R.string.list_variant) + ": " + (current?.displayLabel ?: stringResource(R.string.list_choose)))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        part.variants.forEach { variant ->
            DropdownMenuItem(text = { Text(variant.displayLabel) }, onClick = { setVariant(variant.sku); open = false })
        }
    }
}

@Composable
private fun ShopGroupCard(
    group: ShopGroup,
    busy: Boolean,
    message: ShopMessage?,
    carts: List<CartLink>,
    onCheckout: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.shopName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    group.totals.joinToString(" + ") { PriceFormat.string(it.amount, it.currency) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            group.parts.forEach { part ->
                Text("${part.query}: ${part.pick?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Button(onClick = onCheckout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(pluralStringResource(R.plurals.list_checkout_items, group.parts.size, group.parts.size))
            }
            message?.let {
                Text(
                    when (it) {
                        is ShopMessage.StillChecking -> it.error?.let { e -> errorText(e) } ?: stringResource(R.string.list_still_checking, it.query)
                        is ShopMessage.ChooseVariant -> stringResource(R.string.list_choose_variant_for, it.queries.joinToString(", "))
                        ShopMessage.NoCheckoutLinks -> stringResource(R.string.list_no_checkout_links)
                        is ShopMessage.Failed -> errorText(it.error)
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            carts.forEach { cart ->
                val base = if (cart.count == 1) stringResource(R.string.list_cart) else stringResource(R.string.list_cart_n, cart.index + 1, cart.count)
                val label = cart.query?.let { "$base: $it" } ?: base
                if (cart.url != null) {
                    OutlinedButton(onClick = { onOpen(cart.url) }, modifier = Modifier.fillMaxWidth()) { Text("${stringResource(R.string.list_open)} · $label") }
                } else {
                    Text("$label: ${errorText(cart.error)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
