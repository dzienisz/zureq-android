package com.dzienisz.zureqshopper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.dzienisz.zureqshopper.auth.ZureqAuth
import com.dzienisz.zureqshopper.ui.RootScreen
import com.dzienisz.zureqshopper.ui.theme.ZureqTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as ZureqApp).model
        handleRedirect(intent)
        if (BuildConfig.DEBUG) {
            // Emulator testing: `adb shell am start -n com.dzienisz.zureqshopper/.MainActivity --es zureqApiKey zmcp_…`
            intent?.getStringExtra("zureqApiKey")?.takeIf { it.isNotBlank() }?.let { key ->
                lifecycleScope.launch { runCatching { model.saveKey(key) } }
            }
        }
        setContent {
            ZureqTheme {
                RootScreen(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == ZureqAuth.APP_SCHEME }?.let { uri ->
            lifecycleScope.launch { (application as ZureqApp).model.finishSignIn(uri.toString()) }
        }
    }
}
