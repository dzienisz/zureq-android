package com.dzienisz.zureqshopper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.Credits
import com.dzienisz.zureqshopper.api.FreshnessLabel
import com.dzienisz.zureqshopper.api.PriceFormat
import com.dzienisz.zureqshopper.api.StockLabel
import com.dzienisz.zureqshopper.api.Usage
import com.dzienisz.zureqshopper.api.ZureqException

/** Localized text for an error; API errors keep the shop/server message. */
@Composable
fun errorText(error: Throwable?, fallback: Int = R.string.error_generic): String {
    val e = error as? ZureqException ?: return stringResource(fallback)
    return when (e.code) {
        ZureqException.Code.NO_KEY -> stringResource(R.string.error_no_key)
        ZureqException.Code.SIGN_IN_ENDED -> stringResource(R.string.error_sign_in_ended)
        ZureqException.Code.BAD_KEY -> stringResource(R.string.error_bad_key)
        ZureqException.Code.RATE_LIMIT -> stringResource(R.string.error_rate_limit)
        ZureqException.Code.CREDITS_EXHAUSTED -> stringResource(R.string.error_credits_exhausted)
        ZureqException.Code.INVALID_ARGS -> stringResource(R.string.error_invalid_args)
        ZureqException.Code.UNAVAILABLE -> stringResource(R.string.error_unavailable)
        ZureqException.Code.API_ERROR -> e.serverMessage ?: stringResource(R.string.error_api)
        ZureqException.Code.NETWORK_ERROR -> stringResource(R.string.error_network)
        ZureqException.Code.PARSE_ERROR -> stringResource(R.string.error_parse)
        ZureqException.Code.AUTH_CANCELLED -> stringResource(R.string.error_auth_cancelled)
        ZureqException.Code.AUTH_FAILED -> stringResource(R.string.error_auth_failed)
        ZureqException.Code.INVALID_PRICE -> stringResource(R.string.error_invalid_price)
    }
}

@Composable
fun stockText(label: StockLabel): String = when (label) {
    StockLabel.OutOfStock -> stringResource(R.string.out_of_stock)
    StockLabel.Available -> stringResource(R.string.available)
    is StockLabel.Quantity -> pluralStringResource(R.plurals.in_stock_count, label.count, label.count)
}

@Composable
fun freshnessText(label: FreshnessLabel): String? = when (label) {
    FreshnessLabel.Live -> stringResource(R.string.fresh_live)
    FreshnessLabel.JustNow -> stringResource(R.string.fresh_just_now)
    is FreshnessLabel.MinutesAgo -> stringResource(R.string.fresh_minutes, label.minutes)
    is FreshnessLabel.HoursAgo -> stringResource(R.string.fresh_hours, label.hours)
    is FreshnessLabel.DaysAgo -> stringResource(R.string.fresh_days, label.days)
    FreshnessLabel.Unknown -> null
}

@Composable
fun creditsTint(state: Credits.State): Color = when (state) {
    Credits.State.OK -> MaterialTheme.colorScheme.secondary
    Credits.State.LOW -> Color(0xFFE08A00)
    Credits.State.EXHAUSTED -> MaterialTheme.colorScheme.error
}

@Composable
fun creditsMessage(state: Credits.State): String? = when (state) {
    Credits.State.OK -> null
    Credits.State.LOW -> stringResource(R.string.credits_low)
    Credits.State.EXHAUSTED -> stringResource(R.string.credits_exhausted)
}

/** Remaining-credits pill shown in the top bar. */
@Composable
fun CreditsBadge(usage: Usage?, modifier: Modifier = Modifier, sessionCredits: Double = 0.0) {
    val state = usage?.let { Credits.state(it) } ?: Credits.State.OK
    val text = usage?.remaining?.let { Credits.format(it) } ?: "\u2014"
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")) },
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        leadingIcon = {
            Surface(shape = RoundedCornerShape(50), color = creditsTint(state), modifier = Modifier.size(8.dp)) {}
        },
        modifier = modifier.padding(end = 4.dp),
    )
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.credits_title)) },
            text = { CreditsSummary(usage, sessionCredits) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) { Text(stringResource(R.string.filters_done)) }
            },
        )
    }
}

/** Product photos are shot for a white backdrop, so the tile stays white in dark mode too. */
@Composable
fun ProductImage(url: String?, size: Dp, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).clip(MaterialTheme.shapes.small).background(Color.White),
        error = { Placeholder() },
        loading = { Placeholder() },
    )
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize().background(Color(0xFFF1F3F4)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFFA3A9AE))
    }
}

/** The ZUREQ mark; the launcher foreground carries adaptive-icon padding, so it is drawn oversized and clipped. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.42f))
    }
}

@Composable
fun BrandLockup(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(30.dp)
        Text("ZUREQ", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.14.em))
        Text("Shopper", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bordered card used for product rows and grouped content. */
@Composable
fun ListCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border, content = content)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border, content = content)
}

@Composable
fun PriceText(price: Double?, currency: String?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium) {
    Text(PriceFormat.string(price, currency), style = style.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), modifier = modifier)
}

@Composable
fun OutOfStockChip() {
    Surface(shape = RoundedCornerShape(50), color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))) {
        Text(
            stringResource(R.string.out_of_stock),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun shopCountText(model: AppModel): String {
    val state by model.state.collectAsStateWithLifecycle()
    val markets = state.markets
    val total = markets.filter { it.code != "EU" }.sumOf { it.shops ?: 0 }
    if (total < 1000) return stringResource(R.string.search_thousands_of)
    return java.text.NumberFormat.getIntegerInstance(LocalContext.current.resources.configuration.locales[0]).format(total / 100 * 100) + "+"
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
