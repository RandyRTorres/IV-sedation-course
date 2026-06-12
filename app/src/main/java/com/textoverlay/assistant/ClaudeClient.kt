package com.textoverlay.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What Claude produces for a conversation: a short summary and a ready reply. */
data class Suggestion(
    val summary: String,
    val suggestedReply: String
)

/** An image to send to Claude's vision model. */
data class ClaudeImage(
    val mediaType: String,
    val bytes: ByteArray
)

/**
 * Thin wrapper over the Claude Messages API (POST /v1/messages), with vision
 * support so Claude can read picture (MMS) messages.
 */
class ClaudeClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Summarize a conversation with [contactName] and propose my next reply.
     * [transcript] is the conversation rendered as "Them:" / "Me:" lines, and
     * [images] are any pictures from the thread that Claude should look at.
     */
    suspend fun analyze(
        contactName: String,
        transcript: String,
        images: List<ClaudeImage> = emptyList()
    ): Suggestion = withContext(Dispatchers.IO) {
        val apiKey = requireKey()

        val userText = buildString {
            append("Here is a text-message conversation between me and ")
            append(contactName)
            append(". \"Them:\" is ").append(contactName)
            append(", \"Me:\" is me.\n\n")
            append(transcript)
            if (images.isNotEmpty()) {
                append("\n\nThe conversation includes ")
                append(if (images.size == 1) "an image" else "${images.size} images")
                append(" (attached). Take ")
                append(if (images.size == 1) "it" else "them")
                append(" into account.")
            }
            append("\n\nDo two things:\n")
            append("1. summary: one or two sentences capturing where the conversation stands")
            if (images.isNotEmpty()) append(", what's in the image")
            append(", and what (if anything) they want from me.\n")
            append("2. suggested_reply: a ready-to-send next message I could send back, written in a ")
            append(settings.tone)
            append(" tone, in the first person as me. No preamble, just the reply text.")
        }

        val content = JSONArray()
        images.forEach { content.put(imageBlock(it)) }
        content.put(JSONObject().put("type", "text").put("text", userText))

        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("summary", JSONObject().put("type", "string"))
                    .put("suggested_reply", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("summary").put("suggested_reply"))
            .put("additionalProperties", false)

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put(
                "output_config", JSONObject().put(
                    "format", JSONObject().put("type", "json_schema").put("schema", schema)
                )
            )
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val raw = post(apiKey, body)
        parseSuggestion(raw)
    }

    /** Describe a single picture in a sentence or two (plain text). */
    suspend fun describeImage(image: ClaudeImage): String = withContext(Dispatchers.IO) {
        val apiKey = requireKey()
        val content = JSONArray()
            .put(imageBlock(image))
            .put(
                JSONObject().put("type", "text")
                    .put("text", "Describe what's in this picture in one to three sentences.")
            )
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 512)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        firstText(post(apiKey, body)).ifBlank { "(no description)" }
    }

    private fun imageBlock(image: ClaudeImage): JSONObject =
        JSONObject().put("type", "image").put(
            "source", JSONObject()
                .put("type", "base64")
                .put("media_type", normalizeType(image.mediaType))
                .put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
        )

    private fun normalizeType(ct: String): String = when {
        ct.contains("png") -> "image/png"
        ct.contains("gif") -> "image/gif"
        ct.contains("webp") -> "image/webp"
        else -> "image/jpeg"
    }

    private fun requireKey(): String {
        val apiKey = settings.apiKey
        check(apiKey.isNotBlank()) { "No API key set. Add your Claude API key in Settings." }
        return apiKey
    }

    private fun post(apiKey: String, body: JSONObject): String {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(humanError(response.code, raw))
            return raw
        }
    }

    private fun firstText(raw: String): String {
        val root = JSONObject(raw)
        if (root.optString("stop_reason") == "refusal") {
            throw IllegalStateException("Claude declined to respond.")
        }
        val content = root.optJSONArray("content") ?: return ""
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") return block.optString("text")
        }
        return ""
    }

    private fun parseSuggestion(raw: String): Suggestion {
        val payload = firstText(raw)
        if (payload.isBlank()) throw IllegalStateException("Claude returned no text.")
        val parsed = JSONObject(payload)
        return Suggestion(
            summary = parsed.optString("summary").ifBlank { "(no summary)" },
            suggestedReply = parsed.optString("suggested_reply").ifBlank { "(no reply)" }
        )
    }

    private fun humanError(code: Int, raw: String): String {
        val apiMessage = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return when (code) {
            401 -> "Invalid API key. Check it in Settings."
            429 -> "Rate limited by Claude. Try again in a moment."
            in 500..599 -> "Claude is temporarily unavailable. Try again."
            else -> apiMessage?.takeIf { it.isNotBlank() } ?: "Claude API error ($code)."
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
