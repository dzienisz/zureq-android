package com.dzienisz.zureqshopper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.Product

private val suggestionIds = listOf(
    R.string.suggestion_1, R.string.suggestion_2, R.string.suggestion_3, R.string.suggestion_4, R.string.suggestion_5,
    R.string.suggestion_6, R.string.suggestion_7, R.string.suggestion_8, R.string.suggestion_9, R.string.suggestion_10,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: AppModel, model: SearchViewModel, onOpenProduct: (Product) -> Unit) {
    val appState by app.state.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) { app.loadMarkets() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = { MarketMenu(app, state.market, model::setMarket) },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.search_filters),
                            tint = if (state.hasFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    CreditsBadge(appState.usage, sessionCredits = appState.sessionCredits)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = model::setQuery,
                placeholder = { Text(stringResource(R.string.search_prompt)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { model.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear)) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); model.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (val phase = state.phase) {
                SearchViewModel.Phase.Idle -> IdleContent(app, appState.history, model)
                SearchViewModel.Phase.Loading -> Centered {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.search_loading), modifier = Modifier.padding(top = 12.dp))
                }
                SearchViewModel.Phase.Empty -> Centered {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(stringResource(R.string.search_empty_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    val marketName = appState.markets.firstOrNull { it.code == state.market }?.label ?: state.market
                    Text(
                        if (state.market.isEmpty()) stringResource(R.string.search_empty_body, state.searchedQuery)
                        else stringResource(R.string.search_empty_body_market, state.searchedQuery, marketName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is SearchViewModel.Phase.Failed -> Centered {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(stringResource(R.string.search_failed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    val message = if (phase.error is SearchViewModel.MinLengthException) stringResource(R.string.search_min_chars) else errorText(phase.error)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = { model.search() }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.search_try_again)) }
                }
                SearchViewModel.Phase.Results -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.products, key = { it.id }) { product ->
                        ProductRow(product, onClick = { onOpenProduct(product) })
                        HorizontalDivider()
                    }
                    state.note?.let { note ->
                        item { Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                    }
                    if (state.canLoadMore) {
                        item {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { model.loadMore() },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            ) { Text(stringResource(R.string.search_show_more, SearchViewModel.MAX_LIMIT)) }
                        }
                    } else if (state.isLoadingMore) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFilters) {
        FiltersSheet(state, model, onDismiss = { showFilters = false })
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleContent(app: AppModel, history: List<String>, model: SearchViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        if (history.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.search_intro_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.search_intro_body, shopCountText(app)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionHeader(stringResource(R.string.search_try)) }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestionIds.forEach { id ->
                    val text = stringResource(id)
                    SuggestionChip(onClick = { model.search(text) }, label = { Text(text) })
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.search_recent), Modifier.weight(1f))
                    TextButton(onClick = app::clearHistory) { Text(stringResource(R.string.search_clear)) }
                }
            }
            items(history) { item ->
                ListItem(
                    headlineContent = { Text(item) },
                    leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                    modifier = Modifier.clickable { model.search(item) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun MarketMenu(app: AppModel, selection: String, onSelect: (String) -> Unit) {
    val appState by app.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val label = selection.ifEmpty { stringResource(R.string.search_any) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Icon(Icons.Filled.Public, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.search_any_country)) }, onClick = { onSelect(""); open = false })
            appState.markets.forEach { market ->
                val text = market.shops?.let { "${market.label} ($it)" } ?: market.label
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(market.code); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(state: SearchViewModel.State, model: SearchViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.filters_done)) }
            }
            OutlinedTextField(
                value = state.maxPrice,
                onValueChange = model::setMaxPrice,
                label = { Text(stringResource(R.string.filters_max_price)) },
                placeholder = { Text(stringResource(R.string.filters_no_limit)) },
                supportingText = { Text(stringResource(R.string.filters_currency_note)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filters_in_stock), modifier = Modifier.weight(1f))
                Switch(checked = state.inStockOnly, onCheckedChange = model::setInStockOnly)
            }
            if (state.hasFilters) {
                TextButton(onClick = model::resetFilters) {
                    Text(stringResource(R.string.filters_reset), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ProductRow(product: Product, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductImage(product.imageUrl, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.name, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(product.shopName ?: product.shopId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(PriceFormat.string(product.price, product.currency), style = MaterialTheme.typography.titleMedium)
                if (product.inStock == false) {
                    Text(stringResource(R.string.out_of_stock), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
