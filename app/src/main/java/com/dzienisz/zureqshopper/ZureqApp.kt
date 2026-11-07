package com.dzienisz.zureqshopper

import android.app.Application
import com.dzienisz.zureqshopper.data.EncryptedSecretStore

class ZureqApp : Application() {
    val model: AppModel by lazy {
        AppModel(EncryptedSecretStore(this), getSharedPreferences("zureq", MODE_PRIVATE))
    }
}
