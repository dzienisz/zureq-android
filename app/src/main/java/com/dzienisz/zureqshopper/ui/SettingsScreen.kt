package com.dzienisz.zureqshopper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.BuildConfig
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.Credits
import com.dzienisz.zureqshopper.api.Usage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppModel) {
    val state by app.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.refreshUsage() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.credits_title))
            CreditsSummary(state.usage, state.sessionCredits, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Header(stringResource(R.string.settings_account))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_connected_with)) },
                trailingContent = { Text(stringResource(if (state.usesZureqSignIn) R.string.settings_zureq_account else R.string.settings_api_key)) },
            )
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(if (state.usesZureqSignIn) R.string.settings_disconnect_account else R.string.settings_disconnect_key),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable { confirmRemove = true },
            )
            HorizontalDivider()
            Header(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text("zureq.io", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zureq.io"))) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_docs), color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.zureq.io"))) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                trailingContent = { Text(BuildConfig.VERSION_NAME) },
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = {
                Text(stringResource(if (state.usesZureqSignIn) R.string.settings_disconnect_account_confirm else R.string.settings_disconnect_confirm))
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; scope.launch { app.disconnect() } }) {
                    Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun CreditsSummary(usage: Usage?, sessionCredits: Double, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (usage == null) {
            Text(stringResource(R.string.credits_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        val state = Credits.state(usage)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.credits_left, Credits.format(usage.remaining)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            usage.plan?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val included = usage.included
        val used = usage.used
        if (included != null && included > 0 && used != null) {
            LinearProgressIndicator(
                progress = { (used / included).toFloat().coerceIn(0f, 1f) },
                color = creditsTint(state),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            )
            Text(
                usage.period?.let { stringResource(R.string.credits_used_in, Credits.format(used), Credits.format(included), it) }
                    ?: stringResource(R.string.credits_used, Credits.format(used), Credits.format(included)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        creditsMessage(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = creditsTint(state)) }
        if (sessionCredits > 0) {
            Text(stringResource(R.string.credits_session, Credits.format(sessionCredits)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.credits_pricing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
