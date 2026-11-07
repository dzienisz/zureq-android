package com.dzienisz.zureqshopper.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.Credits
import com.dzienisz.zureqshopper.api.FreshnessLabel
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
    Credits.State.OK -> MaterialTheme.colorScheme.primary
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
fun CreditsBadge(usage: Usage?, modifier: Modifier = Modifier) {
    val state = usage?.let { Credits.state(it) } ?: Credits.State.OK
    val text = usage?.remaining?.let { Credits.format(it) } ?: "—"
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, color = MaterialTheme.colorScheme.onSurface) },
        leadingIcon = {
            Surface(shape = RoundedCornerShape(50), color = creditsTint(state), modifier = Modifier.size(8.dp)) {}
        },
        modifier = modifier.padding(end = 4.dp),
    )
}

@Composable
fun ProductImage(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).clip(shape),
        error = { Placeholder() },
        loading = { Placeholder() },
    )
}

@Composable
private fun Placeholder() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
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
