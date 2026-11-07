package com.dzienisz.zureqshopper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Small string store for secrets (API key, OAuth tokens); the Android version is encrypted at rest. */
interface SecretStore {
    fun read(account: String): String?
    fun write(value: String, account: String): Boolean
    fun delete(account: String)
}

class EncryptedSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "zureq_secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(account: String): String? = prefs.getString(account, null)

    override fun write(value: String, account: String): Boolean = prefs.edit().putString(account, value).commit()

    override fun delete(account: String) {
        prefs.edit().remove(account).apply()
    }
}

class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun read(account: String): String? = values[account]
    override fun write(value: String, account: String): Boolean { values[account] = value; return true }
    override fun delete(account: String) { values.remove(account) }
}
