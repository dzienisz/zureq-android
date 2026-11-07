package com.dzienisz.zureqshopper.ui

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.dzienisz.zureqshopper.MainActivity
import com.dzienisz.zureqshopper.auth.LoopbackServer
import kotlinx.coroutines.Job

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(model: AppModel) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
        )
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.welcome_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Feature(Icons.Filled.Search, stringResource(R.string.welcome_feature_words))
            Feature(Icons.Filled.Public, stringResource(R.string.welcome_feature_country))
            Feature(Icons.Filled.Bolt, stringResource(R.string.welcome_feature_live))
            Feature(Icons.Filled.ShoppingCart, stringResource(R.string.welcome_feature_checkout))
        }
        SignInSection(model)
        Text(stringResource(R.string.welcome_credits_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Connect with Zureq" (OAuth in the browser, callback on localhost) with the API-key form folded underneath. */
@Composable
fun SignInSection(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var server by remember { mutableStateOf<LoopbackServer?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var showKey by remember { mutableStateOf(false) }
    val waiting = job != null

    fun cancel() {
        job?.cancel()
        job = null
        server?.stop()
        server = null
    }

    fun signIn() {
        if (waiting) return
        error = null
        job = scope.launch {
            val loopback = LoopbackServer()
            server = loopback
            try {
                val request = model.auth.startRequest()
                loopback.start()
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(request.url))
                val callback = loopback.awaitCallback()
                model.completeSignIn(request, callback)
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loopback.stop()
                server = null
                job = null
            }
        }
    }

    DisposableEffect(Unit) { onDispose { cancel() } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (waiting) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.welcome_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            Button(onClick = { signIn() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_sign_in)) }
        }
        error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.welcome_sign_in_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showKey) {
            ApiKeyForm(model)
        } else {
            OutlinedButton(onClick = { showKey = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_have_key)) }
        }
    }
}

@Composable
fun ApiKeyForm(model: AppModel) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving || key.isBlank()) return
        saving = true
        error = null
        scope.launch {
            try {
                model.saveKey(key)
                key = ""
            } catch (e: Exception) {
                error = e
            } finally {
                saving = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.welcome_key_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { save() }, enabled = !saving && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.welcome_connect))
        }
        Text(stringResource(R.string.welcome_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
