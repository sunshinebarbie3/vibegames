package com.vibe.app.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** OAuth tokens are encrypted with an Android Keystore master key, never logged. */
class TokenVault(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "vibe_tokens",
        masterKeyAlias,
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun access(): String = prefs.getString("access", "").orEmpty()
    fun refresh(): String = prefs.getString("refresh", "").orEmpty()
    fun save(access: String, refresh: String) { prefs.edit().putString("access", access).putString("refresh", refresh).apply() }
    fun clear() { prefs.edit().clear().commit() }
}
