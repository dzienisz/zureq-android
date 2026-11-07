package com.dzienisz.zureqshopper.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ProductDetails
import com.dzienisz.zureqshopper.api.of
import com.dzienisz.zureqshopper.data.WatchedItem
import com.dzienisz.zureqshopper.data.Watchlist
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(app: AppModel, onOpen: (Product) -> Unit) {
    val watchlist = app.watchlist
    val state by watchlist.state.collectAsStateWithLifecycle()
    val appState by app.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun refresh() = scope.launch {
        watchlist.refresh { product ->
            app.call(
                "get_product",
                kotlinx.serialization.json.JsonObject.of("shopId" to JsonPrimitive(product.shopId), "sku" to JsonPrimitive(product.sku)),
                ProductDetails.serializer(),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watchlist_title)) },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { refresh() }, enabled = state.items.isNotEmpty()) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.watchlist_refresh))
                        }
                    }
                    CreditsBadge(appState.usage, Modifier.padding(end = 12.dp))
                },
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.StarBorder,
                title = stringResource(R.string.watchlist_empty_title),
                body = stringResource(R.string.watchlist_empty_body),
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                state.refreshError?.let { error ->
                    item {
                        Text(
                            when (error) {
                                is Watchlist.RefreshError.Stopped -> errorText(error.error)
                                is Watchlist.RefreshError.Partial -> stringResource(R.string.watchlist_partial_error, error.failed, error.total)
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    WatchRow(item, onClick = { onOpen(item.product) }, onRemove = { watchlist.remove(item.product) })
                    HorizontalDivider()
                }
                item {
                    Text(
                        pluralStringResource(R.plurals.watchlist_footer, state.items.size, state.items.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchRow(item: WatchedItem, onClick: () -> Unit, onRemove: () -> Unit) {
    val product = item.product
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ProductImage(product.imageUrl, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(product.shopName ?: product.shopId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(PriceFormat.string(product.price, product.currency), style = MaterialTheme.typography.titleMedium)
                item.priceChange?.let { change ->
                    val down = change < 0
                    val tint = if (down) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    Icon(
                        if (down) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(if (down) R.string.watchlist_price_down else R.string.watchlist_price_up),
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        PriceFormat.string(abs(change), product.currency),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (product.inStock == false) {
                    Text(stringResource(R.string.out_of_stock), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    item.checkedAt?.let {
                        stringResource(R.string.watchlist_checked, DateUtils.getRelativeTimeSpanString(it).toString())
                    } ?: stringResource(R.string.watchlist_not_checked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
