package com.textoverlay.assistant

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

/**
 * Thin wrapper over the Claude Messages API (POST /v1/messages).
 *
 * Uses structured outputs so we get back a clean {summary, suggested_reply}
 * JSON object instead of free-form prose we'd have to parse.
 */
class ClaudeClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Summarize a conversation with [contactName] and propose my next reply.
     * [transcript] is the conversation rendered as alternating "Them:" / "Me:"
     * lines, oldest first. Runs on the IO dispatcher.
     */
    suspend fun analyze(contactName: String, transcript: String): Suggestion =
        withContext(Dispatchers.IO) {
            val apiKey = settings.apiKey
            check(apiKey.isNotBlank()) { "No API key set. Add your Claude API key in Settings." }

            val body = buildRequestBody(contactName, transcript, settings.tone)
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(humanError(response.code, raw))
                }
                parseSuggestion(raw)
            }
        }

    private fun buildRequestBody(contactName: String, transcript: String, tone: String): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("summary", JSONObject().put("type", "string"))
                    .put("suggested_reply", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("summary").put("suggested_reply"))
            .put("additionalProperties", false)

        val userText = buildString {
            append("Here is a text-message conversation between me and ")
            append(contactName)
            append(". \"Them:\" is ").append(contactName)
            append(", \"Me:\" is me.\n\n")
            append(transcript)
            append("\n\nDo two things:\n")
            append("1. summary: one or two sentences capturing where the conversation stands and what (if anything) they want from me.\n")
            append("2. suggested_reply: a ready-to-send next message I could send back, written in a ")
            append(tone)
            append(" tone, in the first person as me. No preamble, just the reply text.")
        }

        return JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put(
                "output_config", JSONObject().put(
                    "format", JSONObject()
                        .put("type", "json_schema")
                        .put("schema", schema)
                )
            )
            .put(
                "messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userText)
                )
            )
    }

    private fun parseSuggestion(raw: String): Suggestion {
        val root = JSONObject(raw)

        // A safety refusal returns HTTP 200 with stop_reason "refusal".
        if (root.optString("stop_reason") == "refusal") {
            throw IllegalStateException("Claude declined to respond to this conversation.")
        }

        val content = root.optJSONArray("content")
            ?: throw IllegalStateException("Unexpected response from Claude.")

        // With output_config.format the first text block is guaranteed valid JSON.
        var text: String? = null
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                text = block.optString("text")
                break
            }
        }
        val payload = text ?: throw IllegalStateException("Claude returned no text.")
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
