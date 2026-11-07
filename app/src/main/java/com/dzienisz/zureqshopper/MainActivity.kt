package com.dzienisz.zureqshopper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.dzienisz.zureqshopper.ui.RootScreen
import com.dzienisz.zureqshopper.ui.theme.ZureqTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as ZureqApp).model
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
}
