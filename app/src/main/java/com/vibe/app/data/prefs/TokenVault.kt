package com.vibe.app.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** OAuth tokens are encrypted with an Android Keystore master key, never logged. */
class TokenVault(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "vibe_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun access(): String = prefs.getString("access", "").orEmpty()
    fun refresh(): String = prefs.getString("refresh", "").orEmpty()
    fun save(access: String, refresh: String) { prefs.edit().putString("access", access).putString("refresh", refresh).apply() }
    fun clear() { prefs.edit().clear().commit() }
}
