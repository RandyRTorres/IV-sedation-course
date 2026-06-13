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

    /** Thread ids the user has archived (hidden from the main list). */
    var archivedThreads: Set<Long>
        get() = prefs.getStringSet(KEY_ARCHIVED, emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        private set(value) = prefs.edit()
            .putStringSet(KEY_ARCHIVED, value.map { it.toString() }.toSet()).apply()

    fun archive(threadId: Long) { archivedThreads = archivedThreads + threadId }
    fun unarchive(threadId: Long) { archivedThreads = archivedThreads - threadId }

    /** Pinch-to-zoom multiplier for chat text size. */
    var chatTextScale: Float
        get() = prefs.getFloat(KEY_TEXT_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SCALE, value).apply()

    companion object {
        private const val KEY_API = "claude_api_key"
        private const val KEY_TONE = "reply_tone"
        private const val KEY_ARCHIVED = "archived_threads"
        private const val KEY_TEXT_SCALE = "chat_text_scale"
        const val DEFAULT_TONE = "friendly and concise"
    }
}
