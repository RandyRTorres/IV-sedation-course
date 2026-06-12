package com.textoverlay.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device storage for the user's Claude API key and preferences.
 * The key never leaves the device except in the Authorization header of a
 * direct HTTPS call to api.anthropic.com.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** Optional: how you'd like replies to sound. Folded into the prompt. */
    var tone: String
        get() = prefs.getString(KEY_TONE, DEFAULT_TONE).orEmpty()
        set(value) = prefs.edit().putString(KEY_TONE, value.trim()).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        private const val KEY_API = "claude_api_key"
        private const val KEY_TONE = "reply_tone"
        const val DEFAULT_TONE = "friendly and concise"
    }
}
